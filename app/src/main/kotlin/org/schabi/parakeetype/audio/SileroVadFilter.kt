package org.schabi.parakeetype.audio

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.util.Log
import java.nio.FloatBuffer
import java.nio.LongBuffer

private const val TAG = "SileroVadFilter"

/**
 * A stateful neural-network VAD filter using Silero VAD v6 (ONNX).
 *
 * Silero provides highly accurate frame-level speech probabilities. We wrap this
 * raw probability stream with exactly the same onset, pre-roll, and hangover smoothing
 * logic used in the energy-based [RMSVadFilter].
 *
 * The RNN state tensor and the 64-sample context (the tail of the previous chunk, which the
 * model expects in front of every chunk) are preserved across chunks in a continuous
 * recording session, and reset when [flush] is called.
 */
class SileroVadFilter(
    modelBytes: ByteArray,
    private val threshold: Float = 0.3f,
    private val leadInFrames: Int = LEAD_IN_FRAMES,
    private val onsetFrames: Int = ONSET_FRAMES,
) : VadFilter {

    private enum class State { SILENCE, SPEECH }

    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null

    // Silero RNN state: [2, 1, 128] float tensor
    private val rnnState: FloatArray = FloatArray(STATE_SIZE)

    // Input buffer: the last CONTEXT_SAMPLES of the previous chunk, followed by the current chunk
    private val input: FloatArray = FloatArray(CONTEXT_SAMPLES + REQUIRED_SAMPLES)

    private var state = State.SILENCE
    private var onsetCount = 0
    private var hangoverFrames = 0
    private var consecutiveSilenceFrames = 0
    private val leadIn = ArrayDeque<AudioChunk>(leadInFrames + 1)

    override val isSpeechActive: Boolean get() = state == State.SPEECH

    override var lastSpeechProbability: Float = 0f
        private set

    init {
        try {
            env = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(1)
                setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
            }
            session = env?.createSession(modelBytes, opts)
            Log.d(TAG, "Silero VAD loaded successfully from RAW resource")
            opts.close()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load Silero VAD model from bytes", e)
            session = null
        }
    }

    override fun process(chunk: AudioChunk, rms: Float): List<AudioChunk> {
        val e = env
        val s = session
        var speechProb = 0f

        if (e != null && s != null && chunk.samples.size == REQUIRED_SAMPLES) {
            try {
                // Silero requires float32 samples in range [-1.0, 1.0], behind the context
                // samples that are already at the start of [input].
                for (i in 0 until REQUIRED_SAMPLES) input[CONTEXT_SAMPLES + i] = chunk.samples[i] / 32_768f

                val inputTensor = OnnxTensor.createTensor(
                    e,
                    FloatBuffer.wrap(input),
                    longArrayOf(1, input.size.toLong())
                )
                val srTensor =
                    OnnxTensor.createTensor(e, LongBuffer.wrap(longArrayOf(SAMPLE_RATE.toLong())), longArrayOf(1))
                val stateTensor = OnnxTensor.createTensor(e, FloatBuffer.wrap(rnnState), longArrayOf(2, 1, 128))

                val inputs = mapOf(
                    "input" to inputTensor,
                    "sr" to srTensor,
                    "state" to stateTensor
                )

                s.run(inputs).use { result ->
                    val outputTensor = result.get("output").get() as OnnxTensor
                    speechProb = outputTensor.floatBuffer[0]

                    val stateNTensor = result.get("stateN").get() as OnnxTensor
                    stateNTensor.floatBuffer.get(rnnState)
                }

                inputTensor.close()
                srTensor.close()
                stateTensor.close()

                // The tail of this chunk is the context of the next one.
                input.copyInto(input, destinationOffset = 0, startIndex = REQUIRED_SAMPLES)
            } catch (ex: Exception) {
                Log.e(TAG, "Silero VAD inference failed", ex)
            }
        } else {
            // Unaligned chunk size or missing session: Fallback purely on RMS gating just to avoid crashing pipeline.
            speechProb = if (rms > 0.01f) 1.0f else 0.0f
        }

        lastSpeechProbability = speechProb
        return applySmoothing(chunk, speechProb)
    }

    private fun applySmoothing(chunk: AudioChunk, prob: Float): List<AudioChunk> {
        return when (state) {
            State.SILENCE -> {
                if (leadIn.size >= leadInFrames) leadIn.removeFirst()
                leadIn.addLast(chunk)

                if (prob >= threshold) {
                    onsetCount++
                    if (onsetCount >= onsetFrames) {
                        state = State.SPEECH
                        onsetCount = 0
                        hangoverFrames = 0
                        consecutiveSilenceFrames = 0
                        val out = leadIn.toList()
                        leadIn.clear()
                        out
                    } else {
                        emptyList()
                    }
                } else {
                    onsetCount = 0
                    consecutiveSilenceFrames++
                    if (consecutiveSilenceFrames == SILENCE_BOUNDARY_FRAMES) {
                        consecutiveSilenceFrames = 0
                        listOf(AudioChunk(ShortArray(0), isSilenceBoundary = true))
                    } else {
                        emptyList()
                    }
                }
            }

            State.SPEECH -> {
                if (prob >= threshold) {
                    hangoverFrames = 0
                    listOf(chunk)
                } else {
                    hangoverFrames++
                    if (hangoverFrames >= HANGOVER_FRAMES) {
                        state = State.SILENCE
                        hangoverFrames = 0
                        onsetCount = 0
                        leadIn.clear()
                        emptyList()
                    } else {
                        listOf(chunk)
                    }
                }
            }
        }
    }

    override fun flush(): Boolean {
        val wasSpeech = state == State.SPEECH
        state = State.SILENCE
        hangoverFrames = 0
        onsetCount = 0
        consecutiveSilenceFrames = 0
        leadIn.clear()

        // Reset RNN state and context for the next recording session
        rnnState.fill(0f)
        input.fill(0f)

        return wasSpeech
    }

    override fun close() {
        session?.close()
        session = null
    }

    companion object {
        private const val SAMPLE_RATE = 16_000

        /** 32 ms window at 16 kHz = 512 samples. Required by Silero v5+ ONNX. */
        internal const val REQUIRED_SAMPLES = 512

        /** Samples of the previous chunk the model expects in front of every chunk (at 16 kHz). */
        private const val CONTEXT_SAMPLES = 64

        /** Size of the RNN state tensor [2, 1, 128]. */
        private const val STATE_SIZE = 2 * 1 * 128

        /** 1 frame × 32 ms = 32 ms - opens gate on first confirmed speech frame to avoid clipping onset phonemes. */
        private const val ONSET_FRAMES = 1

        /** 20 frames × 32 ms = 640 ms - gives model pre-speech silence as acoustic context. */
        private const val LEAD_IN_FRAMES = 20

        /** 15 frames × 32 ms = 480 ms - captures trailing soft syllables. */
        private const val HANGOVER_FRAMES = 15

        /** 20 frames × 32 ms = 640 ms of silence after hangover before emitting an utterance boundary. */
        internal const val SILENCE_BOUNDARY_FRAMES = 20

        // ── Short-utterance VAD parameters ───────────────────────────────────────────
        // These constants define tighter onset and lead-in values intended for the
        // short-utterance path (< 2.5 s of VAD-active audio).  Reducing onset latency
        // and lead-in avoids clipping the very first phoneme of a brief command.
        //
        // TODO: Wire these into the short-utterance path once the VAD parameter
        //   injection API is in place.  The current architecture does not support
        //   per-path VAD configuration — SileroVadFilter is constructed once and
        //   shared for both short and long recordings — so these values are documented
        //   here as named constants for A/B testing readiness but are NOT applied yet.

        /** Target onset gate for the short-utterance path: 1 frame × 32 ms = 32 ms. */
        internal const val SHORT_UTT_VAD_ONSET_MS = 30

        /** Target lead-in for the short-utterance path: ~6 frames × 32 ms ≈ 200 ms. */
        internal const val SHORT_UTT_VAD_LEAD_IN_MS = 200
    }
}

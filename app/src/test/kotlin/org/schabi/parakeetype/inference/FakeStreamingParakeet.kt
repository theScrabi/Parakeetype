package org.schabi.parakeetype.inference

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import java.nio.FloatBuffer
import java.nio.LongBuffer
import org.schabi.parakeetype.audio.AudioChunk

/**
 * A deterministic [ChunkStreamingEngine] stand-in for [ParakeetEngine] that runs the
 * *real* chunked-TDT streaming state machine in [InferenceRepository] without ONNX
 * inference.
 *
 * It emulates the TDT decoder as a frame-accurate token source:
 *  - [encodeBuffer] returns the same encoder frame count the real nemo128+encoder
 *    pipeline produces for a given sample length (`ceil((n/160 + 1)/8)`), so the
 *    repository's frame arithmetic ([frameOffset], left/right context offsets,
 *    buffer-ending chunks) runs against identical shape.
 *  - [decodeChunk] maps the decoded frame range back to absolute sample positions and
 *    emits one token per scripted word whose start position falls inside the range.
 *    The frame-to-sample mapping uses the encoder's 1280-sample frame stride
 *    (12.5 frames/s), matching [frameOffset]'s inverse.
 *  - The carried [TdtState] always returns `frameDelta = 0` (the fake consumes its
 *    frame range exactly), so the next chunk's nominal start lines up with the
 *    previous chunk's end - the same invariant the real decoder maintains through
 *    its duration-jump bookkeeping.
 *
 * The word script is a list of (absoluteSampleStart, word). Words are numbered
 * (`w0001`, `w0002`, ...) so the transcript is trivially checkable: no fillers, no
 * repetitions, no punctuation - the cleaning pipeline is a pure passthrough on it,
 * which means the repository's output must equal the script in order.
 *
 * [perChunkDecodeMs] simulates device-side ONNX latency (encoder + joint calls):
 * each decode sleeps for that long, so tests can model a device where decoding is
 * slower than real time (2 s of audio per chunk).
 */
class FakeStreamingParakeet(
    private val script: List<Pair<Int, String>>,
    private val perChunkDecodeMs: Long = 0,
) : ChunkStreamingEngine, SpeechEngine {

    private val wordId = HashMap<String, Int>()

    // Minimal [SpeechEngine] surface: the streaming tests never call load/transcribe;
    // the repository's streaming branch only needs [ChunkStreamingEngine].
    override val isLoaded: Boolean get() = true
    override fun load(modelDir: java.io.File) { /* not used by the streaming tests */
    }
    override fun transcribe(chunk: AudioChunk): TranscriptResult =
        TranscriptResult.Failure(IllegalStateException("not used"))
    override fun close() { /* not used */
    }
    private val idToWord = HashMap<Int, String>()
    init {
        script.forEachIndexed { idx, (_, word) ->
            val id = TOKEN_BASE + idx
            wordId[word] = id
            idToWord[id] = word
        }
    }

    /** Absolute sample position of the next chunk's first frame. Reset by [initialTdtState]. */
    private var nextAbsSample = 0

    /** Number of [decodeChunk] calls - lets tests assert the chunk cadence. */
    var decodeCalls: Int = 0
        private set

    override fun initialTdtState(): TdtState {
        nextAbsSample = 0
        return TdtState(FloatArray(2 * 640), FloatArray(2 * 640), 0)
    }

    override fun encodeBuffer(samples: FloatArray): Pair<OnnxTensor, Int> {
        // Same frame count as the real encoder: ceil((n/160 + 1)/8).
        val frameCount = (samples.size / 160 + 1 + 7) / 8
        val dummy = OnnxTensor.createTensor(
            OrtEnvironment.getEnvironment(),
            FloatBuffer.wrap(FloatArray(1) { 0f }),
            longArrayOf(1L, 1L, 1L.toLong()),
        )
        return dummy to frameCount
    }

    override fun decodeChunk(
        encoderOut: OnnxTensor,
        totalLength: Int,
        frameStart: Int,
        frameEnd: Int,
        state: TdtState,
    ): ChunkDecodeResult {
        if (perChunkDecodeMs > 0) Thread.sleep(perChunkDecodeMs)
        decodeCalls++

        // The chunk's absolute sample range: [nextAbsSample, nextAbsSample + frames*1280).
        // The per-buffer 100 ms padding frame makes the upper bound a small over-estimate
        // (never an under-estimate), so no scripted word at a chunk boundary is missed.
        val rangeEnd = nextAbsSample + (frameEnd - frameStart) * SAMPLES_PER_FRAME
        val tokens = script
            .filter { (start, _) -> start in nextAbsSample until rangeEnd }
            .map { (_, word) -> wordId[word]!! }

        // A full 25-frame (2 s) stride advances the absolute cursor by one chunk;
        // flush-tail chunks (shorter than a full stride) leave the cursor for the
        // next tail piece of the same leftover.
        if (frameEnd - frameStart == CHUNK_FRAMES) {
            nextAbsSample += CHUNK_SAMPLES
        }

        val logProb = Math.log(0.9)
        return ChunkDecodeResult(
            tokens = tokens,
            state = TdtState(
                state.lstmState1.copyOf(),
                state.lstmState2.copyOf(),
                tokens.lastOrNull() ?: 0,
                frameDelta = 0,
            ),
            logProbSum = tokens.size * logProb,
            emissionCount = tokens.size,
        )
    }

    override fun detokenizeTokens(tokens: List<Int>): String =
        tokens.joinToString(" ") { idToWord[it] ?: "?" }

    companion object {
        private const val TOKEN_BASE = 10_000
        private const val SAMPLES_PER_FRAME = 1280   // 16 000 Hz / 12.5 fps
        private const val CHUNK_FRAMES = 25         // 2 s chunk at 12.5 fps
        private const val CHUNK_SAMPLES = 32_000    // 2 s at 16 kHz
    }
}

/**
 * Builds a word script: [words] words at [intervalMs] ms intervals starting at sample 0.
 * `w0001 w0002 ...` - numbered so the expected transcript is derivable and the
 * cleaning pipeline (fillers, dedup, periods, numbers) is a passthrough.
 */
fun buildWordScript(words: Int, intervalMs: Int): List<Pair<Int, String>> =
    List(words) { i -> (i * intervalMs * 16) to "w%04d".format(i + 1) }

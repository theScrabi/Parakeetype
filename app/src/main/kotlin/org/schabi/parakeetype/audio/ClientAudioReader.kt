package org.schabi.parakeetype.audio

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import java.io.IOException
import java.io.InputStream

private const val TAG = "ClientAudioReader"

/** 32 ms at 16 kHz - Silero VAD's frame size, the same chunks [AudioCaptureManager] emits. */
private const val FRAME_SAMPLES = 512

private const val READ_BUFFER_BYTES = 8_192

/**
 * Reads the audio a recognizer client supplies instead of the microphone
 * ([android.speech.RecognizerIntent.EXTRA_AUDIO_SOURCE]: a file or the read end of a pipe the
 * client writes into) and emits it like [AudioCaptureManager.startCapture]: 32 ms
 * [AudioChunk]s at 16 kHz mono, filtered by the VAD.
 *
 * The input is read as fast as it arrives; the flow completes at the end of the input or
 * after [stop]. The reader owns [input] and closes it.
 *
 * @param loadVad Creates the VAD (called on [Dispatchers.IO]), e.g. [VadFilter.load].
 */
class ClientAudioReader(
    private val input: InputStream,
    format: PcmFormat,
    private val loadVad: () -> VadFilter,
) {

    private val converter = PcmConverter(format)

    @Volatile
    private var stopRequested = false

    /**
     * Ends the input: no audio after the frame currently being processed is emitted. Closes
     * [input], which also unblocks a read waiting for a pipe. Safe to call from any thread.
     */
    fun stop() {
        if (stopRequested) return
        stopRequested = true
        try {
            input.close()
        } catch (e: IOException) {
            Log.w(TAG, "Closing the client audio failed", e)
        }
    }

    /**
     * Emits the client's audio. Can be collected only once.
     *
     * @param onLevel Called on the reading thread with the normalised RMS in [0.0, 1.0] of
     *   every frame, before VAD filtering.
     * @param onSpeechProbability Called on the reading thread with the VAD's raw speech
     *   probability of every frame; feeds a [SpeechEndpointer]. Calling [stop] from it drops
     *   all audio after that frame.
     * @throws IOException if reading the input fails.
     */
    fun read(
        onLevel: ((Float) -> Unit)? = null,
        onSpeechProbability: ((Float) -> Unit)? = null,
    ): Flow<AudioChunk> = channelFlow {
        val vad = loadVad()
        val frame = ShortArray(FRAME_SAMPLES)
        var frameSize = 0

        suspend fun emitFrame(samples: ShortArray) {
            val rms = calculateRms(samples)
            onLevel?.invoke(rms)
            for (chunk in vad.process(AudioChunk(samples), rms)) send(chunk)
            onSpeechProbability?.invoke(vad.lastSpeechProbability)
        }

        suspend fun emitSamples(samples: ShortArray) {
            for (sample in samples) {
                if (stopRequested) return
                frame[frameSize++] = sample
                if (frameSize == FRAME_SAMPLES) {
                    emitFrame(frame.copyOf())
                    frameSize = 0
                }
            }
        }

        try {
            val buffer = ByteArray(READ_BUFFER_BYTES)
            while (currentCoroutineContext().isActive && !stopRequested) {
                val read = try {
                    input.read(buffer)
                } catch (e: IOException) {
                    // stop() closed the input under a blocked read.
                    if (stopRequested) break else throw e
                }
                if (read < 0) break
                emitSamples(converter.convert(buffer, read))
            }
            if (!stopRequested) {
                Log.d(TAG, "End of client audio")
                emitSamples(converter.finish())
                // The VAD needs whole frames: pad the last one with silence.
                if (frameSize > 0) emitFrame(frame.copyOf(frameSize).copyOf(FRAME_SAMPLES))
            }
            for (chunk in vad.drainHangover(FRAME_SAMPLES)) send(chunk)
        } finally {
            vad.flush()
            vad.close()
            try {
                input.close()
            } catch (e: IOException) {
                Log.w(TAG, "Closing the client audio failed", e)
            }
        }
    }.flowOn(Dispatchers.IO)
}

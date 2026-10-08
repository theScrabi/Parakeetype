package org.schabi.parakeetype.audio

import android.content.Context
import android.util.Log
import org.schabi.parakeetype.settings.model.ModelStorageManager
import org.schabi.parakeetype.settings.model.SILERO_VAD_FILE

private const val TAG = "VadFilter"

/**
 * Maximum silence frames pumped through the VAD during the trailing drain phase.
 * 20 frames × 32 ms = 640 ms - generously above [RMSVadFilter]'s 15-frame / 450 ms hangover
 * so the hangover always expires before this cap is hit.
 */
private const val HANGOVER_DRAIN_SAFETY_FRAMES = 20

/**
 * Common interface for Voice Activity Detection filters.
 */
interface VadFilter {
    /** `true` while outputting speech frames (including during the hangover window). */
    val isSpeechActive: Boolean

    /**
     * Speech probability in [0.0, 1.0] of the last chunk passed to [process], before the
     * onset / hangover smoothing. End-of-speech detection uses it: the smoothed output
     * opens on a single frame above a low threshold, so a breath or click would count as
     * speech.
     */
    val lastSpeechProbability: Float

    /**
     * Processes a single chunk of audio and returns a list of chunks to forward to inference.
     * Returns an empty list during silence, and buffers leading audio to emit on speech onset.
     */
    fun process(chunk: AudioChunk, rms: Float): List<AudioChunk>

    /**
     * Resets internal state. Should be called when a recording session ends.
     * Returns `true` if the session ended while speech was active (meaning trailing audio
     * might have been cut off).
     */
    fun flush(): Boolean

    /** Releases any resources held by the filter (like native ONNX sessions). */
    fun close() {}

    companion object {
        /**
         * The Silero VAD from the installed model archive, or the energy VAD when it cannot be
         * loaded.
         */
        fun load(context: Context): VadFilter = try {
            val sileroFile = checkNotNull(ModelStorageManager.findVadModel(context)) {
                "no installed model contains $SILERO_VAD_FILE"
            }
            SileroVadFilter(modelBytes = sileroFile.readBytes(), threshold = 0.3f)
        } catch (e: Exception) {
            Log.w(TAG, "Silero VAD model could not be loaded. Falling back to Energy VAD.", e)
            RMSVadFilter(0.4f)
        }
    }
}

/**
 * Feeds silence frames of [frameSamples] samples until the VAD's hangover expires and returns
 * the chunks it still emits, so the tail of the last word is not cut off when the input ends
 * while speech is active.
 */
fun VadFilter.drainHangover(frameSamples: Int): List<AudioChunk> {
    if (!isSpeechActive) return emptyList()
    val silence = AudioChunk(ShortArray(frameSamples))
    val drained = mutableListOf<AudioChunk>()
    var safetyFrames = HANGOVER_DRAIN_SAFETY_FRAMES
    while (isSpeechActive && safetyFrames-- > 0) {
        drained += process(silence, 0f)
    }
    Log.d(TAG, "VAD hangover drain complete (framesLeft=$safetyFrames)")
    return drained
}

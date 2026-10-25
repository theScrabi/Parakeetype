package org.schabi.parakeetype.audio

/**
 * A single chunk of raw PCM audio captured from the microphone.
 *
 * @param samples           16-bit signed PCM samples (mono, [sampleRate] Hz).
 * @param sampleRate        Capture sample rate; always 16 000 Hz for Parakeet.
 * @param timestampMs       Position (ms) of the chunk's first sample in the captured audio, counted
 *                          from the start of capture. The VAD drops silent chunks, so it is what
 *                          maps the audio that reaches the recogniser back to when it was said.
 * @param isSilenceBoundary When true this is a zero-sample sentinel emitted by the VAD after
 *                          a sustained silence period. InferenceRepository flushes the rolling
 *                          window and emits a Final result when it receives this marker.
 */
/** Duration in ms of [samples] samples at [PIPELINE_SAMPLE_RATE]: the position of a chunk that starts after them. */
fun samplesToMs(samples: Long): Long = samples * 1_000 / PIPELINE_SAMPLE_RATE

data class AudioChunk(
    val samples: ShortArray,
    val sampleRate: Int = 16_000,
    val timestampMs: Long = 0,
    val isSilenceBoundary: Boolean = false,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AudioChunk) return false
        return sampleRate == other.sampleRate &&
                timestampMs == other.timestampMs &&
                isSilenceBoundary == other.isSilenceBoundary &&
                samples.contentEquals(other.samples)
    }

    override fun hashCode(): Int {
        var result = samples.contentHashCode()
        result = 31 * result + sampleRate
        result = 31 * result + timestampMs.hashCode()
        result = 31 * result + isSilenceBoundary.hashCode()
        return result
    }
}

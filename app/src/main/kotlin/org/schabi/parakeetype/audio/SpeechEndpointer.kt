package org.schabi.parakeetype.audio

/** Speech probability from which a VAD frame counts towards speech (Silero's reference threshold). */
private const val SPEECH_PROBABILITY = 0.5f

/**
 * Consecutive speech frames (8 × 32 ms = 256 ms) that make up speech. With Silero v6 the
 * shortest words ("no", "hello") stay above [SPEECH_PROBABILITY] for 12+ frames; taps and
 * breaths on their own never reach it, but a breath right after speech can hold it for ~6.
 */
private const val SUSTAINED_SPEECH_FRAMES = 8

/** Duration of one VAD frame (512 samples at 16 kHz). */
private const val FRAME_MS = 32L

/**
 * End-of-speech detection from the VAD's per-frame speech probability
 * ([VadFilter.lastSpeechProbability], delivered by [AudioCaptureManager.startCapture]'s
 * `onSpeechProbability`).
 *
 * It deliberately does not use the VAD's filtered output: that opens on a single frame above
 * a low threshold and replays its lead-in, so a breath or a tap after the user stopped
 * speaking looked like resumed speech and kept postponing the end of speech indefinitely.
 * Here only [SUSTAINED_SPEECH_FRAMES] consecutive frames of likely speech count, and the
 * silence is measured from the end of the last such run.
 *
 * Time is counted in frames, which arrive in real time from the microphone, so the class
 * needs no clock or timers. Not thread-safe: feed it from the capture thread only.
 *
 * @param silenceMs Silence after speech that ends it.
 * @param noSpeechTimeoutMs Without any speech for this long, [Event.NoSpeech]; `null` = wait forever.
 * @param minimumLengthMs [Event.EndOfSpeech] is not reported before this much audio.
 */
class SpeechEndpointer(
    private val silenceMs: Long,
    private val noSpeechTimeoutMs: Long? = null,
    private val minimumLengthMs: Long = 0L,
) {
    enum class Event {
        /** The first speech of the session. */
        SpeechStart,

        /** The user stopped speaking. Terminal. */
        EndOfSpeech,

        /** Nothing was said within the no-speech timeout. Terminal. */
        NoSpeech,
    }

    private var frames = 0L
    private var speechRun = 0
    private var silentFrames = 0L
    private var heardSpeech = false
    private var done = false

    /** One VAD frame's speech probability; returns the event it triggers, if any. */
    fun onFrame(speechProbability: Float): Event? {
        if (done) return null
        frames++
        speechRun = if (speechProbability >= SPEECH_PROBABILITY) speechRun + 1 else 0
        if (speechRun >= SUSTAINED_SPEECH_FRAMES) {
            silentFrames = 0
            if (!heardSpeech) {
                heardSpeech = true
                return Event.SpeechStart
            }
            return null
        }
        silentFrames++
        return when {
            heardSpeech && silentFrames * FRAME_MS >= silenceMs && frames * FRAME_MS >= minimumLengthMs ->
                finish(Event.EndOfSpeech)

            !heardSpeech && noSpeechTimeoutMs != null && frames * FRAME_MS >= noSpeechTimeoutMs ->
                finish(Event.NoSpeech)

            else -> null
        }
    }

    private fun finish(event: Event): Event {
        done = true
        return event
    }
}

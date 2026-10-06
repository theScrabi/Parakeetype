package org.schabi.parakeetype.audio

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.schabi.parakeetype.audio.SpeechEndpointer.Event
import org.schabi.parakeetype.inference.WavReader
import org.schabi.parakeetype.inference.openWavFixture
import org.schabi.parakeetype.inference.resolveSileroModel
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * [SpeechEndpointer] ends a session after the silence following sustained speech, and is not
 * fooled by breaths or taps (short, low-probability VAD frames) after the user stopped
 * speaking.
 */
class SpeechEndpointerTest {

    private val frameMs = 32

    /** [ms] rounded up to whole frames: the endpointer counts time in frames. */
    private fun frames(ms: Int) = (ms + frameMs - 1) / frameMs * frameMs

    /** The time [feed] actually feeds for [ms]: whole frames, rounded down. */
    private fun fed(ms: Int) = ms / frameMs * frameMs

    /** Feeds [ms] of frames with [probability]; returns the events with their time. */
    private fun SpeechEndpointer.feed(ms: Int, probability: Float, events: MutableList<Pair<Int, Event>>, clock: IntArray) {
        repeat(ms / frameMs) {
            clock[0] += frameMs
            onFrame(probability)?.let { events.add(clock[0] to it) }
        }
    }

    @Test
    fun `ends after the silence that follows speech`() {
        val e = SpeechEndpointer(silenceMs = 2_000, noSpeechTimeoutMs = 8_000)
        val events = mutableListOf<Pair<Int, Event>>()
        val clock = IntArray(1)
        e.feed(300, 0.1f, events, clock)
        e.feed(900, 0.9f, events, clock)
        e.feed(5_000, 0.05f, events, clock)

        assertThat(events.map { it.second }).containsExactly(Event.SpeechStart, Event.EndOfSpeech)
        assertThat(events.last().first).isEqualTo(fed(300) + fed(900) + frames(2_000))
    }

    @Test
    fun `ends when nothing is said`() {
        val e = SpeechEndpointer(silenceMs = 2_000, noSpeechTimeoutMs = 8_000)
        val events = mutableListOf<Pair<Int, Event>>()
        val clock = IntArray(1)
        e.feed(10_000, 0.1f, events, clock)

        assertThat(events).containsExactly(frames(8_000) to Event.NoSpeech)
    }

    @Test
    fun `without a no-speech timeout silence never ends the session`() {
        val e = SpeechEndpointer(silenceMs = 1_000, noSpeechTimeoutMs = null)
        val events = mutableListOf<Pair<Int, Event>>()
        e.feed(60_000, 0f, events, IntArray(1))

        assertThat(events).isEmpty()
    }

    @Test
    fun `resumed speech restarts the silence`() {
        val e = SpeechEndpointer(silenceMs = 2_000)
        val events = mutableListOf<Pair<Int, Event>>()
        val clock = IntArray(1)
        e.feed(600, 0.9f, events, clock)
        e.feed(1_500, 0.1f, events, clock)
        e.feed(600, 0.9f, events, clock)
        e.feed(3_000, 0.1f, events, clock)

        assertThat(events.map { it.second }).containsExactly(Event.SpeechStart, Event.EndOfSpeech)
        assertThat(events.last().first).isEqualTo(fed(600) + fed(1_500) + fed(600) + frames(2_000))
    }

    @Test
    fun `short blips after speech do not postpone the end`() {
        val e = SpeechEndpointer(silenceMs = 2_000)
        val events = mutableListOf<Pair<Int, Event>>()
        val clock = IntArray(1)
        e.feed(600, 0.9f, events, clock)
        e.feed(300, 0.1f, events, clock)
        // A breath / tap every 450 ms: two likely-speech frames, never a sustained run.
        repeat(10) {
            e.feed(64, 0.8f, events, clock)
            e.feed(384, 0.1f, events, clock)
        }

        assertThat(events.map { it.second }).containsExactly(Event.SpeechStart, Event.EndOfSpeech)
        assertThat(events.last().first).isEqualTo(fed(600) + frames(2_000))
    }

    @Test
    fun `blips alone are not speech`() {
        val e = SpeechEndpointer(silenceMs = 1_000, noSpeechTimeoutMs = 8_000)
        val events = mutableListOf<Pair<Int, Event>>()
        val clock = IntArray(1)
        repeat(40) {
            e.feed(96, 0.9f, events, clock)
            e.feed(192, 0.1f, events, clock)
        }

        assertThat(events).containsExactly(frames(8_000) to Event.NoSpeech)
    }

    @Test
    fun `does not end before the minimum length`() {
        val e = SpeechEndpointer(silenceMs = 500, minimumLengthMs = 3_000)
        val events = mutableListOf<Pair<Int, Event>>()
        val clock = IntArray(1)
        e.feed(600, 0.9f, events, clock)
        e.feed(5_000, 0.1f, events, clock)

        assertThat(events.last()).isEqualTo(frames(3_000) to Event.EndOfSpeech)
    }

    @Test
    fun `reports nothing after the terminal event`() {
        val e = SpeechEndpointer(silenceMs = 500)
        val events = mutableListOf<Pair<Int, Event>>()
        val clock = IntArray(1)
        e.feed(600, 0.9f, events, clock)
        e.feed(1_000, 0.1f, events, clock)
        e.feed(600, 0.9f, events, clock)
        e.feed(1_000, 0.1f, events, clock)

        assertThat(events.map { it.second }).containsExactly(Event.SpeechStart, Event.EndOfSpeech)
    }

    // ── Real Silero VAD ──────────────────────────────────────────────────────────────────

    private val rnd = Random(7)

    private fun noise(n: Int, dbfs: Double, brown: Boolean): DoubleArray {
        var b = 0.0
        val raw = DoubleArray(n) {
            val w = rnd.nextDouble(-1.0, 1.0)
            if (brown) { b = 0.98 * b + 0.2 * w; b } else w
        }
        val rms = sqrt(raw.sumOf { it * it } / n)
        val amp = 32768.0 * Math.pow(10.0, dbfs / 20.0)
        return DoubleArray(n) { raw[it] / rms * amp }
    }

    /**
     * Regression: a speech clip followed by a noise floor with a soft breath and a tap on the
     * phone every 1.2 s. Each of them opens the Silero VAD's output again (one frame above its
     * 0.3 threshold), which used to postpone the end of speech forever; the endpointer must
     * still end [silenceMs] after the speech.
     */
    @Test
    fun `real VAD - breaths and taps after speech do not keep the session open`() {
        val model = resolveSileroModel()
        val speech = WavReader().loadAsSingleChunk(openWavFixture("long-sentence.wav")).samples
        val tailN = 16_000 * 6
        val tail = noise(tailN, -55.0, brown = true)
        val breath = noise(4_800, -40.0, brown = false).also { b ->
            for (i in b.indices) b[i] *= sin(PI * i / b.size)
        }
        val tap = noise(640, -25.0, brown = true).also { t ->
            for (i in t.indices) t[i] *= Math.exp(-i / 120.0) * 3
        }
        var pos = 16_000 * 9 / 10
        var useBreath = true
        while (pos + breath.size < tailN) {
            val t = if (useBreath) breath else tap
            for (i in t.indices) tail[pos + i] += t[i]
            useBreath = !useBreath
            pos += 16_000 * 12 / 10
        }
        val bed = noise(speech.size, -55.0, brown = true)
        val audio = ShortArray(speech.size + tailN) {
            val v = if (it < speech.size) speech[it] + bed[it] else tail[it - speech.size]
            v.toInt().coerceIn(-32768, 32767).toShort()
        }

        val silenceMs = 2_000L
        val vad = SileroVadFilter(model.readBytes(), threshold = 0.3f)
        val endpointer = SpeechEndpointer(silenceMs = silenceMs, noSpeechTimeoutMs = 8_000)
        var endAtMs = -1L
        var vadReopened = false
        var frame = 0
        val speechEndFrame = speech.size / 512
        var i = 0
        while (i + 512 <= audio.size && endAtMs < 0) {
            val out = vad.process(AudioChunk(audio.copyOfRange(i, i + 512)), 0f)
            // Well after the speech decayed, any VAD output is a transient reopening it.
            if (frame > speechEndFrame + 25 && out.any { !it.isSilenceBoundary }) vadReopened = true
            if (endpointer.onFrame(vad.lastSpeechProbability) == Event.EndOfSpeech) {
                endAtMs = (frame - speechEndFrame) * 32L
            }
            frame++
            i += 512
        }
        vad.close()

        // The fixture must actually exercise the old failure: the VAD output reopens.
        assertThat(vadReopened).isTrue()
        assertThat(endAtMs).isBetween(silenceMs - 500, silenceMs + 300)
    }
}

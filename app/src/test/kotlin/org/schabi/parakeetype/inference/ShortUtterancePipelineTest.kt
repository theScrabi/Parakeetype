package org.schabi.parakeetype.inference

import org.schabi.parakeetype.audio.AudioChunk
import org.schabi.parakeetype.audio.SileroVadFilter
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.AfterClass
import org.junit.Assume
import org.junit.BeforeClass
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * Integration tests for the short-utterance decode path (the decode-context retry in
 * `InferenceRepository.flushAsFinal`).
 *
 * The TDT decoder is extremely context-sensitive on short clips: real-model probes show
 * 600 ms of leading silence flips blank decodes into correct words, while trailing
 * digital silence can flip a correct decode into blank. These tests pin the production
 * behavior end-to-end: fixture → (Silero VAD) → `InferenceRepository.transcribe()` →
 * Final, and assert that silent/noisy short inputs produce no Final.
 *
 * Skipped gracefully when the model directory is unavailable (same convention as
 * [ParakeetEngineRealAudioTest]).
 */
class ShortUtterancePipelineTest {

    companion object {
        private val engine: SpeechEngine = ParakeetEngine()
        private var modelAvailable = false

        @BeforeClass
        @JvmStatic
        fun loadModel() {
            val modelDir = try {
                resolveModelDir()
            } catch (e: IllegalStateException) {
                Assume.assumeFalse("Parakeet model directory not available", true)
                return
            }
            engine.load(modelDir)
            modelAvailable = true
        }

        @AfterClass
        @JvmStatic
        fun closeModel() {
            if (modelAvailable) engine.close()
        }
    }

    private val wavReader = WavReader()

    private fun wavSamples(name: String): ShortArray {
        val stream = openWavFixture(name)
        val pcm = wavReader.readPcm16(ByteArrayInputStream(stream.readBytes()))
        return wavReader.resampleLinear(pcm.samples, pcm.sampleRate, 16_000)
    }

    private fun chunkify(samples: ShortArray, chunkSize: Int = 640): List<AudioChunk> {
        val chunks = mutableListOf<AudioChunk>()
        var i = 0
        while (i < samples.size) {
            val end = minOf(i + chunkSize, samples.size)
            chunks.add(AudioChunk(samples.copyOfRange(i, end)))
            i = end
        }
        return chunks
    }

    /** Scales amplitude by [gain] (clamped to 16-bit range). */
    private fun scale(samples: ShortArray, gain: Float): ShortArray =
        ShortArray(samples.size) { (samples[it] * gain).toInt().coerceIn(-32768, 32767).toShort() }

    /** Time-stretches [samples] by [factor] (>1 = faster/shorter) via linear resample. */
    private fun stretch(samples: ShortArray, factor: Float): ShortArray {
        val outLen = (samples.size / factor).toInt().coerceAtLeast(1)
        val out = ShortArray(outLen)
        for (i in 0 until outLen) out[i] = samples[(i * factor).toInt().coerceAtMost(samples.size - 1)]
        return out
    }

    /** Adds uniform white noise of half-amplitude [amp] with a deterministic [seed]. */
    private fun addNoise(samples: ShortArray, seed: Long, amp: Int): ShortArray {
        val rng = java.util.Random(seed)
        return ShortArray(samples.size) {
            (samples[it].toInt() + (rng.nextInt(2 * amp + 1) - amp)).coerceIn(-32768, 32767).toShort()
        }
    }

    /** Runs [samples] through the repository's streaming path; returns all results. */
    private fun transcribe(samples: ShortArray): List<TranscriptResult> {
        val repo = InferenceRepository(engine)
        return runBlocking {
            repo.transcribe(flowOf(*chunkify(samples).toTypedArray()), postprocessingEnabled = true)
                .toList()
        }
    }

    /**
     * Splits [samples] into 480-sample (30 ms) chunks and runs them through the real
     * Silero VAD (production threshold), returning the VAD-emitted samples — the exact
     * audio a recording session hands to the repository.
     */
    private fun vadProcess(samples: ShortArray): ShortArray {
        val vad = SileroVadFilter(modelBytes = resolveSileroModel().readBytes(), threshold = 0.3f)
        val out = ArrayList<ShortArray>()
        var i = 0
        while (i + 480 <= samples.size) {
            for (c in vad.process(AudioChunk(samples.copyOfRange(i, i + 480)), 0f))
                if (c.samples.isNotEmpty()) out.add(c.samples)
            i += 480
        }
        if (i < samples.size) {
            for (c in vad.process(AudioChunk(samples.copyOfRange(i, samples.size)), 0f))
                if (c.samples.isNotEmpty()) out.add(c.samples)
        }
        vad.flush()
        vad.close()
        return out.fold(ShortArray(0)) { a, b -> a + b }
    }

    @Test
    fun `short word without lead-in silence is transcribed`() {
        Assume.assumeTrue(modelAvailable)
        // The fixture starts with speech almost immediately (little or no pre-roll), as
        // when the user starts talking right after pressing the talk button. The
        // short-utterance decode must prepend the lead silence to anchor the word.
        val results = transcribe(wavSamples("single-yes.wav"))
        val finals = results.filterIsInstance<TranscriptResult.Final>()
        assertThat(finals)
            .describedAs("short word with no lead-in must still produce a Final: $results")
            .isNotEmpty
        assertThat(finals.last().text.lowercase()).contains("yes")
    }

    @Test
    fun `short word through the vad is transcribed`() {
        Assume.assumeTrue(modelAvailable)
        val vadOut = vadProcess(wavSamples("single-yes.wav"))
        assertThat(vadOut.size)
            .describedAs("VAD must pass the speech through")
            .isGreaterThan(4_000)   // > 0.25 s
        val results = transcribe(vadOut)
        val finals = results.filterIsInstance<TranscriptResult.Final>()
        assertThat(finals)
            .describedAs("VAD-processed short word must produce a Final: $results")
            .isNotEmpty
        assertThat(finals.last().text.lowercase()).contains("yes")
    }

    @Test
    fun `short no through the vad is transcribed`() {
        Assume.assumeTrue(modelAvailable)
        val vadOut = vadProcess(wavSamples("single-no.wav"))
        assertThat(vadOut.size)
            .describedAs("VAD must pass the speech through")
            .isGreaterThan(4_000)   // > 0.25 s
        val results = transcribe(vadOut)
        val finals = results.filterIsInstance<TranscriptResult.Final>()
        assertThat(finals)
            .describedAs("VAD-processed 'No' must produce a Final: $results")
            .isNotEmpty
        assertThat(finals.last().text.lowercase()).contains("no")
    }

    @Test
    fun `short silence produces no final`() {
        Assume.assumeTrue(modelAvailable)
        // 1.0 s of digital silence (< 2.5 s short-utterance threshold): both decode
        // contexts must yield blank → no Final, no committed hallucination.
        val results = transcribe(ShortArray(16_000))
        assertThat(results.filterIsInstance<TranscriptResult.Final>())
            .describedAs("silent short input must not produce a Final: $results")
            .isEmpty()
    }

    @Test
    fun `short noise produces no final`() {
        Assume.assumeTrue(modelAvailable)
        // 1.0 s of white noise (same amplitude as the generated noise-only fixture):
        // any decode must fail the plausibility/confidence gate → no Final.
        val rng = java.util.Random(42)
        val noise = ShortArray(16_000) { (rng.nextInt(2001) - 1000).toShort() }
        val results = transcribe(noise)
        assertThat(results.filterIsInstance<TranscriptResult.Final>())
            .describedAs("noisy short input must not produce a Final: $results")
            .isEmpty()
    }

    @Test
    fun `short no-word input surfaces a no-speech cue`() {
        Assume.assumeTrue(modelAvailable)
        // 1.0 s of digital silence: the decoder resolves no word in either context, so the
        // pipeline surfaces a NoSpeech cue (the "didn't catch that" feedback) instead of
        // staying silent or committing a hallucination.
        val results = transcribe(ShortArray(16_000))
        assertThat(results.filterIsInstance<TranscriptResult.NoSpeech>())
            .describedAs("silent short input must surface a NoSpeech cue: $results")
            .isNotEmpty
    }

    /**
     * The user's reported case: a single short word ("Thanks.") at normal speed must be
     * transcribed, both with and without the VAD in front. This is the end-to-end anchor
     * for the short-utterance path.
     */
    @Test
    fun `single thanks is transcribed`() {
        Assume.assumeTrue(modelAvailable)
        val samples = wavSamples("single-thanks.wav")

        val raw = transcribe(samples).filterIsInstance<TranscriptResult.Final>()
        assertThat(raw)
            .describedAs("raw short 'thanks' must produce a Final")
            .isNotEmpty
        assertThat(raw.last().text.lowercase()).contains("thanks")

        val vadOut = vadProcess(samples)
        val viaVad = transcribe(vadOut).filterIsInstance<TranscriptResult.Final>()
        assertThat(viaVad)
            .describedAs("VAD-processed short 'thanks' must produce a Final")
            .isNotEmpty
        assertThat(viaVad.last().text.lowercase()).contains("thanks")
    }

    /**
     * Regression test for the short-utterance confidence gate. The model decodes this
     * "No" correctly (a real word) but its geometric-mean engine confidence is ~0.54 —
     * below the old 0.55 gate. The old code suppressed it as "Low confidence — could not
     * understand" even though the model heard the word; other Parakeet tools emit it.
     * The plausibility floor (>= 2 word chars) must let a correct word through regardless
     * of confidence.
     */
    @Test
    fun `low-confidence correct word is not suppressed`() {
        Assume.assumeTrue(modelAvailable)
        // Reproduces the proven suppression case: "No" decoded at ~0.54 confidence
        // (scale 0.6, 1.1x faster, room noise). The model hears the word; the pipeline
        // must emit it, not a Low-confidence Failure.
        val speech = wavSamples("single-no.wav")
        val variant = addNoise(stretch(scale(speech, 0.6f), 1.1f), seed = 871L, amp = 800)

        val results = transcribe(variant)
        val failures = results.filterIsInstance<TranscriptResult.Failure>()
        assertThat(failures.none { it.cause.message?.contains("Low confidence") == true })
            .describedAs("a correctly-decoded word must not be suppressed as Low confidence: $results")
            .isTrue
        val finals = results.filterIsInstance<TranscriptResult.Final>()
        assertThat(finals)
            .describedAs("the correctly-decoded 'No' must be committed: $results")
            .isNotEmpty
        assertThat(finals.last().text.lowercase()).contains("no")
    }

    /**
     * Full-frame-coverage regression. Feeding "Thank you." directly (no VAD, so no trailing
     * hangover) makes the speech end exactly at the buffer end with no tail padding — the
     * short-utterance flush's final chunk reaches the buffer end. The flush must decode the
     * full encoder frame range (matching the one-shot reference) so the complete word is
     * committed. This pins the frame-end fix: the flush ends its final chunk at the true
     * encoder frame count, not at the position→frame map (which underestimates by 1 for
     * most lengths).
     */
    @Test
    fun `full word survives when speech ends at buffer end`() {
        Assume.assumeTrue(modelAvailable)
        val samples = wavSamples("single-thankyou.wav")
        // 11 517 samples + 9 600 lead silence = 21 117 >= 20 000 → no tail padding, so the
        // speech ends at the buffer end and its tail sits in the final encoder frame.
        assertThat(samples.size + 9_600).isGreaterThanOrEqualTo(20_000)

        val finals = transcribe(samples).filterIsInstance<TranscriptResult.Final>()
        assertThat(finals)
            .describedAs("buffer-end 'thank you' must produce a Final")
            .isNotEmpty
        val text = finals.last().text.lowercase()
        assertThat(text)
            .describedAs("the full word must survive the final frame (no tail truncation)")
            .contains("thank")
            .contains("you")
    }
}

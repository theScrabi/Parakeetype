package org.schabi.parakeetype.inference

import org.schabi.parakeetype.audio.AudioChunk
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.AfterClass
import org.junit.Assume
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.util.Locale

/**
 * Integration tests that feed real WAV audio through the actual Parakeet ONNX model.
 *
 * Each test: load WAV resource → pad to minimum length for short-utterance path → SpeechEngine.transcribe() → assert text.
 *
 * The model is loaded once per test class and shared across tests for speed.
 * Engine is used via SpeechEngine interface. Language is reset after each test to avoid cross-test leakage.
 *
 * **Skipping:** All tests assume the model directory is available. If not, the entire class is
 * skipped gracefully (Assume.isTrue in @BeforeClass).
 *
 * **Robolectric:** Required on classpath to shim `android.util.Log` and `android.os.Debug`
 * calls inside ParakeetEngine during JVM tests. No annotation needed — the dependency alone
 * provides the shims.
 */
class ParakeetEngineRealAudioTest {

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
            assertThat(engine.isLoaded).`as`("Parakeet model should be loaded").isTrue()
            modelAvailable = true
        }

        @AfterClass
        @JvmStatic
        fun closeModel() {
            if (modelAvailable) engine.close()
        }
    }

    @get:Rule
    val globalTimeout = Timeout(2, java.util.concurrent.TimeUnit.MINUTES)

    private val wavReader = WavReader()
    /** 1.25 s — mirrors the production short-utterance path (MIN_PADDING_SAMPLES). */
    private val MIN_PADDING_SAMPLES = 16_000 * 5 / 4

    // Helper: extract text from Final or Partial; fail on other types.
    private fun TranscriptResult.textOrFail(): String = when (this) {
        is TranscriptResult.Final -> this.text
        is TranscriptResult.Partial -> this.text
        is TranscriptResult.Failure -> throw AssertionError("Engine failed: ${this.cause.message}")
        is TranscriptResult.WindowTrimmed -> throw AssertionError("Unexpected WindowTrimmed from direct transcribe()")
        is TranscriptResult.NoSpeech -> throw AssertionError("Unexpected NoSpeech from direct transcribe()")
    }

    /**
     * Feeds the fixture to the engine the way the production short-utterance path does:
     * up to 640 ms of VAD lead-in silence prepended (SileroVadFilter LEAD_IN_FRAMES),
     * then zero-padding to at least [MIN_PADDING_SAMPLES].
     *
     * [leadMs] is 0 for the non-speech rejection tests, which probe raw noise/silence
     * handling without the VAD's pre-roll.
     */
    private fun transcribePadded(stream: java.io.InputStream, leadMs: Int = 600): TranscriptResult {
        val chunk = wavReader.loadAsSingleChunk(stream)
        val lead = ShortArray(leadMs * 16)
        val withLead = lead + chunk.samples
        val samples = if (withLead.size < MIN_PADDING_SAMPLES) {
            withLead.copyOf(MIN_PADDING_SAMPLES)
        } else {
            withLead
        }
        val padded = AudioChunk(samples, sampleRate = chunk.sampleRate, timestampMs = chunk.timestampMs)
        return engine.transcribe(padded)
    }

    /**
     * Opens a WAV fixture from the `audio` resource directory.
     *
     * Uses [ClassLoader.getResourceAsStream] instead of `URL(audioDir, name)`: the directory
     * URL returned by [java.net.URLClassLoader] has no trailing slash, so the two-argument
     * URL constructor resolved the file name against the *parent* directory (dropping the
     * `audio` segment) and every lookup failed with FileNotFoundException.
     */
    private fun openWav(name: String): java.io.InputStream =
        openWavFixture(name)

    @After
    fun resetLanguage() {
        engine.setLanguage("en")
    }

    // ─── Short-utterance tests ──────────────────────────────────────────────

    @Test
    fun `single word yes is recognised with zero-padding`() {
        Assume.assumeTrue(modelAvailable)
        val result = transcribePadded(openWav("single-yes.wav"))

        assertThat(result).isInstanceOfAny(
            TranscriptResult.Final::class.java,
            TranscriptResult.Partial::class.java,
        )
        val text = result.textOrFail()
        assertThat(text.lowercase(Locale.ROOT)).contains("yes")
    }

    @Test
    fun `single thanks on the one-shot path is recognised`() {
        Assume.assumeTrue(modelAvailable)
        val result = transcribePadded(openWav("single-thanks.wav"))
        val text = result.textOrFail()
        assertThat(text.lowercase(Locale.ROOT))
            .describedAs("Single 'thanks' must decode to 'thanks', got: '$text'")
            .contains("thanks")
    }

    @Test
    fun `single word no is recognised`() {
        Assume.assumeTrue(modelAvailable)
        val result = transcribePadded(openWav("single-no.wav"))

        assertThat(result).isInstanceOfAny(
            TranscriptResult.Final::class.java,
            TranscriptResult.Partial::class.java,
        )
        val text = result.textOrFail()
        assertThat(text.lowercase(Locale.ROOT)).contains("no")
    }

    @Test
    fun `hello world short phrase is recognised`() {
        Assume.assumeTrue(modelAvailable)
        val result = transcribePadded(openWav("hello-world.wav"))

        assertThat(result).isInstanceOfAny(
            TranscriptResult.Final::class.java,
            TranscriptResult.Partial::class.java,
        )
        val text = result.textOrFail()
        assertThat(text.lowercase(Locale.ROOT)).contains("hello")
        assertThat(text.lowercase(Locale.ROOT)).contains("world")
    }

    @Test
    fun `two words how are you is recognised`() {
        Assume.assumeTrue(modelAvailable)
        val result = transcribePadded(openWav("two-words.wav"))

        val text = result.textOrFail()
        val lower = text.lowercase(Locale.ROOT)
        assertThat(lower).contains("how")
        assertThat(lower).contains("you")
    }

    // ─── Medium-length utterance ────────────────────────────────────────────

    @Test
    fun `medium sentence recognised with high word overlap`() {
        Assume.assumeTrue(modelAvailable)
        val result = transcribePadded(openWav("medium-sentence.wav"))

        val text = result.textOrFail()
        val lower = text.lowercase(Locale.ROOT)
        assertThat(lower).contains("fox")
        assertThat(lower).contains("brown")
        assertThat(lower).contains("quick")
    }

    // ─── Silence / noise rejection ──────────────────────────────────────────

    @Test
    fun `silence-only audio does not produce coherent hallucination`() {
        Assume.assumeTrue(modelAvailable)
        val result = transcribePadded(openWav("silence-only.wav"), leadMs = 0)

        val text = when (result) {
            is TranscriptResult.Final -> result.text
            is TranscriptResult.Partial -> result.text
            is TranscriptResult.Failure -> ""
            is TranscriptResult.WindowTrimmed -> throw AssertionError("Unexpected WindowTrimmed")
            is TranscriptResult.NoSpeech -> ""
        }

        val words = text.split(Regex("\\s+")).filter { it.any { c -> c.isLetterOrDigit() } }
        assertThat(words)
            .describedAs(
                "Silence should not produce a coherent (multi-word) hallucination (got '%s')".format(text)
            )
            .hasSizeLessThanOrEqualTo(1)
    }

    @Test
    fun `noise-only audio does not produce coherent hallucination`() {
        Assume.assumeTrue(modelAvailable)
        val result = transcribePadded(openWav("noise-only.wav"), leadMs = 0)

        val text = when (result) {
            is TranscriptResult.Final -> result.text
            is TranscriptResult.Partial -> result.text
            is TranscriptResult.Failure -> ""
            is TranscriptResult.WindowTrimmed -> throw AssertionError("Unexpected WindowTrimmed")
            is TranscriptResult.NoSpeech -> ""
        }

        // TDT models can emit a single-word fragment on white noise (observed: ", идете") —
        // that is not a coherent hallucination. Two or more word-bearing tokens would be.
        val words = text.split(Regex("\\s+")).filter { it.any { c -> c.isLetterOrDigit() } }
        assertThat(words)
            .describedAs("Noise should not produce a coherent (multi-word) hallucination (got '%s')".format(text))
            .hasSizeLessThanOrEqualTo(1)
    }

    // ─── Silence-then-speech ────────────────────────────────────────────────

    @Test
    fun `silence then speech yields real words`() {
        Assume.assumeTrue(modelAvailable)
        // The fixture already contains the VAD lead-in silence (0.6 s) before the speech;
        // no extra lead-in is prepended.
        val result = transcribePadded(openWav("silence-then-speech.wav"), leadMs = 0)

        val text = result.textOrFail()
        assertThat(text.lowercase(Locale.ROOT)).contains("hello")
    }

    // ─── Long utterance ─────────────────────────────────────────────────────

    @Test
    fun `long sentence is recognised with key words present`() {
        Assume.assumeTrue(modelAvailable)
        val result = transcribePadded(openWav("long-sentence.wav"))

        val text = result.textOrFail()
        val lower = text.lowercase(Locale.ROOT)
        assertThat(lower).contains("interesting")
        assertThat(lower).contains("morning")
        assertThat(lower).contains("help")
    }

    // ─── Medium/long sentence coverage (Piper neural TTS) ─────────────────
    //
    // Fifteen medium-to-long sentences (2.9–4.0 s) covering everyday dictation
    // content: requests, questions, statements, times and numbers. Each asserts
    // that the distinctive keywords survive the one-shot engine path and that
    // the engine reports high confidence.

    private val sentenceFixtures: List<Triple<String, List<String>, Float>> = listOf(
        Triple("med-groceries", listOf("groceries", "store"), 0.8f),
        Triple("med-report", listOf("report", "tomorrow"), 0.8f),
        Triple("med-meeting", listOf("meeting", "thursday"), 0.8f),
        Triple("long-appointment", listOf("appointment", "doctor", "possible"), 0.8f),
        Triple("long-approach", listOf("approach", "project", "continue"), 0.8f),
        Triple("long-callback", listOf("call", "home"), 0.8f),
        Triple("long-furniture", listOf("furniture", "saturday"), 0.8f),
        Triple("long-package", listOf("package", "damaged"), 0.8f),
        Triple("long-plants", listOf("plants", "trip"), 0.8f),
        Triple("long-pizza", listOf("pizza", "restaurant"), 0.8f),
        Triple("long-reservation", listOf("reservation", "received"), 0.8f),
        Triple("long-slides", listOf("slides", "client"), 0.8f),
        Triple("long-train", listOf("train", "station"), 0.8f),
        Triple("long-weather", listOf("weather", "indoors"), 0.8f),
        Triple("long-wallet", listOf("wallet", "taxi"), 0.8f),
    )

    private fun assertSentenceFixture(name: String, keywords: List<String>, minConfidence: Float) {
        Assume.assumeTrue(modelAvailable)
        val result = transcribePadded(openWav("$name.wav"))

        assertThat(result).isInstanceOfAny(
            TranscriptResult.Final::class.java,
            TranscriptResult.Partial::class.java,
        )
        val text = result.textOrFail()
        val lower = text.lowercase(Locale.ROOT)
        val missing = keywords.filter { lower.contains(it) == false }
        assertThat(missing)
            .describedAs("Keywords missing from transcription of $name: got '$text'")
            .isEmpty()
        val confidence = when (result) {
            is TranscriptResult.Final -> result.confidence
            is TranscriptResult.Partial -> result.confidence
            else -> 0f
        }
        assertThat(confidence)
            .describedAs("Confidence for $name should be ≥ $minConfidence (got $confidence on '$text')")
            .isGreaterThanOrEqualTo(minConfidence)
    }

    @Test
    fun `med groceries sentence is recognised`() =
        assertSentenceFixture("med-groceries", sentenceFixtures[0].second, sentenceFixtures[0].third)

    @Test
    fun `med report sentence is recognised`() =
        assertSentenceFixture("med-report", sentenceFixtures[1].second, sentenceFixtures[1].third)

    @Test
    fun `med meeting sentence is recognised`() =
        assertSentenceFixture("med-meeting", sentenceFixtures[2].second, sentenceFixtures[2].third)

    @Test
    fun `long appointment sentence is recognised`() =
        assertSentenceFixture("long-appointment", sentenceFixtures[3].second, sentenceFixtures[3].third)

    @Test
    fun `long approach sentence is recognised`() =
        assertSentenceFixture("long-approach", sentenceFixtures[4].second, sentenceFixtures[4].third)

    @Test
    fun `long callback sentence is recognised`() =
        assertSentenceFixture("long-callback", sentenceFixtures[5].second, sentenceFixtures[5].third)

    @Test
    fun `long furniture sentence is recognised`() =
        assertSentenceFixture("long-furniture", sentenceFixtures[6].second, sentenceFixtures[6].third)

    @Test
    fun `long package sentence is recognised`() =
        assertSentenceFixture("long-package", sentenceFixtures[7].second, sentenceFixtures[7].third)

    @Test
    fun `long plants sentence is recognised`() =
        assertSentenceFixture("long-plants", sentenceFixtures[8].second, sentenceFixtures[8].third)

    @Test
    fun `long pizza sentence is recognised`() =
        assertSentenceFixture("long-pizza", sentenceFixtures[9].second, sentenceFixtures[9].third)

    @Test
    fun `long reservation sentence is recognised`() =
        assertSentenceFixture("long-reservation", sentenceFixtures[10].second, sentenceFixtures[10].third)

    @Test
    fun `long slides sentence is recognised`() =
        assertSentenceFixture("long-slides", sentenceFixtures[11].second, sentenceFixtures[11].third)

    @Test
    fun `long train sentence is recognised`() =
        assertSentenceFixture("long-train", sentenceFixtures[12].second, sentenceFixtures[12].third)

    @Test
    fun `long weather sentence is recognised`() =
        assertSentenceFixture("long-weather", sentenceFixtures[13].second, sentenceFixtures[13].third)

    @Test
    fun `long wallet sentence is recognised`() =
        assertSentenceFixture("long-wallet", sentenceFixtures[14].second, sentenceFixtures[14].third)

    // ─── Language-specific tests ────────────────────────────────────────────

    @Test
    fun `german phrase with umlauts is recognised`() {
        Assume.assumeTrue(modelAvailable)
        engine.setLanguage("de")
        val result = transcribePadded(openWav("german-hallo.wav"))

        val text = result.textOrFail()
        assertThat(text.lowercase(Locale.ROOT)).contains("hallo")
    }

    // ─── Confidence tests ───────────────────────────────────────────────────

    @Test
    fun `clear speech produces high confidence Final`() {
        Assume.assumeTrue(modelAvailable)
        val result = transcribePadded(openWav("hello-world.wav"))

        assertThat(result).isInstanceOf(TranscriptResult.Final::class.java)
        val confidence = (result as TranscriptResult.Final).confidence
        assertThat(confidence)
            .describedAs("Clear speech confidence should be ≥ 0.3 (got %.3f on '%s')", confidence, result.text)
            .isGreaterThanOrEqualTo(0.3f)
    }

    // ─── Fuzzy matching demo ────────────────────────────────────────────────

    @Test
    fun `medium sentence matches expected with acceptable WER`() {
        Assume.assumeTrue(modelAvailable)
        val result = transcribePadded(openWav("medium-sentence.wav"))

        val actual = result.textOrFail()
        assertTranscriptionClose("The quick brown fox jumps over the lazy dog", actual)
    }
}

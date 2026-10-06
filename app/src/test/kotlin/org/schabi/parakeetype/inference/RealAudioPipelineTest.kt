package org.schabi.parakeetype.inference

import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.schabi.parakeetype.audio.AudioChunk
import org.schabi.parakeetype.ime.FakeInputConnection
import org.schabi.parakeetype.ime.TextInjector
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Assume
import org.junit.BeforeClass
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.Locale

/**
 * Integration tests that feed **real WAV audio through the full streaming pipeline**
 * ([InferenceRepository] + the real Parakeet ONNX engine) on the JVM.
 *
 * The `long-diff-two-sentences` scenario is the regression anchor for the streaming-parity
 * fixes (sentence-final window reset + final-pass re-emission guard): before them,
 * sentence 1 was committed twice and sentence 2 was replaced by a pattern-continuation
 * hallucination. The two sentences must have **distinct** content: a repeated sentence
 * is textually indistinguishable from a plateau (no new content) and the aligner
 * correctly treats it as such.
 *
 * **Skipping:** like [ParakeetEngineRealAudioTest], the whole class is skipped when the
 * model directory is unavailable.
 */
class RealAudioPipelineTest {

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

    private fun wavSamples(name: String): ShortArray {
        val stream = openWavFixture(name)
        val pcm = WavReader().readPcm16(ByteArrayInputStream(stream.readBytes()))
        val resampled = WavReader().resampleLinear(pcm.samples, pcm.sampleRate, 16_000)
        return if (pcm.channels > 1) {
            ShortArray(resampled.size / 2) { i -> ((resampled[2 * i] + resampled[2 * i + 1]) / 2).toShort() }
        } else resampled
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

    private fun textOf(result: TranscriptResult): String = when (result) {
        is TranscriptResult.Final -> result.text
        is TranscriptResult.Partial -> result.text
        else -> throw AssertionError("Expected Final, got $result")
    }

    @Test
    fun `streaming final matches one-shot reference on medium utterance`() {
        Assume.assumeTrue(modelAvailable)
        engine.setLanguage("en")
        val samples = wavSamples("long-sentence.wav")

        val oneShot = engine.transcribe(AudioChunk(samples))
        val reference = when (oneShot) {
            is TranscriptResult.Final -> oneShot.text
            is TranscriptResult.Partial -> oneShot.text
            else -> throw AssertionError("One-shot reference failed: $oneShot")
        }

        val repo = InferenceRepository(engine)
        val results = runBlocking {
            repo.transcribe(flowOf(*chunkify(samples).toTypedArray()), postprocessingEnabled = true)
                .toList()
        }

        // The pipeline must emit at least one partial and end with a Final.
        val finals = results.filterIsInstance<TranscriptResult.Final>()
        org.assertj.core.api.Assertions.assertThat(finals)
            .describedAs("Pipeline must end with a Final result")
            .isNotEmpty
        val finalText = finals.last().text

        // Streaming quality must be on par with the one-shot (non-streaming) reference.
        val errorRate = wer(reference, finalText)
        org.assertj.core.api.Assertions.assertThat(errorRate)
            .describedAs(
                "Streaming WER vs one-shot must be ≤ 0.1 (reference='%s', streaming='%s', wer=%.3f)"
                    .format(reference, finalText, errorRate)
            )
            .isLessThanOrEqualTo(0.1f)
    }

    @Test
    fun `vad boundary flush produces one-shot quality final`() {
        Assume.assumeTrue(modelAvailable)
        engine.setLanguage("en")
        val samples = wavSamples("long-sentence.wav")

        val oneShot = engine.transcribe(AudioChunk(samples))
        val reference = when (oneShot) {
            is TranscriptResult.Final -> oneShot.text
            is TranscriptResult.Partial -> oneShot.text
            else -> throw AssertionError("One-shot reference failed: $oneShot")
        }

        // VAD emits a zero-sample isSilenceBoundary sentinel after sustained silence;
        // the repository flushes the whole window as a Final on it.
        val chunks = chunkify(samples) + AudioChunk(ShortArray(0), isSilenceBoundary = true)
        val repo = InferenceRepository(engine)
        val results = runBlocking {
            repo.transcribe(flowOf(*chunks.toTypedArray()), postprocessingEnabled = true)
                .toList()
        }

        val boundaryFinal = results.filterIsInstance<TranscriptResult.Final>()
            .lastOrNull { it.isUtteranceBoundary }
        org.assertj.core.api.Assertions.assertThat(boundaryFinal)
            .describedAs("Boundary flush must emit a Final with isUtteranceBoundary=true")
            .isNotNull

        val errorRate = wer(reference, boundaryFinal!!.text)
        org.assertj.core.api.Assertions.assertThat(errorRate)
            .describedAs(
                "Boundary final WER vs one-shot must be ≤ 0.1 (reference='%s', boundary='%s', wer=%.3f)"
                    .format(reference, boundaryFinal.text, errorRate)
            )
            .isLessThanOrEqualTo(0.1f)
    }

    /**
     * The streaming-parity regression anchor: ~9 s of speech (two distinct sentences
     * separated by a 0.5 s pause) driven through the full production stack —
     * [InferenceRepository] + real Parakeet + [org.schabi.parakeetype.ime.TextInjector] +
     * in-memory [org.schabi.parakeetype.ime.FakeInputConnection].
     *
     * Before the sentence-final window reset (P1) and the final-pass re-emission guard
     * (P3), long two-sentence recordings committed sentence 1 twice and replaced
     * sentence 2 with a pattern-continuation hallucination. The field content must now
     * match the one-shot (non-streaming) transcription of the same audio.
     */
    @Test
    fun `two sentences stream through the full stack without duplication`() {
        Assume.assumeTrue(modelAvailable)
        engine.setLanguage("en")
        val samples = wavSamples("long-diff-two-sentences.wav")

        // One-shot reference: both sentences, transcribed in a single pass.
        val oneShot = engine.transcribe(AudioChunk(samples))
        val reference = when (oneShot) {
            is TranscriptResult.Final -> oneShot.text
            is TranscriptResult.Partial -> oneShot.text
            else -> throw AssertionError("One-shot reference failed: $oneShot")
        }

        val ic = FakeInputConnection()
        val editorInfo = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL
        }
        val injector = TextInjector(ic, editorInfo)
        val repo = InferenceRepository(engine)

        runBlocking {
            repo.transcribe(flowOf(*chunkify(samples).toTypedArray()), postprocessingEnabled = true)
                .collect { result ->
                    when (result) {
                        is TranscriptResult.Partial -> injector.setPartial(result.text)
                        is TranscriptResult.Final -> injector.commitFinal(result.text)
                        is TranscriptResult.WindowTrimmed -> injector.resetAfterTrim(result.stableWords)
                        else -> {}
                    }
                }
        }

        val fieldText = ic.fieldText.trim()
        val errorRate = wer(reference, fieldText)
        org.assertj.core.api.Assertions.assertThat(errorRate)
            .describedAs(
                "Full-stack streaming field must match the one-shot reference (WER ≤ 0.1). " +
                        "reference='%s', field='%s', wer=%.3f".format(reference, fieldText, errorRate)
            )
            .isLessThanOrEqualTo(0.1f)

        // Both distinct sentences must be present, each exactly once (no duplication).
        val fieldLower = fieldText.lowercase(Locale.ROOT)
        org.assertj.core.api.Assertions.assertThat(fieldLower)
            .describedAs("Both sentences must be present in the field")
            .contains("encoder")
            .contains("decoder")
        org.assertj.core.api.Assertions.assertThat(fieldLower.split("encoder").size - 1)
            .describedAs("'encoder' must appear exactly once (sentence 1 not duplicated)")
            .isEqualTo(1)
        org.assertj.core.api.Assertions.assertThat(fieldLower.split("decoder").size - 1)
            .describedAs("'decoder' must appear exactly once (sentence 2 not duplicated)")
            .isEqualTo(1)
    }

    /**
     * Streaming parity across the new medium/long Piper fixtures: each ~3–4 s sentence
     * is driven through the rolling-window pipeline (partials at 2 s and 3 s strides,
     * final pass over the full window) and must match the one-shot transcription at
     * WER ≤ 0.1.
     */
    @Test
    fun `streaming finals match one-shot on new medium fixtures`() {
        Assume.assumeTrue(modelAvailable)
        engine.setLanguage("en")

        val fixtures = listOf(
            "med-groceries", "med-report", "long-appointment",
            "long-package", "long-weather",
        )

        for (name in fixtures) {
            val samples = wavSamples("$name.wav")

            val oneShot = engine.transcribe(AudioChunk(samples))
            val reference = when (oneShot) {
                is TranscriptResult.Final -> oneShot.text
                is TranscriptResult.Partial -> oneShot.text
                else -> throw AssertionError("One-shot reference failed for $name: $oneShot")
            }

            val repo = InferenceRepository(engine)
            val results = runBlocking {
                repo.transcribe(flowOf(*chunkify(samples).toTypedArray()), postprocessingEnabled = true)
                    .toList()
            }

            val finals = results.filterIsInstance<TranscriptResult.Final>()
            org.assertj.core.api.Assertions.assertThat(finals)
                .describedAs("$name: pipeline must end with a Final result")
                .isNotEmpty
            val finalText = finals.last().text

            val errorRate = wer(reference, finalText)
            org.assertj.core.api.Assertions.assertThat(errorRate)
                .describedAs(
                    "$name: streaming WER vs one-shot must be ≤ 0.1 (reference='%s', streaming='%s', wer=%.3f)"
                        .format(reference, finalText, errorRate)
                )
                .isLessThanOrEqualTo(0.1f)
        }
    }

    /**
     * Regression anchor for the alignment-desync bug: a ~11 s four-sentence dictation
     * whose second sentence ("…apples are oranges in a sense.") the model transcribes
     * unstably across strides ("and a sense" / "and a scent" / "and essence").  The
     * diverging-and-reconverging partials desynced the suffix-overlap tracker so that
     * "My cousin prefers grapes." was tracked in BOTH committedWords and composingWords
     * at once and then dropped from the field (text loss — the sibling of the sentence
     * duplication reported in the field).
     *
     * Every sentence's distinctive words must survive the full stack, and no phrase may
     * be committed twice.
     */
    @Test
    fun `four sentence dictation with unstable phrase keeps all words without duplication`() {
        Assume.assumeTrue(modelAvailable)
        engine.setLanguage("en")
        val samples = wavSamples("multi-4sent.wav")

        val ic = FakeInputConnection()
        val editorInfo = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL
        }
        val injector = TextInjector(ic, editorInfo)
        val repo = InferenceRepository(engine)

        runBlocking {
            repo.transcribe(flowOf(*chunkify(samples).toTypedArray()), postprocessingEnabled = true)
                .collect { result ->
                    when (result) {
                        is TranscriptResult.Partial -> injector.setPartial(result.text)
                        is TranscriptResult.Final -> injector.commitFinal(result.text)
                        is TranscriptResult.WindowTrimmed -> injector.resetAfterTrim(result.stableWords)
                        else -> {}
                    }
                }
        }

        val field = ic.fieldText.lowercase(Locale.ROOT)

        // Every sentence's distinctive words must be present (the desync dropped
        // "My cousin prefers grapes" — cousin/grapes are the load-bearing assertions).
        for (word in listOf("family", "apples", "cousin", "grapes", "tomatoes", "anyway")) {
            org.assertj.core.api.Assertions.assertThat(field)
                .describedAs("Word '$word' must survive the full stack (field='$field')")
                .contains(word)
        }

        // No phrase of >= 3 words may be committed twice (the reported duplication).
        val words = field.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            .map { it.trim { !it.isLetterOrDigit() } }
        var duplicated = ""
        outer@
        for (len in words.size - 1 downTo 3) {
            for (i in 0..words.size - len) {
                val phrase = words.subList(i, i + len)
                for (j in i + len..words.size - len) {
                    if (words.subList(j, j + len) == phrase) {
                        duplicated = phrase.joinToString(" ")
                        break@outer
                    }
                }
            }
        }
        org.assertj.core.api.Assertions.assertThat(duplicated)
            .describedAs("No phrase may be committed twice (field='$field')")
            .isEmpty()
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Full-pipeline no-loss / no-duplication battery.
    //
    // Each test drives a multi-sentence dictation through the complete production
    // stack ([InferenceRepository] + real Parakeet + [TextInjector] +
    // [FakeInputConnection]) and asserts two invariants:
    //   1. No loss  - every sentence's distinctive words survive in the field.
    //   2. No dup   - no phrase of >= 3 words is committed twice.
    //
    // These are the two failure modes of the alignment-desync bug (the model
    // transcribes some phrases unstably across strides, desyncing the
    // committed/composing trackers). The fixtures are concatenations of the known
    // single-sentence Piper recordings with 0.5 s pauses, so their expected content
    // is exactly known.
    // ────────────────────────────────────────────────────────────────────────────

    /** Drives [fixture] through the full streaming stack and returns the final field text (lowercased). */
    private fun driveFullPipeline(fixture: String): String {
        engine.setLanguage("en")
        val samples = wavSamples(fixture)
        val ic = FakeInputConnection()
        val editorInfo = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL
        }
        val injector = TextInjector(ic, editorInfo)
        val repo = InferenceRepository(engine)
        runBlocking {
            repo.transcribe(flowOf(*chunkify(samples).toTypedArray()), postprocessingEnabled = true)
                .collect { result ->
                    when (result) {
                        is TranscriptResult.Partial -> injector.setPartial(result.text)
                        is TranscriptResult.Final -> injector.commitFinal(result.text)
                        is TranscriptResult.WindowTrimmed -> injector.resetAfterTrim(result.stableWords)
                        else -> {}
                    }
                }
        }
        return ic.fieldText.lowercase(Locale.ROOT)
    }

    /** Returns the first phrase of >= 3 words that appears twice in [field], or "" if none. */
    private fun findDuplicatedPhrase(field: String): String {
        val words = field.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            .map { it.trim { !it.isLetterOrDigit() } }
        var duplicated = ""
        outer@
        for (len in words.size - 1 downTo 3) {
            for (i in 0..words.size - len) {
                val phrase = words.subList(i, i + len)
                for (j in i + len..words.size - len) {
                    if (words.subList(j, j + len) == phrase) {
                        duplicated = phrase.joinToString(" ")
                        break@outer
                    }
                }
            }
        }
        return duplicated
    }

    /** Asserts every word in [words] is present in [field] (no-loss invariant). */
    private fun assertWordsPresent(field: String, words: List<String>) {
        for (word in words) {
            org.assertj.core.api.Assertions.assertThat(field)
                .describedAs("Word '$word' must survive the full stack (field='$field')")
                .contains(word)
        }
    }

    /** Asserts no phrase of >= 3 words is committed twice in [field] (no-dup invariant). */
    private fun assertNoDuplication(field: String) {
        org.assertj.core.api.Assertions.assertThat(findDuplicatedPhrase(field))
            .describedAs("No phrase may be committed twice (field='$field')")
            .isEmpty()
    }

    @Test
    fun `pipe-a three sentence report approach package keeps all words without duplication`() {
        Assume.assumeTrue(modelAvailable)
        val field = driveFullPipeline("pipe-a.wav")
        assertWordsPresent(field, listOf("report", "tomorrow", "reconsider", "approach", "project", "package", "damaged"))
        assertNoDuplication(field)
    }

    @Test
    fun `pipe-k four sentence weather furniture pizza train keeps all words without duplication`() {
        Assume.assumeTrue(modelAvailable)
        val field = driveFullPipeline("pipe-k.wav")
        assertWordsPresent(field, listOf("weather", "forecast", "rain", "indoors", "furniture", "saturday", "restaurant", "pizza", "city", "train", "station"))
        assertNoDuplication(field)
    }

    @Test
    fun `pipe-c three sentence appointment reservation slides keeps all words without duplication`() {
        Assume.assumeTrue(modelAvailable)
        val field = driveFullPipeline("pipe-c.wav")
        assertWordsPresent(field, listOf("appointment", "doctor", "reservation", "received", "presentation", "slides", "client"))
        assertNoDuplication(field)
    }

    @Test
    fun `pipe-l four sentence report appointment weather furniture keeps all words without duplication`() {
        Assume.assumeTrue(modelAvailable)
        val field = driveFullPipeline("pipe-l.wav")
        assertWordsPresent(field, listOf("report", "tomorrow", "appointment", "doctor", "weather", "rain", "indoors", "furniture", "saturday"))
        assertNoDuplication(field)
    }

    @Test
    fun `pipe-e five sentence approach plants package weather wallet keeps all words without duplication`() {
        Assume.assumeTrue(modelAvailable)
        val field = driveFullPipeline("pipe-e.wav")
        assertWordsPresent(field, listOf("approach", "project", "water", "plants", "business", "package", "shipping", "weather", "rain", "indoors", "wallet", "taxi"))
        assertNoDuplication(field)
    }

    @Test
    fun `pipe-f two sentence pangram and wondering keeps all words without duplication`() {
        Assume.assumeTrue(modelAvailable)
        val field = driveFullPipeline("pipe-f.wav")
        assertWordsPresent(field, listOf("quick", "brown", "fox", "lazy", "dog", "wondering", "interesting", "morning"))
        assertNoDuplication(field)
    }

    @Test
    fun `pipe-g four sentence train pizza appointment reservation keeps all words without duplication`() {
        Assume.assumeTrue(modelAvailable)
        val field = driveFullPipeline("pipe-g.wav")
        assertWordsPresent(field, listOf("train", "station", "restaurant", "pizza", "city", "appointment", "doctor", "reservation", "received"))
        assertNoDuplication(field)
    }

    @Test
    fun `pipe-n four sentence meeting furniture package slides keeps all words without duplication`() {
        Assume.assumeTrue(modelAvailable)
        val field = driveFullPipeline("pipe-n.wav")
        assertWordsPresent(field, listOf("meeting", "thursday", "furniture", "saturday", "package", "shipping", "slides", "client"))
        assertNoDuplication(field)
    }

    @Test
    fun `pipe-o four sentence weather furniture appointment pizza keeps all words without duplication`() {
        Assume.assumeTrue(modelAvailable)
        val field = driveFullPipeline("pipe-o.wav")
        assertWordsPresent(field, listOf("weather", "rain", "indoors", "furniture", "saturday", "appointment", "doctor", "restaurant", "pizza", "city"))
        assertNoDuplication(field)
    }

    @Test
    fun `pipe-j three sentence weather wallet pizza keeps all words without duplication`() {
        Assume.assumeTrue(modelAvailable)
        val field = driveFullPipeline("pipe-j.wav")
        assertWordsPresent(field, listOf("weather", "forecast", "rain", "indoors", "wallet", "taxi", "restaurant", "pizza", "city"))
        assertNoDuplication(field)
    }
}

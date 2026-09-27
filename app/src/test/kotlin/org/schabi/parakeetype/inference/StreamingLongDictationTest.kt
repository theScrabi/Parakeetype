package org.schabi.parakeetype.inference

import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.schabi.parakeetype.audio.AudioChunk
import org.schabi.parakeetype.ime.FakeInputConnection
import org.schabi.parakeetype.ime.TextInjector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

/**
 * Integration tests for the chunked-TDT streaming path (the production path for
 * [ParakeetEngine]) that reproduce the two field reports:
 *
 *  1. "After 30-45 s of dictation nothing is transcribed anymore - the button just
 *     keeps spinning and the last sentence stays there."
 *  2. "Speaking fast (10-20 sentences in 30 s) drops ~60% of the content - only
 *     every fewth sentence makes it into the field."
 *
 * Both share one root cause: the per-chunk processing cost of the streaming path
 * exceeds the 2 s real-time audio budget, and the cost grew super-linearly (cubic,
 * via [collapseRepeatedPhrases]) with the utterance length because [InferenceRepository]
 * re-runs the full [cleanTranscript] pipeline over the ENTIRE accumulated utterance on
 * every chunk. Once cumulative per-chunk cost (ONNX decode + re-cleaning) exceeds the
 * chunk's audio time, [InferenceRepository] enters a permanent catch-up: partials
 * arrive only in bursts and the field freezes on the last partial - "spinning, last
 * sentence stays there". Fast speakers hit the same wall sooner (more tokens per
 * chunk, 8x the cubic cleaning cost for the same wall-clock time), so within a 30 s
 * window text is visible only in bursts - "sentences 1, 2, 7, 14".
 *
 * [FakeStreamingParakeet] drives the REAL repository streaming state machine
 * (buffer management, chunk cadence, TDT state carry-over, flush) with a
 * frame-accurate scripted token source, so these tests exercise the production
 * control flow without ONNX.
 */
class StreamingLongDictationTest {

    private fun textEditorInfo(): EditorInfo = EditorInfo().apply {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL
    }

    /** A [words]-word transcript of distinct, cleaning-neutral words (`word1 word2 ...`). */
    private fun transcript(words: Int): String = (1..words).joinToString(" ") { "word$it" }

    /** Median of [runs] timing runs of [block] (µs) - trims GC / scheduler noise. */
    private fun timedMicros(runs: Int, block: () -> Unit): Long {
        // Warmup: class loading, regex compilation, JIT.
        block()
        val samples = LongArray(runs)
        for (i in 0 until runs) {
            val start = System.nanoTime()
            block()
            samples[i] = (System.nanoTime() - start) / 1000
        }
        samples.sort()
        return samples[runs / 2]
    }

    /**
     * ISSUE 1 root-cause test: [cleanTranscript] - re-run over the whole utterance on
     * every chunk by the streaming path - must scale linearly (or better) with the
     * transcript length. Cubic growth (the pre-fix [collapseRepeatedPhrases] behaviour)
     * makes per-chunk cost exceed the 2 s real-time budget within ~30-90 s of
     * continuous dictation on device, freezing the field on the last partial.
     *
     * 800 words is a ~5 min continuous dictation at a normal speaking rate (2.5 w/s);
     * 100 words is ~40 s. A linear implementation differs by ~8x plus noise; the
     * cubic one differs by ~500x.
     */
    @Test
    fun `cleanTranscript scales linearly with transcript length`() {
        val small = transcript(100)
        val large = transcript(800)

        val tSmall = timedMicros(runs = 7) { small.cleanTranscript() }
        val tLarge = timedMicros(runs = 5) { large.cleanTranscript() }

        // Sanity: the large transcript must actually cost more (guards against a
        // measurement that trivially passes by timing nothing).
        assertThat(tLarge).isGreaterThan(tSmall)

        // Linear + noise allows ~16x for an 8x input; the cubic implementation is ~500x.
        // AssertJ's default failure message shows the measured ratio.
        assertThat(tLarge.toDouble() / tSmall.toDouble()).isLessThanOrEqualTo(16.0)
    }

    /**
     * ISSUE 1 end-to-end test: a 45 s continuous dictation (normal speaking rate,
     * no utterance boundary) driven through the real streaming repository with a
     * zero-cost fake engine. The total wall time is the sum of per-chunk non-ONNX
     * processing (buffer handling + full re-cleaning of the growing utterance).
     *
     * Pre-fix this takes 5-25 s on the JVM (cubic re-cleaning), i.e. the per-chunk
     * cost clearly breaks the 2 s real-time budget on any real device well before
     * 45 s - the "nothing transcribes after 30-45 s" symptom. Post-fix the whole
     * 45 s session must process in well under 2 s.
     */
    @Test
    fun `long continuous dictation - per-chunk processing stays bounded`() = runTest {
        val words = 112   // 2.5 w/s for 45 s
        val script = buildWordScript(words, 400)
        val engine = FakeStreamingParakeet(script)
        val repo = InferenceRepository(engine)

        val totalSamples = 45 * 16_000
        val audio = flow { repeat(totalSamples / 160) { emit(AudioChunk(ShortArray(160))) } }

        val start = System.nanoTime()
        val results = repo.transcribe(audio).toList()
        val elapsedMs = (System.nanoTime() - start) / 1_000_000

        // Every spoken word must be present in the final, in order.
        val final = results.filterIsInstance<TranscriptResult.Final>().last()
        val fieldWords = final.text.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), "")
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
        val expected = (1..words).map { "w%04d".format(it).lowercase() }
        assertThat(fieldWords).containsExactlyElementsOf(expected)

        // The whole session's non-ONNX processing must be a fraction of the 45 s of
        // audio - on device the ONNX decode is the dominant cost, so the JVM total
        // here is an upper bound on the remaining headroom.
        assertThat(elapsedMs).isLessThanOrEqualTo(2000)
    }

    /**
     * ISSUE 2 end-to-end test: fast speech (150 words in 30 s, 5 w/s) through the
     * FULL production stack - streaming repository + [TextInjector] +
     * [FakeInputConnection] - with a 100 ms simulated per-chunk decode cost (a fast
     * device; the pre-fix non-ONNX cost on top is what pushed the pipeline into
     * permanent catch-up for fast speakers).
     *
     * The field after the final must contain every spoken word, in order: no
     * sentence may be silently dropped or reordered.
     */
    @Test
    fun `fast speech 30s - every word lands in the field in order`() = runTest {
        val words = 150   // 5 w/s for 30 s
        val script = buildWordScript(words, 200)
        val engine = FakeStreamingParakeet(script, perChunkDecodeMs = 100)
        val repo = InferenceRepository(engine)

        val ic = FakeInputConnection()
        val injector = TextInjector(ic, textEditorInfo())

        val totalSamples = 30 * 16_000
        val audio = flow { repeat(totalSamples / 160) { emit(AudioChunk(ShortArray(160))) } }

        repo.transcribe(audio).collect { result ->
            when (result) {
                is TranscriptResult.Partial -> injector.setPartial(result.text)
                is TranscriptResult.Final -> injector.commitFinal(result.text)
                else -> Unit
            }
        }

        val fieldWords = ic.fieldText.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), "")
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
        val expected = (1..words).map { "w%04d".format(it).lowercase() }
        assertThat(fieldWords).containsExactlyElementsOf(expected)
    }
}

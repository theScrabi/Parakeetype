package org.schabi.parakeetype.inference

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

/**
 * Word timings come from the decoder's raw tokens, but clients get the post-processed text
 * (fillers removed, numbers as digits, capitalisation): the timed raw words are aligned to the
 * words of that text.
 */
class WordTimingsTest {

    private fun word(text: String, startMs: Long, confidence: Float = 0.9f) = WordTiming(text, startMs, confidence)

    @Test
    fun `given unchanged text, when aligning, then every word keeps its timing`() {
        val raw = listOf(word("Hello", 100), word("world.", 600, 0.4f))

        assertThat(alignWordTimings("Hello world.", raw)).isEqualTo(raw)
    }

    @Test
    fun `given a removed filler, when aligning, then the remaining words keep their timings`() {
        val raw = listOf(word("um", 0), word("so", 300), word("uh", 500), word("we", 800), word("go", 1_000))

        assertThat(alignWordTimings("So we go", raw))
            .containsExactly(word("So", 300), word("we", 800), word("go", 1_000))
    }

    @Test
    fun `given number words turned into digits, when aligning, then the number starts at its first word`() {
        val raw = listOf(
            word("in", 0), word("twenty", 200, 0.8f), word("twenty", 500), word("five.", 800), word("Yes", 1_500),
        )

        assertThat(alignWordTimings("in 2025. Yes", raw))
            .containsExactly(word("in", 0), word("2025.", 200, 0.8f), word("Yes", 1_500))
    }

    @Test
    fun `given a word without a raw counterpart, when aligning, then it starts with the next word`() {
        val raw = listOf(word("one", 0), word("two", 400))

        assertThat(alignWordTimings("one, and two", raw))
            .containsExactly(word("one,", 0), word("and", 400), word("two", 400))
    }

    @Test
    fun `given no timed words, when aligning, then there are none`() {
        assertThat(alignWordTimings("Hello", emptyList())).isEmpty()
        assertThat(alignWordTimings("  ", listOf(word("Hello", 0)))).isEmpty()
    }

    @Test
    fun `given audio with a dropped silence, when mapping positions, then they skip the gap`() {
        val positions = CapturePositions()
        positions.onAudio(position = 0, timestampMs = 1_000)
        // The VAD dropped 3 s of silence: the next chunk was captured at 5 s.
        positions.onAudio(position = 16_000, timestampMs = 5_000)
        positions.onAudio(position = 24_000, timestampMs = 5_500)

        assertThat(positions.msAt(0)).isEqualTo(1_000)
        assertThat(positions.msAt(8_000)).isEqualTo(1_500)
        assertThat(positions.msAt(16_000)).isEqualTo(5_000)
        assertThat(positions.msAt(24_000 + 1_600)).isEqualTo(5_600)
        // Padding past the end and lead silence before the start.
        assertThat(positions.msAt(-9_600)).isEqualTo(400)
    }

    @Test
    fun `given no audio, when mapping positions, then they count from zero`() {
        assertThat(CapturePositions().msAt(16_000)).isEqualTo(1_000)
    }

    @Test
    fun `given SentencePiece tokens, when grouping them into words, then punctuation and digits join their word`() {
        val vocabulary = arrayOf("▁in", "▁", "2", "0", ".", "▁Yes", "<unk>", "ter", "<blk>")
        // "▁in ▁ 2 0 . ▁Yes ter <unk>"
        val tokens = listOf(0, 1, 2, 3, 4, 5, 7, 6)

        assertThat(sentencePieceWordRanges(tokens, vocabulary, blankId = 8))
            .containsExactly(0..0, 1..4, 5..7)
    }
}

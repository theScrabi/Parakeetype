package org.schabi.parakeetype.inference

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

/**
 * Unit tests for [detokenizeSentencePiece] with a vocabulary shaped like Parakeet's
 * `tokens.txt`: digits are bare single-character tokens and a number that starts a word
 * is preceded by the lone `▁` marker token.
 */
class DetokenizeTest {

    private val vocab = arrayOf(
        "<unk>", "<|nospeech|>", "<pad>", "<|0|>", // 0-3: control tokens
        "0", "1", "2", "3", "4", "5", "6", "7", "8", "9", // 4-13: digits
        "▁", "▁In", "▁we", "▁sold", "▁units", ".", ",", "▁at", "▁o", "'", "clock", // 14-24
        "<blk>", // 25
    )
    private val blank = 25

    private fun decode(vararg tokens: String): String =
        detokenizeSentencePiece(tokens.map { vocab.indexOf(it) }, vocab, blank)

    @Test
    fun `digits after the bare word marker form a separate number word`() {
        assertThat(decode("▁In", "▁", "2", "0", "2", "5", "▁we", "▁sold", "▁", "3", "▁units", "."))
            .isEqualTo("In 2025 we sold 3 units.")
    }

    @Test
    fun `decimal and thousands separators stay inside the number`() {
        assertThat(decode("▁sold", "▁", "3", ".", "5", "▁units", ",", "▁", "1", ",", "0", "0", "0"))
            .isEqualTo("sold 3.5 units, 1,000")
    }

    @Test
    fun `number at the start of the transcript`() {
        assertThat(decode("▁", "4", "2", "▁units")).isEqualTo("42 units")
    }

    @Test
    fun `blank and control tokens are skipped`() {
        val ids = listOf(0, 15, 1, 25, 14, 3, 7, 2, 18)
        assertThat(detokenizeSentencePiece(ids, vocab, blank)).isEqualTo("In 3 units")
    }

    @Test
    fun `digits survive the post-processing pipeline`() {
        val raw = decode("▁In", "▁", "2", "0", "2", "5", "▁we", "▁sold", "▁", "3", ".", "5", "▁units", ".")
        assertThat(raw.cleanTranscript()).isEqualTo("In 2025 we sold 3.5 units.")
    }

    @Test
    fun `a decimal point does not start a new sentence`() {
        assertThat("it costs 3.5 euros. then more".applySentenceCapitalization())
            .isEqualTo("It costs 3.5 euros. Then more")
    }
}

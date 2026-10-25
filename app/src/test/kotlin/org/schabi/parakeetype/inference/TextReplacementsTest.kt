package org.schabi.parakeetype.inference

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class TextReplacementsTest {

    private fun replacements(vararg rules: Pair<String, String>) =
        TextReplacements(rules.map { (from, to) -> TextReplacement(from, to) })

    @Test
    fun `replaces whole words ignoring case`() {
        val r = replacements("handy" to "Smartphone")
        assertThat(r.apply("Mein Handy ist leer, handy!")).isEqualTo("Mein Smartphone ist leer, Smartphone!")
    }

    @Test
    fun `does not replace inside other words`() {
        val r = replacements("cat" to "dog")
        assertThat(r.apply("concatenate the cat, cats")).isEqualTo("concatenate the dog, cats")
    }

    @Test
    fun `matches umlauts case-insensitively`() {
        val r = replacements("ÖBB" to "Bahn")
        assertThat(r.apply("mit der öbb fahren")).isEqualTo("mit der Bahn fahren")
    }

    @Test
    fun `phrases match across any whitespace`() {
        val r = replacements("parakeet  type" to "Parakeetype")
        assertThat(r.apply("I use parakeet\ttype daily")).isEqualTo("I use Parakeetype daily")
    }

    @Test
    fun `longest phrase wins and output is not replaced again`() {
        val r = replacements("new" to "old", "new york" to "NYC", "old" to "ancient")
        assertThat(r.apply("new york is new")).isEqualTo("NYC is old")
    }

    @Test
    fun `replacement containing its own phrase is applied once`() {
        val r = replacements("Christian" to "Christian Schabesberger")
        assertThat(r.apply("Hallo Christian.")).isEqualTo("Hallo Christian Schabesberger.")
    }

    @Test
    fun `punctuation replacement swallows the space before it`() {
        val r = replacements("Komma" to ",", "Punkt" to ".")
        assertThat(r.apply("Hallo Komma wie geht es Punkt")).isEqualTo("Hallo, wie geht es.")
    }

    @Test
    fun `empty replacement removes the words`() {
        val r = replacements("sozusagen" to "")
        assertThat(r.apply("das ist sozusagen gut")).isEqualTo("das ist gut")
    }

    @Test
    fun `special regex characters are literal`() {
        val r = replacements("c++" to "C plus plus", "a.b" to "x")
        assertThat(r.apply("I like c++ and aXb and a.b")).isEqualTo("I like C plus plus and aXb and x")
    }

    @Test
    fun `first rule for a phrase wins and blank rules are ignored`() {
        val r = replacements("foo" to "first", "FOO" to "second", "  " to "nothing")
        assertThat(r.apply("foo bar")).isEqualTo("first bar")
    }

    @Test
    fun `no rules leave the text unchanged`() {
        assertThat(TextReplacements.NONE.isEmpty).isTrue()
        assertThat(TextReplacements.NONE.apply("  keep  this ")).isEqualTo("  keep  this ")
    }

    @Test
    fun `encode and decode round-trip`() {
        val rules = listOf(
            TextReplacement("parakeet type", "Parakeetype"),
            TextReplacement("weg", ""),
            TextReplacement("tab\there", "line\nbreak"),
        )
        assertThat(TextReplacements.decode(TextReplacements.encode(rules))).containsExactly(
            TextReplacement("parakeet type", "Parakeetype"),
            TextReplacement("weg", ""),
            TextReplacement("tab here", "line break"),
        )
        assertThat(TextReplacements.decode("")).isEmpty()
    }
}

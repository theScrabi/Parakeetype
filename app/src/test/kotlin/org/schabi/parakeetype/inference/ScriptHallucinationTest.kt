package org.schabi.parakeetype.inference

import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

/**
 * Unit tests for [isScriptHallucination].
 *
 * Covers the five required cases:
 *  1. Pure Latin text → passes (returns false)
 *  2. Pure Chinese text → fails (returns true)
 *  3. Mixed 10% non-Latin → passes (below 20% threshold)
 *  4. Mixed 25% non-Latin → fails (above 20% threshold)
 *  5. Empty string → passes (returns false)
 *
 * The check is script-consistency based, not language-setting based:
 * coherent Cyrillic and Greek text passes (it is a real transcript from a
 * Parakeet-supported language), while CJK, Arabic, and symbol noise fails.
 *
 * Additional edge-case tests validate punctuation neutrality, digits, German umlauts,
 * and the exact boundary behaviour around the 20% threshold.
 */
class ScriptHallucinationTest {

    // ── Required cases ────────────────────────────────────────────────────────

    @Test
    fun `pure Latin text passes`() {
        val text = "Hello world, this is a normal English sentence."
        assertThat(isScriptHallucination(text)).isFalse()
    }

    @Test
    fun `pure Chinese text fails`() {
        val text = "你好世界这是中文文本"
        assertThat(isScriptHallucination(text)).isTrue()
    }

    @Test
    fun `mixed 10 percent non-Latin passes`() {
        // 9 Latin letters + 1 Chinese character = 10% non-Latin → below threshold
        val text = "abcdefghi你"  // 1 out of 10 non-whitespace chars is Chinese = 10%
        assertThat(isScriptHallucination(text)).isFalse()
    }

    @Test
    fun `mixed 25 percent non-Latin fails`() {
        // 3 Latin letters + 1 Chinese character = 25% non-Latin → above threshold
        val text = "abc你"  // 1 out of 4 = 25%
        assertThat(isScriptHallucination(text)).isTrue()
    }

    @Test
    fun `empty string passes`() {
        assertThat(isScriptHallucination("")).isFalse()
    }

    // ── Additional edge cases ─────────────────────────────────────────────────

    @Test
    fun `whitespace-only string passes`() {
        assertThat(isScriptHallucination("   \t\n  ")).isFalse()
    }

    @Test
    fun `German umlauts are within Latin range and pass`() {
        val text = "Das ist sehr schön, wirklich wunderbar!"
        assertThat(isScriptHallucination(text)).isFalse()
    }

    @Test
    fun `Latin Extended-B character passes`() {
        // U+024F (ɏ) is the last code point of Latin Extended-B - must be allowed
        val text = "lati\u024F"
        assertThat(isScriptHallucination(text)).isFalse()
    }

    @Test
    fun `Arabic text fails`() {
        val text = "مرحبا بالعالم"
        assertThat(isScriptHallucination(text)).isTrue()
    }

    @Test
    fun `Cyrillic text passes - it is a real Parakeet-supported script`() {
        val text = "Привет мир"
        assertThat(isScriptHallucination(text)).isFalse()
    }

    @Test
    fun `Greek text passes - it is a real Parakeet-supported script`() {
        val text = "Γεια σου κόσμε"
        assertThat(isScriptHallucination(text)).isFalse()
    }

    @Test
    fun `punctuation and digits are neutral and do not count toward total`() {
        // All characters are punctuation or digits - total == 0 → no hallucination
        val text = "1234 5678 !@#$%^&*()"
        assertThat(isScriptHallucination(text)).isFalse()
    }

    @Test
    fun `exactly 20 percent non-Latin does NOT fail (threshold is strictly greater than)`() {
        // 4 Latin + 1 Chinese = exactly 20% → should pass (threshold is > 0.20, not >=)
        val text = "abcd\u4E2D"  // 1 Chinese out of 5 total = exactly 20%
        assertThat(isScriptHallucination(text)).isFalse()
    }

    @Test
    fun `single non-Latin character in short string fails when fraction exceeds threshold`() {
        // "a你" → 1 out of 2 = 50% non-Latin
        assertThat(isScriptHallucination("a你")).isTrue()
    }

    @Test
    fun `allowed typographic punctuation does not skew ratio`() {
        // Euro, German quotes etc. should be neutral
        // \u201E = „ (double low-9 quotation mark), \u201C = “ (left double quotation mark)
        val text = "Preis: \u20AC100 \u201Egut\u201C \u00ABsehr gut\u00BB"
        assertThat(isScriptHallucination(text)).isFalse()
    }
}

/**
 * Unit tests for [stripScriptHallucinations] — the soft counterpart to
 * [isScriptHallucination] introduced for E3.
 */
class StripScriptHallucinationTest {

    @Test
    fun `pure Latin text is returned unchanged`() {
        assertThat(stripScriptHallucinations("Hello world")).isEqualTo("Hello world")
    }

    @Test
    fun `Cyrillic and Greek are preserved`() {
        assertThat(stripScriptHallucinations("Привет Γεια"))?.isEqualTo("Привет Γεια")
    }

    @Test
    fun `single CJK artefact is stripped and Latin words preserved`() {
        // Cold-first-stride pattern: one spurious CJK token alongside the real words.
        val text = "你 Hello world"
        assertThat(stripScriptHallucinations(text)).isEqualTo("Hello world")
    }

    @Test
    fun `embedded symbol between words is dropped and surrounding words kept`() {
        val text = "hello ★ world"
        // The ★ is removed; the two surrounding spaces collapse to a double space (not
        // re-normalised here — that is the job of the downstream cleaning pipeline).
        assertThat(stripScriptHallucinations(text)).isEqualTo("hello  world")
    }

    @Test
    fun `entirely non-script text returns null`() {
        assertThat(stripScriptHallucinations("你世界")).isNull()
        assertThat(stripScriptHallucinations("★☆♫")).isNull()
    }

    @Test
    fun `blank or empty input returns null`() {
        assertThat(stripScriptHallucinations("")).isNull()
        assertThat(stripScriptHallucinations("   ")).isNull()
    }

    @Test
    fun `punctuation and whitespace are preserved when letters remain`() {
        val text = "Hello, world!"
        assertThat(stripScriptHallucinations(text)).isEqualTo("Hello, world!")
    }

    @Test
    fun `punctuation-only text with no letters returns null`() {
        assertThat(stripScriptHallucinations("!@# .,?")).isNull()
    }

    @Test
    fun `German umlauts are preserved`() {
        val text = "Das ist schön"
        assertThat(stripScriptHallucinations(text)).isEqualTo("Das ist schön")
    }
}

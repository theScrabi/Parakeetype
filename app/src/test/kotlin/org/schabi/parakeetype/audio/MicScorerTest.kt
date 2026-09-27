package org.schabi.parakeetype.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * Verifies the pure-Kotlin [MicScorer] ranking logic on the JVM: a mic that preserves
 * high-frequency fricative energy must out-rank one that doesn't — cleaner capture
 * (more HF energy, higher SNR) is what the calibration is trying to find.
 */
class MicScorerTest {

    private fun tone(freqHz: Double, seconds: Double, sr: Int = 16_000, amp: Short = 12000): ShortArray {
        val n = (seconds * sr).toInt()
        return ShortArray(n) { i -> (amp * sin(2.0 * PI * freqHz * i / sr)).toInt().toShort() }
    }

    /** A "Thanks"-like clip: a voiced low band plus a strong high-frequency fricative burst. */
    private fun fricativeClip(seconds: Double, sr: Int = 16_000): ShortArray {
        val n = (seconds * sr).toInt()
        return ShortArray(n) { i ->
            val t = i.toDouble() / sr
            // Low-frequency "voicing" (200 Hz) + high-frequency fricative (6 kHz), gated to the
            // last third (the /ks/ burst) so the HF is a real burst, not continuous noise.
            val voiced = 8000.0 * sin(2.0 * PI * 200.0 * t)
            val gate = if (t > seconds * 2.0 / 3.0) 1.0 else 0.0
            val fric = gate * 6000.0 * sin(2.0 * PI * 6000.0 * t)
            (voiced + fric).toInt().coerceIn(-32768, 32767).toShort()
        }
    }

    @Test
    fun silenceScoresZero() {
        val score = MicScorer.score(ShortArray(16_000))
        assertEquals(0f, score.score)
        assertEquals(0f, score.hfFraction)
    }

    @Test
    fun shortClipScoresZero() {
        val short = tone(6000.0, 0.1)
        assertEquals(0f, MicScorer.score(short).score)
    }

    @Test
    fun highFrequencyClipScoresHigherThanLowFrequencyClip() {
        // Same amplitude, same duration — only the frequency content differs.
        val hf = MicScorer.score(fricativeClip(1.0))
        val low = MicScorer.score(tone(200.0, 1.0, amp = 12000))

        assertTrue(
            "HF clip hfFraction=${hf.hfFraction} should exceed low clip ${low.hfFraction}",
            hf.hfFraction > low.hfFraction,
        )
        assertTrue(
            "HF clip score=${hf.score} should exceed low clip ${low.score}",
            hf.score > low.score,
        )
    }

    @Test
    fun hfFractionIsBounded() {
        for (freq in listOf(100.0, 1000.0, 6000.0, 10000.0)) {
            val hf = MicScorer.score(tone(freq, 1.0)).hfFraction
            assertTrue("hfFraction for ${freq}Hz was $hf", hf in 0f..1f)
        }
    }

    @Test
    fun highFrequencyClipHasNonNegativeSnr() {
        val snr = MicScorer.score(fricativeClip(1.0)).snrDb
        assertTrue("SNR was $snr dB", snr >= 0f)
    }
}

package org.schabi.parakeetype.recognition

import android.speech.SpeechRecognizer
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

/**
 * Parakeet transcribes all its languages without being told which one is spoken, but reports
 * none. [LanguageTracker] turns a text language detector's guesses for the transcript into the
 * `onLanguageDetection` reports of a `SpeechRecognizer` session.
 */
class LanguageTrackerTest {

    private fun tracker(allowed: List<String> = emptyList(), reportSwitch: Boolean = false) =
        LanguageTracker(allowed, reportSwitch)

    @Test
    fun `given allowed languages with regions, when the text is detected as a bare language, then the allowed tag is reported`() {
        val detection = tracker(listOf("en-US", "de-DE"))
            .onHypotheses(listOf(LanguageHypothesis("en", 0.97f), LanguageHypothesis("de", 0.02f)))

        assertThat(detection!!.language).isEqualTo("en-US")
        assertThat(detection.confidenceLevel).isEqualTo(SpeechRecognizer.LANGUAGE_DETECTION_CONFIDENCE_LEVEL_HIGHLY_CONFIDENT)
        assertThat(detection.alternatives).containsExactly("en-US", "de-DE")
        assertThat(detection.switchResult).isNull()
    }

    @Test
    fun `given allowed languages, when the best guess is not allowed, then the best allowed guess is reported`() {
        val detection = tracker(listOf("en-US", "de-DE"))
            .onHypotheses(listOf(LanguageHypothesis("nl", 0.6f), LanguageHypothesis("de", 0.3f)))

        assertThat(detection!!.language).isEqualTo("de-DE")
        assertThat(detection.confidenceLevel).isEqualTo(SpeechRecognizer.LANGUAGE_DETECTION_CONFIDENCE_LEVEL_NOT_CONFIDENT)
        assertThat(detection.alternatives).containsExactly("de-DE")
    }

    @Test
    fun `given no allowed languages, when detecting, then only Parakeet's languages are reported`() {
        val tracker = tracker()

        assertThat(tracker.onHypotheses(listOf(LanguageHypothesis("ja", 0.9f)))).isNull()
        val detection = tracker.onHypotheses(listOf(LanguageHypothesis("fr-FR", 0.8f), LanguageHypothesis("ja", 0.1f)))
        assertThat(detection!!.language).isEqualTo("fr-FR")
        assertThat(detection.confidenceLevel).isEqualTo(SpeechRecognizer.LANGUAGE_DETECTION_CONFIDENCE_LEVEL_CONFIDENT)
    }

    @Test
    fun `given no guess, when detecting, then nothing is reported`() {
        assertThat(tracker(listOf("en-US")).onHypotheses(emptyList())).isNull()
    }

    @Test
    fun `given the same language again, when detecting, then it is not reported twice`() {
        val tracker = tracker(listOf("en-US", "de-DE"))
        tracker.onHypotheses(listOf(LanguageHypothesis("en", 0.95f)))

        assertThat(tracker.onHypotheses(listOf(LanguageHypothesis("en", 0.96f)))).isNull()
    }

    @Test
    fun `given language switching, when the language changes, then the switch is reported as succeeded`() {
        val tracker = tracker(listOf("en-US", "de-DE"), reportSwitch = true)

        val first = tracker.onHypotheses(listOf(LanguageHypothesis("en", 0.95f)))
        val second = tracker.onHypotheses(listOf(LanguageHypothesis("de", 0.95f)))

        assertThat(first!!.switchResult).isEqualTo(SpeechRecognizer.LANGUAGE_SWITCH_RESULT_NOT_ATTEMPTED)
        assertThat(second!!.language).isEqualTo("de-DE")
        // Parakeet needs no switch: it already transcribes every one of its languages.
        assertThat(second.switchResult).isEqualTo(SpeechRecognizer.LANGUAGE_SWITCH_RESULT_SUCCEEDED)
    }

    @Test
    fun `given no usable text classifier, when detection fails, then an undetermined language is reported once`() {
        val tracker = tracker(listOf("en-US", "de-DE"), reportSwitch = true)

        val detection = tracker.onDetectionFailed()

        assertThat(detection!!.language).isEqualTo("und")
        assertThat(detection.confidenceLevel).isEqualTo(SpeechRecognizer.LANGUAGE_DETECTION_CONFIDENCE_LEVEL_UNKNOWN)
        assertThat(detection.alternatives).isEmpty()
        assertThat(detection.switchResult).isEqualTo(SpeechRecognizer.LANGUAGE_SWITCH_RESULT_NOT_ATTEMPTED)
        assertThat(tracker.onDetectionFailed()).isNull()
    }

    @Test
    fun `given a failed detection, when a later detection succeeds, then the language is reported`() {
        val tracker = tracker(listOf("en-US", "de-DE"), reportSwitch = true)
        tracker.onDetectionFailed()

        val detection = tracker.onHypotheses(listOf(LanguageHypothesis("en", 0.95f)))

        assertThat(detection!!.language).isEqualTo("en-US")
        // Nothing was detected before, so there was no switch.
        assertThat(detection.switchResult).isEqualTo(SpeechRecognizer.LANGUAGE_SWITCH_RESULT_NOT_ATTEMPTED)
    }

    @Test
    fun `given a mixed-language transcript, when choosing the text to detect, then the last clause is used`() {
        assertThat(languageDetectionText("This is a English sentence, das ist ein deutscher Satz."))
            .isEqualTo("das ist ein deutscher Satz")
        assertThat(languageDetectionText("This is a English sentence"))
            .isEqualTo("This is a English sentence")
    }

    @Test
    fun `given a short last clause, when choosing the text to detect, then earlier clauses are added`() {
        assertThat(languageDetectionText("Ich glaube, ja. Gut."))
            .isEqualTo("Ich glaube, ja. Gut")
        assertThat(languageDetectionText("  ")).isEmpty()
    }
}

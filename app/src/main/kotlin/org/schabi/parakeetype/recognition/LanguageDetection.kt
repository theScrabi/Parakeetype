package org.schabi.parakeetype.recognition

import android.content.Context
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.textclassifier.TextClassificationManager
import android.view.textclassifier.TextClassifier
import android.view.textclassifier.TextLanguage

private const val TAG = "LanguageDetection"

/** The last clauses of the transcript are detected until they hold at least this many words. */
private const val MIN_DETECTION_WORDS = 3

private const val HIGHLY_CONFIDENT_SCORE = 0.9f
private const val CONFIDENT_SCORE = 0.7f

/** BCP-47 "undetermined": reported when the language could not be classified at all. */
internal const val UNDETERMINED_LANGUAGE = "und"

/** A clause ends at punctuation followed by a space ("3.5" stays one word). */
private val CLAUSE_BREAK = Regex("[.,!?;:]+\\s+")
private val WHITESPACE = Regex("\\s+")

/** One guess of a [LanguageDetector]: a BCP-47 tag and its score in [0.0, 1.0]. */
data class LanguageHypothesis(val languageTag: String, val score: Float)

/** Guesses the language of a text, best guess first; `null` when classification is not possible. */
fun interface LanguageDetector {
    fun detect(text: String): List<LanguageHypothesis>?
}

/**
 * Detects the language with the system's on-device text classifier
 * ([android.view.textclassifier.TextClassifier.detectLanguage]), which AOSP's ExtServices
 * provides (also on ROMs without Google apps). Blocking: call it off the main thread.
 *
 * Returns `null` when the classifier is missing or fails. A missing classifier service
 * rarely throws: the system classifier then falls back to [TextClassifier.NO_OP], which
 * answers with no hypothesis at all, so an empty answer counts as a failure too.
 */
class TextClassifierLanguageDetector(context: Context) : LanguageDetector {

    private val manager = context.getSystemService(TextClassificationManager::class.java)

    override fun detect(text: String): List<LanguageHypothesis>? = try {
        val classifier = manager?.textClassifier?.takeUnless { it === TextClassifier.NO_OP }
        val result = classifier?.detectLanguage(TextLanguage.Request.Builder(text).build())
        if (result == null || result.localeHypothesisCount == 0) {
            Log.w(TAG, "No text classifier can detect the language")
            null
        } else (0 until result.localeHypothesisCount).map { i ->
            val locale = result.getLocale(i)
            LanguageHypothesis(locale.toLanguageTag(), result.getConfidenceScore(locale))
        }
    } catch (e: Exception) {
        Log.w(TAG, "Language detection failed", e)
        null
    }
}

/** An `onLanguageDetection` report; [switchResult] is `null` unless switching was requested. */
data class LanguageDetection(
    /** The detected language, spelled as in the client's allowed languages. */
    val language: String,
    /** A `SpeechRecognizer.LANGUAGE_DETECTION_CONFIDENCE_LEVEL_*` value. */
    val confidenceLevel: Int,
    /** All allowed languages the detector considered, best first. */
    val alternatives: List<String>,
    /** A `SpeechRecognizer.LANGUAGE_SWITCH_RESULT_*` value. */
    val switchResult: Int?,
)

/**
 * Turns a [LanguageDetector]'s guesses for the transcript into `onLanguageDetection` reports.
 *
 * Parakeet transcribes every one of its languages without being told which one is spoken, but
 * reports none, so the language is detected from the recognised text. Guesses are limited to
 * [allowedLanguages] (`EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES`), or to Parakeet's languages
 * when the client allows any. A language is reported when it differs from the last report.
 *
 * With [reportSwitch] (`EXTRA_ENABLE_LANGUAGE_SWITCH`) each report carries a switch result:
 * a change of language "succeeds" at once, as the model needs no switch to follow it.
 *
 * When the language cannot be classified at all ([onDetectionFailed]) before any language
 * was reported, the client hears once that it is undetermined.
 */
class LanguageTracker(
    private val allowedLanguages: List<String>,
    private val reportSwitch: Boolean,
) {
    /** The last reported language, [UNDETERMINED_LANGUAGE] included. */
    private var reported: String? = null

    /** The last language actually detected; a change from it is a switch. */
    private var detected: String? = null

    fun onHypotheses(hypotheses: List<LanguageHypothesis>): LanguageDetection? {
        val candidates = hypotheses
            .mapNotNull { hypothesis -> allowedTag(hypothesis.languageTag)?.let { it to hypothesis.score } }
            .distinctBy { it.first }
        val (language, score) = candidates.firstOrNull() ?: return null
        if (language == reported) return null
        val previous = detected
        reported = language
        detected = language
        return LanguageDetection(
            language = language,
            confidenceLevel = when {
                score >= HIGHLY_CONFIDENT_SCORE -> SpeechRecognizer.LANGUAGE_DETECTION_CONFIDENCE_LEVEL_HIGHLY_CONFIDENT
                score >= CONFIDENT_SCORE -> SpeechRecognizer.LANGUAGE_DETECTION_CONFIDENCE_LEVEL_CONFIDENT
                else -> SpeechRecognizer.LANGUAGE_DETECTION_CONFIDENCE_LEVEL_NOT_CONFIDENT
            },
            alternatives = candidates.map { it.first },
            switchResult = when {
                !reportSwitch -> null
                previous == null -> SpeechRecognizer.LANGUAGE_SWITCH_RESULT_NOT_ATTEMPTED
                else -> SpeechRecognizer.LANGUAGE_SWITCH_RESULT_SUCCEEDED
            },
        )
    }

    /** The detector could not classify the text: reported only while nothing else was. */
    fun onDetectionFailed(): LanguageDetection? {
        if (reported != null) return null
        reported = UNDETERMINED_LANGUAGE
        return LanguageDetection(
            language = UNDETERMINED_LANGUAGE,
            confidenceLevel = SpeechRecognizer.LANGUAGE_DETECTION_CONFIDENCE_LEVEL_UNKNOWN,
            alternatives = emptyList(),
            switchResult = if (reportSwitch) SpeechRecognizer.LANGUAGE_SWITCH_RESULT_NOT_ATTEMPTED else null,
        )
    }

    /** [tag] as the client spelled it in [allowedLanguages], or `null` if it is not allowed. */
    private fun allowedTag(tag: String): String? {
        val language = tag.primaryLanguage()
        if (allowedLanguages.isEmpty()) return tag.takeIf { language in PARAKEET_LANGUAGES }
        return allowedLanguages.firstOrNull { it.primaryLanguage() == language }
    }

    private fun String.primaryLanguage() = substringBefore('-').substringBefore('_').lowercase()
}

/**
 * The part of [transcript] whose language is the one currently spoken: its last clause, with
 * earlier clauses added until there are enough words for a reliable guess.
 */
internal fun languageDetectionText(transcript: String): String {
    val text = transcript.trim().trimEnd { it in ".,!?;:" }
    val clauseStarts = listOf(0) + CLAUSE_BREAK.findAll(text).map { it.range.last + 1 }
    for (start in clauseStarts.asReversed()) {
        val clauses = text.substring(start)
        if (clauses.split(WHITESPACE).size >= MIN_DETECTION_WORDS) return clauses
    }
    return text
}

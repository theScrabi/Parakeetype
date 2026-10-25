package org.schabi.parakeetype.inference

import java.util.Locale

/** One entry of the user's replacement table: [from] (a word or phrase) is replaced by [to]. */
data class TextReplacement(val from: String, val to: String)

/**
 * The user's lookup table of word / phrase replacements. [cleanTranscript] applies it after
 * filler removal, number formatting and phrase deduplication (so [TextReplacement.from] is
 * written as the transcript shows it, e.g. with digits) and before the structural clean-up
 * (punctuation spacing, sentence capitalisation).
 *
 * Matching is case-insensitive, on whole words only, and any whitespace in the transcript
 * matches a space in [TextReplacement.from]. All rules run in one pass, longest phrase first,
 * so a replacement's output is never replaced again. A replacement that starts with
 * punctuation (e.g. "Komma" → ",") swallows the space before it. Replacements are inserted as
 * written; the sentence capitalisation that runs afterwards capitalises sentence starts.
 *
 * Pure Kotlin — safe to unit-test on the JVM.
 */
class TextReplacements(rules: List<TextReplacement>) {

    /** Normalised [TextReplacement.from] → [TextReplacement.to]; the first rule for a phrase wins. */
    private val byKey: Map<String, String> = rules
        .filter { key(it.from).isNotEmpty() }
        .reversed()
        .associate { key(it.from) to it.to.trim() }

    private val regex: Regex? = if (byKey.isEmpty()) null else Regex(
        byKey.keys
            .sortedByDescending { it.length }
            .joinToString("|", prefix = """(\s*)(?<![\p{L}\p{N}])(?:""", postfix = """)(?![\p{L}\p{N}])""") { k ->
                k.split(' ').joinToString("""\s+""") { Regex.escape(it) }
            },
        RegexOption.IGNORE_CASE,
    )

    val isEmpty: Boolean get() = regex == null

    fun apply(text: String): String {
        val re = regex ?: return text
        val replaced = re.replace(text) { m ->
            val leadingSpace = m.groupValues[1]
            val matched = m.value.substring(leadingSpace.length)
            val to = byKey[key(matched)] ?: return@replace m.value
            when {
                to.isEmpty() -> leadingSpace
                to.first() in PUNCTUATION -> to
                else -> leadingSpace + to
            }
        }
        return if (replaced == text) text else replaced.replace(MULTI_SPACE_RE, " ").trim()
    }

    companion object {
        val NONE = TextReplacements(emptyList())

        private val WHITESPACE_RE = Regex("""\s+""")
        private val MULTI_SPACE_RE = Regex(""" {2,}""")
        private const val PUNCTUATION = ".,;:!?"

        private fun key(phrase: String): String =
            phrase.trim().split(WHITESPACE_RE).joinToString(" ").lowercase(Locale.ROOT)

        /**
         * Serialises [rules] for the preferences: one rule per line, `from` and `to` separated
         * by a tab. Tabs and line breaks inside a rule become spaces.
         */
        fun encode(rules: List<TextReplacement>): String = rules.joinToString("\n") {
            "${it.from.toSingleLine()}\t${it.to.toSingleLine()}"
        }

        /** Inverse of [encode]; skips malformed lines. */
        fun decode(encoded: String): List<TextReplacement> = encoded
            .split('\n')
            .mapNotNull { line ->
                val tab = line.indexOf('\t')
                if (tab < 0) null else TextReplacement(line.substring(0, tab), line.substring(tab + 1))
            }

        private fun String.toSingleLine(): String = replace(Regex("""[\t\r\n]+"""), " ")
    }
}

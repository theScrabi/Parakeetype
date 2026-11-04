package org.schabi.parakeetype.bubble

/** The field text after inserting a transcript, and where the cursor goes. */
data class Splice(val text: String, val cursor: Int)

/** Punctuation that attaches to the previous word, so no space goes in front of it. */
private const val CLOSING_PUNCTUATION = ".,;:!?)]}…'\"%"

/** Characters after which a new word follows without a space. */
private const val OPENING_PUNCTUATION = "([{\"'/@#"

/**
 * Inserts [insert] into [current] in place of the selection [selStart]..[selEnd], adding a
 * space before or after it where it would otherwise run into the neighbouring word.
 *
 * A missing or invalid selection (negative, as reported by fields without a cursor) appends at
 * the end. The cursor ends up right after the inserted text (and its trailing space).
 */
fun spliceTranscript(current: CharSequence, selStart: Int, selEnd: Int, insert: String): Splice {
    val text = current.toString()
    val trimmed = insert.trim()
    if (trimmed.isEmpty()) {
        val at = if (selStart in 0..text.length) selStart else text.length
        return Splice(text, at)
    }

    val (start, end) = if (selStart < 0 || selEnd < 0 || selStart > text.length || selEnd > text.length) {
        text.length to text.length
    } else {
        minOf(selStart, selEnd) to maxOf(selStart, selEnd)
    }

    val before = text.substring(0, start)
    val after = text.substring(end)

    val needsLeadingSpace = before.isNotEmpty() &&
            !before.last().isWhitespace() &&
            before.last() !in OPENING_PUNCTUATION &&
            trimmed.first() !in CLOSING_PUNCTUATION
    val needsTrailingSpace = after.isNotEmpty() &&
            !after.first().isWhitespace() &&
            after.first() !in CLOSING_PUNCTUATION

    val piece = buildString {
        if (needsLeadingSpace) append(' ')
        append(trimmed)
        if (needsTrailingSpace) append(' ')
    }
    return Splice(before + piece + after, before.length + piece.length)
}

/**
 * The text to put on the clipboard when the field only accepts a paste: the transcript with a
 * leading space when the field already holds text, since the cursor position is unknown.
 */
fun pasteText(fieldHasText: Boolean, insert: String): String {
    val trimmed = insert.trim()
    return if (fieldHasText && trimmed.isNotEmpty() && trimmed.first() !in CLOSING_PUNCTUATION) " $trimmed" else trimmed
}

package org.schabi.parakeetype.inference

import org.schabi.parakeetype.audio.AudioChunk
import org.schabi.parakeetype.audio.PIPELINE_SAMPLE_RATE
import kotlin.math.abs

/** Apart by at most this much, a chunk's position counts as the continuation of the previous one. */
private const val POSITION_TOLERANCE_MS = 2L

/**
 * One word of a transcript: [text] as it appears in the transcript (with adjacent
 * punctuation), when it starts and how sure the model is of it.
 *
 * @param startMs Position of the word's first token in the captured audio (see
 *   [AudioChunk.timestampMs]), accurate to one encoder frame (80 ms).
 * @param confidence Geometric mean of the word's token probabilities in [0.0, 1.0].
 */
data class WordTiming(val text: String, val startMs: Long, val confidence: Float)

/**
 * Maps the sample positions of an utterance's audio to positions in the captured audio.
 *
 * The VAD drops silence, so the audio that reaches the recogniser has gaps the decoder does
 * not know about. Each chunk's [AudioChunk.timestampMs] is recorded where it continues the
 * utterance; positions in between are counted from the chunk before them.
 */
internal class CapturePositions {

    /** Utterance sample position → capture position (ms) of each discontinuity, ascending. */
    private val anchors = ArrayList<Pair<Int, Long>>()

    /** Records that the audio at utterance sample [position] was captured at [timestampMs]. */
    fun onAudio(position: Int, timestampMs: Long) {
        if (anchors.isNotEmpty() && abs(msAt(position) - timestampMs) <= POSITION_TOLERANCE_MS) return
        anchors += position to timestampMs
    }

    /** The capture position (ms) of utterance sample [position]; also before / past the audio. */
    fun msAt(position: Int): Long {
        val (anchorPosition, anchorMs) = anchors.lastOrNull { it.first <= position }
            ?: anchors.firstOrNull()
            ?: (0 to 0L)
        return anchorMs + Math.floorDiv((position - anchorPosition) * 1_000L, PIPELINE_SAMPLE_RATE.toLong())
    }

    fun clear() = anchors.clear()
}

/**
 * Groups SentencePiece tokens into words: a word starts at a token with the word-boundary
 * marker `▁` (the bare marker in front of a number included) and takes every following token
 * without one — word pieces, digits and punctuation. Returns the index range of each word in
 * [tokenIds].
 */
internal fun sentencePieceWordRanges(tokenIds: List<Int>, vocabulary: Array<String>, blankId: Int): List<IntRange> {
    val ranges = mutableListOf<IntRange>()
    var start = 0
    for (i in 1 until tokenIds.size) {
        val id = tokenIds[i]
        val startsWord = id != blankId && vocabulary.getOrNull(id)?.startsWith("▁") == true
        if (startsWord) {
            ranges += start until i
            start = i
        }
    }
    if (tokenIds.isNotEmpty()) ranges += start until tokenIds.size
    return ranges
}

/**
 * Times the words of [text] — the post-processed transcript — with the decoder's [words].
 *
 * Post-processing removes fillers and stutters, turns number words into digits and changes
 * case and punctuation, so the words are aligned by edit distance over their letters and
 * digits: a kept or replaced word takes the timing of its raw word ("2025" the first of
 * "twenty twenty five"), a word without a raw counterpart that of the next one. Removed raw
 * words are dropped.
 */
fun alignWordTimings(text: String, words: List<WordTiming>): List<WordTiming> {
    val target = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (target.isEmpty() || words.isEmpty()) return emptyList()
    val a = target.map { it.normalizedWord() }
    val b = words.map { it.text.normalizedWord() }

    // cost[i][j]: edits to align target[i..] with words[j..].
    val cost = Array(a.size + 1) { IntArray(b.size + 1) }
    for (i in a.size downTo 0) for (j in b.size downTo 0) {
        cost[i][j] = when {
            i == a.size -> b.size - j
            j == b.size -> a.size - i
            else -> minOf(
                cost[i + 1][j + 1] + if (a[i] == b[j]) 0 else 1,
                cost[i + 1][j] + 1,
                cost[i][j + 1] + 1,
            )
        }
    }

    // Walk the cheapest alignment, preferring pairs over gaps so a replacement takes the
    // timing of the first word it replaces.
    val timed = ArrayList<WordTiming>(target.size)
    var i = 0
    var j = 0
    while (i < a.size) {
        when {
            j < b.size && cost[i][j] == cost[i + 1][j + 1] + if (a[i] == b[j]) 0 else 1 -> {
                timed += words[j].copy(text = target[i])
                i++
                j++
            }

            j < b.size && cost[i][j] == cost[i][j + 1] + 1 && cost[i][j] != cost[i + 1][j] + 1 -> j++
            else -> {
                val next = words.getOrNull(j) ?: words.last()
                timed += next.copy(text = target[i])
                i++
            }
        }
    }
    return timed
}

private fun String.normalizedWord(): String = filter { it.isLetterOrDigit() }.lowercase()

package org.schabi.parakeetype.inference

import org.assertj.core.api.Assertions.assertThat
import org.junit.Assume
import org.schabi.parakeetype.settings.model.SILERO_VAD_FILE
import java.io.File
import java.io.InputStream
import java.util.Locale

/**
 * Resolve the directory containing the Parakeet model files for JVM tests.
 *
 * Priority:
 *  1. System property `-Dtest.model.dir=/path/to/models`
 *  2. Environment variable `PARAKEETYPE_TEST_MODEL_DIR`
 *  3. `~/.cache/parakeetype-test-model/parakeet-ultra/`
 *
 * @throws IllegalStateException if no model directory is found.
 */
fun resolveModelDir(): File {
    // 1. System property
    val fromProp = System.getProperty("test.model.dir")
    if (!fromProp.isNullOrEmpty()) {
        val f = File(fromProp)
        if (f.isDirectory) return f
    }

    // 2. Environment variable
    val fromEnv = System.getenv("PARAKEETYPE_TEST_MODEL_DIR")
    if (!fromEnv.isNullOrEmpty()) {
        val f = File(fromEnv)
        if (f.isDirectory) return f
    }

    // 3. Cache directory
    val cacheDir = File(
        System.getProperty("user.home"),
        ".cache/parakeetype-test-model/parakeet-ultra"
    )
    if (cacheDir.exists() && cacheDir.listFiles()?.isNotEmpty() == true) return cacheDir

    throw IllegalStateException(
        buildString {
            appendLine("Parakeet model directory not found for real-audio tests.")
            appendLine()
            appendLine("Set one of:")
            appendLine("  -Dtest.model.dir=/path/to/parakeet-model")
            appendLine("  PARAKEETYPE_TEST_MODEL_DIR=/path/to/parakeet-model")
            appendLine("  Or place model files in: $cacheDir")
        }
    )
}

/**
 * Opens the WAV fixture `audio/[name]` from the test classpath. The fixtures are internal
 * development data (git-ignored, not published); without them the calling test is skipped.
 */
fun openWavFixture(name: String): InputStream {
    val stream = object {}.javaClass.classLoader!!.getResourceAsStream("audio/$name")
    Assume.assumeTrue(
        "WAV fixture '$name' not found in app/src/test/resources/audio/",
        stream != null,
    )
    return stream!!
}

/**
 * Returns the Silero VAD model from the model directory ([resolveModelDir]); it ships in the
 * model archive, not in the repository. Skips the calling test when it is not available.
 */
fun resolveSileroModel(): File {
    val file = runCatching { File(resolveModelDir(), SILERO_VAD_FILE) }.getOrNull()
    Assume.assumeTrue(
        "$SILERO_VAD_FILE not found - run devtools/fetch-test-model.sh",
        file?.isFile == true,
    )
    return file!!
}

/**
 * Computes word error rate (WER) between two strings using Levenshtein distance on word lists.
 * Returns a value in [0.0, 1.0] where 0.0 = perfect match.
 */
fun wer(expected: String, actual: String): Float {
    val expectedWords = expected.lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9\\s+]"), "")
        .split(Regex("\\s+"))
        .filter { it.isNotBlank() }
    val actualWords = actual.lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9\\s+]"), "")
        .split(Regex("\\s+"))
        .filter { it.isNotBlank() }
    if (expectedWords.isEmpty()) return if (actualWords.isEmpty()) 0f else 1f
    val distance = levenshtein(expectedWords, actualWords)
    return distance.toFloat() / expectedWords.size.coerceAtLeast(1)
}

/**
 * Assert that actual transcription is close enough to expected (WER ≤ 0.3).
 */
fun assertTranscriptionClose(expected: String, actual: String) {
    val errorRate = wer(expected, actual)
    assertThat(errorRate)
        .describedAs("WER=%.2f (expected: '%s', actual: '%s')", errorRate, expected, actual)
        .isLessThanOrEqualTo(0.3f)
}

/** Classic Levenshtein distance between two lists. */
private fun levenshtein(a: List<String>, b: List<String>): Int {
    val m = a.size
    val n = b.size
    if (m == 0) return n
    if (n == 0) return m

    // Two-row optimisation to save memory on long transcripts
    var prev = IntArray(n + 1) { it }
    val curr = IntArray(n + 1)

    for (i in 1..m) {
        curr[0] = i
        for (j in 1..n) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            curr[j] = minOf(
                curr[j - 1] + 1,       // insertion
                prev[j] + 1,           // deletion
                prev[j - 1] + cost,    // substitution
            )
        }
        val tmp = prev
        System.arraycopy(curr, 0, prev, 0, n + 1)
        System.arraycopy(tmp, 0, curr, 0, n + 1) // reuse array
    }
    return prev[n]
}

package org.schabi.parakeetype.crash

import java.util.concurrent.TimeUnit

/**
 * Dumps the app's own logcat. Android only returns log lines of the calling app's UID, so no
 * permission is needed and nothing from other apps ends up in the report.
 */
internal object LogcatReader {

    private const val MAX_LINES = "5000"

    fun dump(): String =
        run("logcat", "-d", "-v", "threadtime", "-b", "main,system,crash", "-t", MAX_LINES)
            // Some ROMs refuse one of the buffers; fall back to the default ones.
            ?: run("logcat", "-d", "-v", "threadtime", "-t", MAX_LINES)
            ?: "(logcat unavailable)"

    private fun run(vararg command: String): String? = try {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        try {
            // `-d` makes logcat exit after dumping, so reading to EOF terminates.
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val finished = process.waitFor(3, TimeUnit.SECONDS)
            output.takeIf { finished && process.exitValue() == 0 && it.isNotBlank() }
        } finally {
            process.destroy()
        }
    } catch (e: Exception) {
        null
    }
}

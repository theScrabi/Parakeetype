package org.schabi.parakeetype.crash

import android.app.ApplicationExitInfo
import java.time.Instant
import java.time.format.DateTimeFormatter

/** Static facts about the app build and device, written at the top of every crash report. */
internal data class DeviceInfo(
    val appVersion: String,
    val versionCode: Int,
    val buildType: String,
    val manufacturer: String,
    val model: String,
    val androidRelease: String,
    val sdkInt: Int,
    val abis: List<String>,
    val processName: String,
)

/** A process exit as reported by `ActivityManager.getHistoricalProcessExitReasons`. */
internal data class ExitRecord(
    val timestamp: Long,
    val reason: Int,
    val description: String?,
    val pid: Int,
)

internal val CRASH_EXIT_REASONS = setOf(
    ApplicationExitInfo.REASON_CRASH,
    ApplicationExitInfo.REASON_CRASH_NATIVE,
    ApplicationExitInfo.REASON_ANR,
)

/**
 * How long before the system-recorded exit time a report written by the JVM uncaught-exception
 * handler still counts as belonging to that exit (the handler runs, dumps logcat, then the
 * process dies and the system records the exit).
 */
internal const val JAVA_REPORT_WINDOW_MS = 60_000L

/** Crash exits newer than [lastSeen], newest first. */
internal fun newCrashExits(records: List<ExitRecord>, lastSeen: Long): List<ExitRecord> =
    records.filter { it.reason in CRASH_EXIT_REASONS && it.timestamp > lastSeen }
        .sortedByDescending { it.timestamp }

/**
 * Whether [exit] still needs a report built from its exit info. A JVM crash is already covered
 * when the uncaught-exception handler wrote the pending report shortly before the exit; native
 * crashes and ANRs never reach that handler.
 */
internal fun needsExitReport(exit: ExitRecord, pendingWrittenAt: Long?): Boolean =
    exit.reason != ApplicationExitInfo.REASON_CRASH ||
        pendingWrittenAt == null ||
        pendingWrittenAt < exit.timestamp - JAVA_REPORT_WINDOW_MS

/** Builds the plain-text crash report. Pure — no Android calls — so it is JVM-testable. */
internal object CrashReportFormatter {

    fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_CRASH -> "CRASH (Java/Kotlin exception)"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE (native signal)"
        ApplicationExitInfo.REASON_ANR -> "ANR (app not responding)"
        else -> "OTHER ($reason)"
    }

    fun header(device: DeviceInfo, timeMillis: Long): String = buildString {
        appendLine("Parakeetype crash report")
        appendLine("Time:     ${formatTime(timeMillis)}")
        appendLine("App:      ${device.appVersion} (${device.versionCode}, ${device.buildType})")
        appendLine("Device:   ${device.manufacturer} ${device.model}")
        appendLine("Android:  ${device.androidRelease} (SDK ${device.sdkInt})")
        appendLine("ABIs:     ${device.abis.joinToString(", ")}")
        appendLine("Process:  ${device.processName}")
    }

    /** Report written by the uncaught-exception handler while the crashing process is still alive. */
    fun javaCrash(
        device: DeviceInfo,
        timeMillis: Long,
        threadName: String,
        throwable: Throwable,
        logcat: String,
    ): String = buildString {
        append(header(device, timeMillis))
        appendLine()
        appendLine(section("Crash"))
        appendLine("Thread: $threadName")
        appendLine(throwable.stackTraceToString().trimEnd())
        appendLine()
        appendLine(section("Logcat"))
        appendLine(logcat.trimEnd())
    }

    /** Report built on the next start from the system's record of a crash the handler didn't see. */
    fun exitReport(
        device: DeviceInfo,
        timeMillis: Long,
        exit: ExitRecord,
        anrTrace: String?,
        logcat: String,
    ): String = buildString {
        append(header(device, timeMillis))
        appendLine()
        appendLine(section("Exit info"))
        appendLine("Reason:      ${reasonName(exit.reason)}")
        appendLine("Exited at:   ${formatTime(exit.timestamp)}")
        appendLine("PID:         ${exit.pid}")
        appendLine("Description: ${exit.description ?: "-"}")
        if (!anrTrace.isNullOrBlank()) {
            appendLine()
            appendLine(section("ANR trace"))
            appendLine(anrTrace.trimEnd())
        }
        appendLine()
        appendLine(section("Logcat"))
        appendLine(logcat.trimEnd())
    }

    fun section(title: String): String = "=== $title ==="

    private fun formatTime(millis: Long): String =
        DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(millis))
}

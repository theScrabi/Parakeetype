package org.schabi.parakeetype.crash

import android.app.ApplicationExitInfo
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

/** Tests for the pure [CrashReportFormatter] report layout. */
class CrashReportFormatterTest {

    private val device = DeviceInfo(
        appVersion = "0.3.1",
        versionCode = 11,
        buildType = "release",
        manufacturer = "Google",
        model = "Pixel 8",
        androidRelease = "16",
        sdkInt = 36,
        abis = listOf("arm64-v8a", "armeabi-v7a"),
        processName = "org.schabi.parakeetype",
    )

    // 2026-01-02T03:04:05Z
    private val time = 1_767_323_045_000L

    @Test
    fun `header lists app, device and time`() {
        val header = CrashReportFormatter.header(device, time)

        assertThat(header)
            .contains("Time:     2026-01-02T03:04:05Z")
            .contains("App:      0.3.1 (11, release)")
            .contains("Device:   Google Pixel 8")
            .contains("Android:  16 (SDK 36)")
            .contains("ABIs:     arm64-v8a, armeabi-v7a")
            .contains("Process:  org.schabi.parakeetype")
    }

    @Test
    fun `java crash contains thread, cause chain and logcat in order`() {
        val throwable = IllegalStateException("outer", IllegalArgumentException("root cause"))

        val report = CrashReportFormatter.javaCrash(device, time, "main", throwable, "log line 1\nlog line 2\n")

        assertThat(report)
            .contains("Thread: main")
            .contains("java.lang.IllegalStateException: outer")
            .contains("Caused by: java.lang.IllegalArgumentException: root cause")
            .contains("log line 2")
        assertThat(report.indexOf("=== Crash ===")).isLessThan(report.indexOf("=== Logcat ==="))
        assertThat(report.indexOf("root cause")).isLessThan(report.indexOf("log line 1"))
    }

    @Test
    fun `exit report includes ANR trace only when present`() {
        val anr = ExitRecord(time, ApplicationExitInfo.REASON_ANR, "Input dispatching timed out", 1234)

        val withTrace = CrashReportFormatter.exitReport(device, time, anr, "\"main\" prio=5 tid=1", "logs")
        val withoutTrace = CrashReportFormatter.exitReport(device, time, anr, null, "logs")

        assertThat(withTrace)
            .contains("Reason:      ANR (app not responding)")
            .contains("PID:         1234")
            .contains("Description: Input dispatching timed out")
            .contains("=== ANR trace ===")
            .contains("\"main\" prio=5 tid=1")
        assertThat(withoutTrace).doesNotContain("=== ANR trace ===").contains("=== Logcat ===")
    }

    @Test
    fun `reason names`() {
        assertThat(CrashReportFormatter.reasonName(ApplicationExitInfo.REASON_CRASH)).startsWith("CRASH ")
        assertThat(CrashReportFormatter.reasonName(ApplicationExitInfo.REASON_CRASH_NATIVE)).startsWith("CRASH_NATIVE")
        assertThat(CrashReportFormatter.reasonName(ApplicationExitInfo.REASON_ANR)).startsWith("ANR")
        assertThat(CrashReportFormatter.reasonName(ApplicationExitInfo.REASON_LOW_MEMORY)).startsWith("OTHER")
    }
}

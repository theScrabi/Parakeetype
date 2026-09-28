package org.schabi.parakeetype.crash

import android.app.ApplicationExitInfo.REASON_ANR
import android.app.ApplicationExitInfo.REASON_CRASH
import android.app.ApplicationExitInfo.REASON_CRASH_NATIVE
import android.app.ApplicationExitInfo.REASON_LOW_MEMORY
import android.app.ApplicationExitInfo.REASON_USER_REQUESTED
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

/** Tests for [newCrashExits] and [needsExitReport] — which process exits get a crash report. */
class CrashExitFilterTest {

    private fun exit(timestamp: Long, reason: Int) = ExitRecord(timestamp, reason, null, 1)

    @Test
    fun `keeps only crash reasons newer than last seen, newest first`() {
        val records = listOf(
            exit(100, REASON_CRASH),          // already seen
            exit(200, REASON_LOW_MEMORY),     // not a crash
            exit(300, REASON_CRASH_NATIVE),
            exit(400, REASON_USER_REQUESTED), // not a crash
            exit(500, REASON_ANR),
            exit(250, REASON_CRASH),
        )

        val result = newCrashExits(records, lastSeen = 100)

        assertThat(result.map { it.timestamp }).containsExactly(500L, 300L, 250L)
    }

    @Test
    fun `nothing new when every exit is older than last seen`() {
        val records = listOf(exit(100, REASON_CRASH), exit(90, REASON_ANR))

        assertThat(newCrashExits(records, lastSeen = 100)).isEmpty()
    }

    @Test
    fun `java crash already covered by the handler's report is not written again`() {
        val crash = exit(1_000_000, REASON_CRASH)

        // Handler wrote the report 2 s before the system recorded the exit.
        assertThat(needsExitReport(crash, pendingWrittenAt = 998_000)).isFalse()
    }

    @Test
    fun `java crash without a handler report needs an exit report`() {
        val crash = exit(1_000_000, REASON_CRASH)

        assertThat(needsExitReport(crash, pendingWrittenAt = null)).isTrue()
        // A stale report from an earlier crash doesn't cover this one.
        assertThat(needsExitReport(crash, pendingWrittenAt = 1_000_000 - JAVA_REPORT_WINDOW_MS - 1)).isTrue()
    }

    @Test
    fun `native crashes and ANRs always need an exit report`() {
        assertThat(needsExitReport(exit(1_000_000, REASON_CRASH_NATIVE), pendingWrittenAt = 999_999)).isTrue()
        assertThat(needsExitReport(exit(1_000_000, REASON_ANR), pendingWrittenAt = 999_999)).isTrue()
    }
}

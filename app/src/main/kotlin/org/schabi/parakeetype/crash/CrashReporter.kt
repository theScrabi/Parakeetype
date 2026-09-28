package org.schabi.parakeetype.crash

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.schabi.parakeetype.BuildConfig
import org.schabi.parakeetype.R
import org.schabi.parakeetype.settings.SettingsActivity
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val TAG = "CrashReporter"
private const val CHANNEL_ID = "parakeetype_crash"
private const val NOTIFICATION_ID = 1002

/**
 * Local-only crash reporter. Nothing is ever uploaded: a crash is written to
 * `filesDir/crash/pending.log`, and the user is offered to share it.
 *
 * - JVM crashes are caught by the uncaught-exception handler installed in [install], which writes
 *   the stack trace plus a logcat dump before handing the crash to the previous handler.
 * - Native crashes and ANRs never reach that handler; [checkPreviousExit] picks them up from
 *   `ApplicationExitInfo` on the next process start, while the logcat ring buffer still holds the
 *   lines from before the crash.
 *
 * After a crash the next process start (usually the keyboard, which Android restarts on its own)
 * posts a notification that opens [SettingsActivity], which shows [CrashReportDialog] to share it.
 */
object CrashReporter {

    private val _hasPendingReport = MutableStateFlow(false)

    /** True while a crash report is waiting for the user to share or dismiss it. */
    val hasPendingReport: StateFlow<Boolean> = _hasPendingReport.asStateFlow()

    private fun crashDir(context: Context) = File(context.filesDir, "crash")
    private fun pendingFile(context: Context) = File(crashDir(context), "pending.log")
    private fun lastExitFile(context: Context) = File(crashDir(context), "last_exit_ts")
    private fun shareDir(context: Context) = File(context.cacheDir, "crash_share")

    fun install(application: Application) {
        _hasPendingReport.value = pendingFile(application).isFile
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val now = System.currentTimeMillis()
                writePending(
                    application,
                    CrashReportFormatter.javaCrash(
                        deviceInfo(application), now, thread.name, throwable, LogcatReader.dump(),
                    ),
                )
            } catch (t: Throwable) {
                // Never let the reporter hide the real crash.
                Log.e(TAG, "Failed to write crash report", t)
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    /**
     * Looks for crashes of earlier processes that the system recorded since the last check,
     * writes a report for those the JVM handler couldn't catch, and notifies the user.
     * Does blocking I/O (runs logcat) — call it off the main thread.
     */
    fun checkPreviousExit(context: Context) {
        try {
            val am = context.getSystemService(ActivityManager::class.java) ?: return
            val exitInfos = am.getHistoricalProcessExitReasons(context.packageName, 0, 10)
            // First run of a build with this reporter: only report crashes since the install/update.
            val lastSeen = readLastSeen(context) ?: context.packageManager
                .getPackageInfo(context.packageName, 0).lastUpdateTime
            exitInfos.maxOfOrNull { it.timestamp }?.let { writeLastSeen(context, maxOf(it, lastSeen)) }

            val records = exitInfos.map {
                ExitRecord(it.timestamp, it.reason, it.description, it.pid)
            }
            val newest = newCrashExits(records, lastSeen).firstOrNull() ?: return

            val pending = pendingFile(context)
            val pendingWrittenAt = pending.takeIf { it.isFile }?.lastModified()
            if (needsExitReport(newest, pendingWrittenAt)) {
                val anrTrace = exitInfos.firstOrNull { it.timestamp == newest.timestamp }
                    ?.takeIf { it.reason == ApplicationExitInfo.REASON_ANR }
                    ?.let { info -> info.traceInputStream?.bufferedReader()?.use { it.readText() } }
                writePending(
                    context,
                    CrashReportFormatter.exitReport(
                        deviceInfo(context), System.currentTimeMillis(), newest, anrTrace,
                        LogcatReader.dump(),
                    ),
                )
            }
            if (pending.isFile) postNotification(context)
        } catch (e: Exception) {
            Log.w(TAG, "Checking previous process exits failed", e)
        }
    }

    /** File name of the shared report copy. */
    private fun suggestedFileName(): String =
        "parakeetype-crash-" + DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneId.systemDefault())
            .format(Instant.now()) + ".log"

    /**
     * Copies the pending report into the share cache and returns a share-sheet intent for it,
     * or `null` when there is no report. Does file I/O — call it off the main thread.
     */
    fun shareIntent(context: Context): Intent? {
        val pending = pendingFile(context).takeIf { it.isFile } ?: return null
        val dir = shareDir(context)
        dir.deleteRecursively()
        dir.mkdirs()
        val copy = pending.copyTo(File(dir, suggestedFileName()), overwrite = true)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.crashlog", copy)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, copy.name)
            clipData = ClipData.newRawUri(copy.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, context.getString(R.string.crash_share_title))
    }

    /** Deletes the pending report and its notification. */
    fun discard(context: Context) {
        pendingFile(context).delete()
        _hasPendingReport.value = false
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }

    private fun writePending(context: Context, report: String) {
        val dir = crashDir(context).apply { mkdirs() }
        val tmp = File(dir, "pending.log.tmp")
        tmp.writeText(report)
        if (!tmp.renameTo(pendingFile(context))) {
            tmp.delete()
            return
        }
        _hasPendingReport.value = true
    }

    private fun readLastSeen(context: Context): Long? =
        lastExitFile(context).takeIf { it.isFile }?.readText()?.trim()?.toLongOrNull()

    private fun writeLastSeen(context: Context, timestamp: Long) {
        crashDir(context).mkdirs()
        lastExitFile(context).writeText(timestamp.toString())
    }

    private fun postNotification(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (!nm.areNotificationsEnabled()) return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.crash_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = context.getString(R.string.crash_channel_desc) },
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.crash_notif_title))
            .setContentText(context.getString(R.string.crash_notif_text))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    0,
                    Intent(context, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            )
            .build()
        try {
            nm.notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS revoked in between — the dialog still shows on next app open.
            Log.w(TAG, "Posting crash notification failed", e)
        }
    }

    private fun deviceInfo(context: Context) = DeviceInfo(
        appVersion = BuildConfig.VERSION_NAME,
        versionCode = BuildConfig.VERSION_CODE,
        buildType = BuildConfig.BUILD_TYPE,
        manufacturer = Build.MANUFACTURER,
        model = Build.MODEL,
        androidRelease = Build.VERSION.RELEASE,
        sdkInt = Build.VERSION.SDK_INT,
        abis = Build.SUPPORTED_ABIS.toList(),
        processName = Application.getProcessName() ?: context.packageName,
    )
}

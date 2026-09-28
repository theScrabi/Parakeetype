package org.schabi.parakeetype.crash

import android.app.Application
import kotlin.concurrent.thread

/** Installs the local [CrashReporter] in every process start (keyboard, service or settings). */
class ParakeetypeApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
        thread(name = "crash-check", isDaemon = true) { CrashReporter.checkPreviousExit(this) }
    }
}

package org.schabi.parakeetype.inference

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import org.schabi.parakeetype.R
import org.schabi.parakeetype.settings.model.ModelId
import org.schabi.parakeetype.settings.model.ModelRegistry
import org.schabi.parakeetype.settings.model.ModelStorageManager
import org.schabi.parakeetype.settings.SettingsActivity
import org.schabi.parakeetype.settings.preferences.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

private const val TAG = "InferenceService"
private const val CHANNEL_ID = "parakeetype_inference"
private const val NOTIFICATION_ID = 1001

/**
 * A [LifecycleService] that owns the [SpeechEngine] lifecycle.
 *
 * **Lifetime.** With the [AppPreferences.keepModelLoaded] setting (on by default) the
 * service is bound by the IME and additionally *starts* itself as a foreground service
 * (see [startKeepLoaded] / [onStartCommand]); a started service survives the unbind and the
 * foreground state protects the process from being reclaimed, so the model stays warm
 * across keyboard switches. When the user opts out, the service is only *bound*, so it
 * lives exactly as long as the IME: when the user switches to another keyboard the system
 * destroys the IME, the last binding goes away, the service is destroyed and the model is
 * unloaded.
 *
 * Responsibilities:
 *  - Show a persistent low-priority notification while running in the foreground
 *  - Observe [AppPreferences.selectedModelId] and load the appropriate [SpeechEngine]
 *    via [SpeechEngineFactory] whenever the selection changes
 *  - Watch the `models/` directory for file-system changes so the engine auto-reloads
 *    after a download completes and auto-unloads when model files are deleted
 *  - Expose [InferenceRepository] to bound clients (the IME) via [InferenceBinder]
 *  - Cleanly close the engine on [onDestroy]
 */
class InferenceService : LifecycleService() {

    /** Currently active grammar corrector. Tied to the service lifecycle. */
    private val grammarCorrector: GrammarCorrector = NoOpGrammarCorrector

    /** The currently loaded engine, or `null` while loading / unloaded. */
    @Volatile
    private var currentEngine: SpeechEngine? = null

    /** Repository wrapping [currentEngine]. Rebuilt each time the engine changes. */
    @Volatile
    private var currentRepository: InferenceRepository? = null

    /**
     * The model id currently selected (or being loaded). Tracked so [reloadIfNeeded] can
     * reload exactly the same model after a memory-pressure unload without re-reading
     * preferences.
     */
    @Volatile
    private var currentModelId: ModelId? = null

    /**
     * `true` after the engine was proactively closed under memory pressure (see
     * [memoryCallback]). The next [reloadIfNeeded] call reloads the model and clears it.
     */
    @Volatile
    private var memoryUnloaded: Boolean = false

    /**
     * Mirrors [AppPreferences.keepModelLoaded]. While `true` the engine is only released
     * on critical memory pressure (see [registerMemoryCallback]).
     */
    @Volatile
    private var keepLoaded: Boolean = true

    /** `true` while the service is in the started (keep-loaded foreground) state. Main thread only. */
    private var isStarted: Boolean = false

    /** Mutex preventing concurrent [reloadForModel] calls from racing. */
    private val engineLoadMutex = Mutex()

    private val _engineState = MutableStateFlow<EngineState>(EngineState.Loading)

    /** Observable loading / runtime state observed by the IME. */
    val engineState: StateFlow<EngineState> = _engineState.asStateFlow()

    inner class InferenceBinder : Binder() {
        /** Returns the active [InferenceRepository], or `null` while the engine is loading. */
        fun getRepository(): InferenceRepository? = currentRepository
        fun getEngineState(): StateFlow<EngineState> = engineState

        /**
         * Reload the previously-selected model if it was closed under memory pressure.
         *
         * Called by the IME on [android.inputmethodservice.InputMethodService.onWindowShown]
         * (and as a safety net from the keyboard VM when the user presses record while the
         * engine is Unloaded). No-op when the engine is still loaded or already loading.
         */
        fun reloadIfNeeded() {
            if (!memoryUnloaded) return
            lifecycleScope.launch(Dispatchers.Default) {
                val modelId = currentModelId
                    ?: AppPreferences(applicationContext).selectedModelId.first()
                Log.i(TAG, "reloadIfNeeded() - reloading $modelId after memory-pressure unload")
                reloadForModel(modelId)
            }
        }
    }

    private val binder = InferenceBinder()

    override fun onBind(intent: android.content.Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onCreate() {
        super.onCreate()

        createNotificationChannel()
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(getString(R.string.notif_engine_loading)),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } catch (e: SecurityException) {
            // startForeground() is rejected when the service is started via the IME binding
            // (no Activity in the foreground).  We continue running as a plain bound service -
            // no persistent notification, but the engine loads and the keyboard stays functional.
            // The notification will appear the next time the user opens the companion Activity.
            Log.w(TAG, "startForeground rejected - running as bound service without notification", e)
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException (API 31+) when created in the background,
            // e.g. a START_STICKY restart. Same fallback as above.
            Log.w(TAG, "startForeground not allowed - running as bound service without notification", e)
        }

        // Log device and memory info at service startup
        val am = getSystemService(ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
        Log.i(
            TAG,
            "Service created on device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})"
        )
        Log.i(
            TAG,
            "Total RAM: ${memInfo.totalMem / (1024 * 1024)} MB, Avail: ${memInfo.availMem / (1024 * 1024)} MB, LowMem: ${memInfo.lowMemory}"
        )

        Log.d(TAG, "Service created - observing selected model preference")

        // Observe the selected model preference and reload the engine whenever it changes.
        lifecycleScope.launch(Dispatchers.Default) {
            AppPreferences(applicationContext).selectedModelId.collect { modelId ->
                currentModelId = modelId
                memoryUnloaded = false  // a model change supersedes any memory-pressure unload
                reloadForModel(modelId)
            }
        }

        // Keep-loaded mode (default, opt-out): become a started foreground service so the engine
        // outlives the IME binding (keyboard switches destroy the IME and unbind us).
        lifecycleScope.launch {
            AppPreferences(applicationContext).keepModelLoaded.distinctUntilChanged().collect { keep ->
                keepLoaded = keep
                if (keep && !isStarted) {
                    startKeepLoaded(this@InferenceService)
                } else if (!keep && isStarted) {
                    Log.i(TAG, "keep-model-loaded disabled - leaving started foreground state")
                    isStarted = false
                    ServiceCompat.stopForeground(this@InferenceService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    // Only clears the started state: while the IME is still bound the service
                    // keeps running and is destroyed on the next unbind, as in the default mode.
                    stopSelf()
                }
            }
        }

        // Keep the model warm across keyboard hide/show and brief app
        // switches by staying bound (the IME no longer unbinds on idle). To avoid being
        // OOM-killed with ~700 MB resident, proactively close the engine when the OS
        // signals running-low / critical memory pressure and let the IME reload it on the
        // next onWindowShown via InferenceBinder.reloadIfNeeded().
        registerMemoryCallback()

        // Watch the models/root directory to detect external changes (e.g. download complete).
        startModelWatcher()
    }

    /**
     * Enters the started foreground state requested via [startKeepLoaded]. Must call
     * startForeground promptly: the service was launched with `startForegroundService`.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else 0
        val text = when (_engineState.value) {
            EngineState.Ready -> currentModelId?.let { getString(R.string.notif_engine_ready, ModelRegistry[it].displayName) }
            EngineState.Unloaded -> getString(R.string.notif_model_not_downloaded)
            else -> null
        } ?: getString(R.string.notif_engine_loading)
        return try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(text), type)
            isStarted = true
            Log.i(TAG, "Started as keep-loaded foreground service")
            START_STICKY
        } catch (e: Exception) {
            // ForegroundServiceStartNotAllowedException / SecurityException: we cannot hold
            // the process in the foreground right now. Drop the started state; the service
            // keeps working as a plain bound service.
            Log.w(TAG, "Could not enter keep-loaded foreground state", e)
            stopSelf(startId)
            START_NOT_STICKY
        }
    }

    /**
     * Registered on the application context in [onCreate]; closes the loaded engine when
     * the OS reports running-low / critical memory pressure so the ~700 MB Parakeet model
     * is reclaimed cooperatively instead of via an OOM kill. Unregistered in [onDestroy].
     *
     * Only the running-low and critical levels trigger an unload — those are the levels at
     * which the process is genuinely at risk. Background / moderate levels leave the model
     * resident so brief app switches keep it warm.
     *
     * In keep-loaded mode ([keepLoaded]) the user has explicitly traded RAM for instant
     * dictation, so only the critical levels ([ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL]
     * and [ComponentCallbacks2.onLowMemory]) release the engine.
     */
    private var memoryCallback: ComponentCallbacks2? = null

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    private fun registerMemoryCallback() {
        // Trim-memory levels are not strictly monotonic in severity:
        //   UI_HIDDEN(20), BACKGROUND(40)        — routine backgrounding, NOT real pressure.
        //   MODERATE(60), COMPLETE(80)          — backgrounded + pressure → unload.
        //   RUNNING_LOW(10), RUNNING_CRITICAL(15) — process in foreground + pressure → unload.
        // We unload on the pressure levels but NOT on UI_HIDDEN/BACKGROUND, which fire on every
        // keyboard hide and would otherwise defeat the “keep the model warm across brief
        // switches” goal. Reload is handled lazily by InferenceBinder.reloadIfNeeded() on the
        // next onWindowShown.
        val unloadLevels = setOf(
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
            ComponentCallbacks2.TRIM_MEMORY_MODERATE,
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE,
        )
        val cb = object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                val unload = if (keepLoaded) {
                    level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
                } else {
                    level in unloadLevels
                }
                if (!unload) return
                unloadDueToMemoryPressure(level)
            }

            override fun onLowMemory() {
                unloadDueToMemoryPressure(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
            }

            override fun onConfigurationChanged(newConfig: Configuration) = Unit
        }
        memoryCallback = cb
        applicationContext.registerComponentCallbacks(cb)
    }

    /**
     * Closes the currently loaded engine cooperatively under memory pressure and marks the
     * engine as memory-unloaded so [InferenceBinder.reloadIfNeeded] reloads it on next use.
     * No-op if the engine is not currently loaded / ready (e.g. still loading or already
     * unloaded). Serialized with [reloadForModel] via [engineLoadMutex].
     */
    private fun unloadDueToMemoryPressure(level: Int) {
        if (currentEngine == null || _engineState.value != EngineState.Ready) return
        Log.w(TAG, "Memory pressure (level=$level) - cooperatively closing engine to free RAM")
        lifecycleScope.launch(Dispatchers.Default) {
            engineLoadMutex.withLock {
                if (currentEngine == null) return@withLock
                currentEngine?.close()
                currentEngine = null
                currentRepository = null
                memoryUnloaded = true
                _engineState.value = EngineState.Unloaded
                updateNotification(getString(R.string.notif_model_not_downloaded))
                logMemoryUsage()
            }
        }
    }

    override fun onDestroy() {
        modelFileObserver?.stopWatching()
        modelFileObserver = null
        currentWatchDir = null
        memoryCallback?.let { applicationContext.unregisterComponentCallbacks(it) }
        memoryCallback = null
        super.onDestroy()
        currentEngine?.close()
        currentEngine = null
        currentRepository = null
        grammarCorrector.close()
        logMemoryUsage()
        Log.d(TAG, "Service destroyed - engine closed")
    }

    /**
     * Closes any existing engine and loads a fresh one for [modelId].
     * Always called inside [engineLoadMutex] to serialise concurrent invocations.
     */
    private suspend fun reloadForModel(modelId: ModelId) {
        engineLoadMutex.withLock {
            // We are (re)loading on purpose, so any prior memory-pressure unload no
            // longer applies — clear the flag so reloadIfNeeded() does not re-trigger.
            memoryUnloaded = false

            // Close the current engine before replacing it.
            currentEngine?.let { engine ->
                engine.close()
                Log.d(TAG, "Previous engine closed before reload")
            }
            currentEngine = null
            currentRepository = null

            if (!ModelStorageManager.isModelReady(applicationContext, modelId)) {
                Log.w(TAG, "Model $modelId files not present - engine stays Unloaded")
                _engineState.value = EngineState.Unloaded
                updateNotification(getString(R.string.notif_model_not_downloaded))
                return
            }

            // Log model file size and available memory before loading
            val modelDir = ModelStorageManager.getModelDir(applicationContext, modelId)
            val modelSizeMB = modelDir.walkTopDown().filter { it.isFile }.sumOf { it.length() } / (1024 * 1024)
            val am = getSystemService(ACTIVITY_SERVICE) as? ActivityManager
            val memInfo = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
            Log.i(TAG, "Preparing to load model $modelId, modelDir=${modelDir.path}, size=${modelSizeMB}MB")
            Log.i(TAG, "Available RAM: ${memInfo.availMem / (1024 * 1024)} MB, LowMem: ${memInfo.lowMemory}")
            if (modelSizeMB > 500 && memInfo.availMem < modelSizeMB * 2 * 1024 * 1024) {
                Log.w(
                    TAG,
                    "Device RAM may be insufficient for large model $modelId (model: ${modelSizeMB}MB, avail: ${memInfo.availMem / (1024 * 1024)}MB)"
                )
            }

            _engineState.value = EngineState.Loading
            updateNotification(getString(R.string.notif_engine_loading))

            val startTime = System.currentTimeMillis()
            try {
                val engine = SpeechEngineFactory.create(modelId)
                engine.load(modelDir)

                // Apply forced-language preference so post-processing uses the correct locale.
                // For Parakeet TDT, this does not affect ONNX inference (no language tensor in
                // the current export) but controls filler removal and number normalisation.
                val lang = AppPreferences(applicationContext).forcedLanguage.first()
                if (lang != null) {
                    engine.setLanguage(lang)
                    Log.d(TAG, "Forced language '$lang' applied to engine for $modelId")
                }

                currentEngine = engine
                currentRepository = InferenceRepository(engine, grammarCorrector)
                _engineState.value = EngineState.Ready
                updateNotification(getString(R.string.notif_engine_ready, ModelRegistry[modelId].displayName))
                val elapsed = System.currentTimeMillis() - startTime
                Log.i(TAG, "Engine loaded successfully for $modelId in ${elapsed}ms")
                logMemoryUsage()
            } catch (e: Exception) {
                val msg = e.localizedMessage ?: "Unknown error"
                Log.e(TAG, "Engine load failed for $modelId: $msg", e)
                _engineState.value = EngineState.Error(msg)
                updateNotification(getString(R.string.notif_engine_failed, msg))
                logMemoryUsage()
            }
        }
    }

    /**
     * Watches the `models/` root directory so we detect both deletion and installation of
     * model files for any model - triggering an engine reload for the selected model.
     *
     * The observer is only recreated when the watched directory actually changes.
     */
    private var modelFileObserver: FileObserver? = null
    private var currentWatchDir: File? = null

    private fun startModelWatcher() {
        val context = applicationContext
        // Watch the models/ root; fall back to filesDir if models/ doesn't exist yet.
        val modelsRoot = ModelStorageManager.getModelsRoot(context).also { it.mkdirs() }
        val watchDir = if (modelsRoot.exists()) modelsRoot else context.filesDir

        if (watchDir == currentWatchDir) return

        modelFileObserver?.stopWatching()
        currentWatchDir = watchDir

        modelFileObserver = object : FileObserver(watchDir, ALL_EVENTS) {
            override fun onEvent(event: Int, path: String?) {
                val mask = event and ALL_EVENTS
                if (mask and (DELETE or DELETE_SELF or MOVED_FROM or
                            CREATE or MOVED_TO or CLOSE_WRITE) != 0
                ) {
                    onModelDirectoryChanged()
                }
            }
        }.also { it.startWatching() }

        Log.d(TAG, "FileObserver started on: $watchDir")
    }

    /**
     * Called (on a FileObserver background thread) whenever anything changes under the
     * `models/` directory. Checks whether the selected model's readiness changed and
     * reloads or unloads the engine accordingly.
     */
    private fun onModelDirectoryChanged() {
        lifecycleScope.launch(Dispatchers.Default) {
            val context = applicationContext
            val selectedModelId = AppPreferences(context).selectedModelId.first()
            val isReady = ModelStorageManager.isModelReady(context, selectedModelId)
            val currentState = _engineState.value

            when {
                !isReady && currentState == EngineState.Ready -> {
                    Log.w(TAG, "Selected model files removed - unloading engine")
                    reloadForModel(selectedModelId) // will detect !isReady and set Unloaded
                    startModelWatcher()
                }

                isReady && currentState == EngineState.Unloaded -> {
                    Log.d(TAG, "Selected model files appeared - loading engine")
                    reloadForModel(selectedModelId)
                    startModelWatcher()
                }
            }
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notif_channel_desc)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Parakeetype")
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            )
            .build()

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun logMemoryUsage() {
        val runtime = Runtime.getRuntime()
        val usedMemMB = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        val maxMemMB = runtime.maxMemory() / (1024 * 1024)
        Log.i(TAG, "App memory usage: used=${usedMemMB}MB, max=${maxMemMB}MB")
        val debugMem = Debug.MemoryInfo()
        Debug.getMemoryInfo(debugMem)
        Log.i(
            TAG,
            "Debug memory: dalvik=${debugMem.dalvikPrivateDirty}KB, native=${debugMem.nativePrivateDirty}KB, totalPss=${debugMem.totalPss}KB"
        )
    }

    companion object {
        /**
         * Starts the service in keep-loaded foreground mode (and loads the model if the
         * service was not running yet). Allowed from a visible Activity or from the IME
         * process (the current input method is exempt from background-start limits);
         * failures are logged and otherwise ignored — the service still works bound-only.
         */
        fun startKeepLoaded(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, InferenceService::class.java))
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Cannot start keep-loaded foreground service right now", e)
            } catch (e: SecurityException) {
                Log.w(TAG, "Cannot start keep-loaded foreground service", e)
            }
        }
    }
}

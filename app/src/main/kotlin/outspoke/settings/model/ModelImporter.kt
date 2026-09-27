package dev.brgr.outspoke.settings.model

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext

private const val TAG = "ModelImporter"

/** Outcome of importing a model archive. */
sealed class ImportResult {
    data object Success : ImportResult()

    /** The file is not a ZIP archive, or contains none of the model's files. */
    data object NotAnArchive : ImportResult()

    /** The archive lacks some of the model's required files. */
    data class MissingFiles(val names: List<String>) : ImportResult()

    /** Some files in the archive did not match their expected SHA-256. */
    data class ChecksumMismatch(val names: List<String>) : ImportResult()

    /** The file could not be read or the model could not be written. */
    data class IoError(val message: String) : ImportResult()
}

/**
 * Installs a speech model from a **single ZIP archive** the user picked with the system
 * file picker (typically downloaded in their browser from [ModelInfo.archiveUrl]).
 *
 * Outspoke has no network access: this is the only way a model gets onto the device.
 *
 * The archive is streamed once: every entry whose file name (ignoring any directories in
 * the archive) matches one of the model's [ModelFile]s is written to a staging directory
 * next to the model directory while its SHA-256 is computed. Only when every required
 * file is present and verified is the staging directory swapped in for the model
 * directory with a single rename — a failed or cancelled import never leaves a
 * half-installed model behind. The rename is also what the
 * [dev.brgr.outspoke.inference.InferenceService] `FileObserver` on `models/` sees, so the
 * engine loads the new model immediately.
 *
 * Runs on a process-wide scope so an import survives leaving the model screen.
 */
object ModelImporter {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = mutableMapOf<ModelId, Job>()

    private val _importStates = MutableStateFlow<Map<ModelId, ModelState>>(emptyMap())

    /** Import progress per model; a model is absent from the map when no import is running. */
    val importStates: StateFlow<Map<ModelId, ModelState>> = _importStates.asStateFlow()

    private val _results = MutableSharedFlow<Pair<ModelId, ImportResult>>(extraBufferCapacity = 4)

    /** Emits the result of every finished (not cancelled) import. */
    val results: SharedFlow<Pair<ModelId, ImportResult>> = _results.asSharedFlow()

    /** Imports the archive at [uri] for [modelInfo]. No-op while an import for it is running. */
    @Synchronized
    fun import(context: Context, modelInfo: ModelInfo, uri: Uri) {
        if (jobs[modelInfo.id]?.isActive == true) return
        val appContext = context.applicationContext
        _importStates.update { it + (modelInfo.id to ModelState.Importing(0f)) }
        jobs[modelInfo.id] = scope.launch {
            try {
                val totalBytes = querySize(appContext, uri)
                val result = appContext.contentResolver.openInputStream(uri)?.use { input ->
                    val modelsRoot = ModelStorageManager.getModelsRoot(appContext).also { it.mkdirs() }
                    install(
                        input = input,
                        modelDir = ModelStorageManager.getModelDir(appContext, modelInfo.id),
                        stagingDir = File(modelsRoot, ".import-${modelInfo.id.storageDirName}"),
                        files = modelInfo.files,
                    ) { bytesRead ->
                        if (totalBytes > 0) {
                            val fraction = (bytesRead.toFloat() / totalBytes).coerceIn(0f, 1f)
                            _importStates.update { it + (modelInfo.id to ModelState.Importing(fraction)) }
                        }
                    }
                } ?: ImportResult.IoError("Cannot open the selected file")
                Log.i(TAG, "Import of ${modelInfo.id} finished: $result")
                _results.emit(modelInfo.id to result)
            } catch (e: CancellationException) {
                Log.i(TAG, "Import of ${modelInfo.id} cancelled")
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Import of ${modelInfo.id} failed", e)
                _results.emit(modelInfo.id to ImportResult.IoError(e.localizedMessage ?: e.javaClass.simpleName))
            } finally {
                _importStates.update { it - modelInfo.id }
                synchronized(this@ModelImporter) { jobs.remove(modelInfo.id) }
            }
        }
    }

    /** Cancels a running import for [modelId]; its staged files are discarded. */
    @Synchronized
    fun cancel(modelId: ModelId) {
        jobs[modelId]?.cancel()
    }

    private fun querySize(context: Context, uri: Uri): Long =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L
        } ?: -1L

    /**
     * Streams the ZIP archive [input] into [stagingDir], verifies every entry that belongs
     * to [files], and on success replaces [modelDir] with the staging directory.
     * [onProgress] receives the number of archive bytes consumed so far.
     *
     * Pure JVM (no Android dependencies) so it can be unit-tested directly.
     */
    suspend fun install(
        input: InputStream,
        modelDir: File,
        stagingDir: File,
        files: List<ModelFile>,
        onProgress: (Long) -> Unit = {},
    ): ImportResult {
        stagingDir.deleteRecursively()
        if (!stagingDir.mkdirs()) return ImportResult.IoError("Cannot create ${stagingDir.name}")
        try {
            val byName = files.associateBy { it.filename }
            val staged = mutableSetOf<String>()
            val mismatched = mutableListOf<String>()
            val counting = CountingInputStream(input, onProgress)
            try {
                ZipInputStream(counting).use { zip ->
                    val buffer = ByteArray(DEFAULT_BUFFER)
                    while (true) {
                        coroutineContext.ensureActive()
                        val entry = zip.nextEntry ?: break
                        val name = entry.name.substringAfterLast('/')
                        val expected = if (entry.isDirectory) null else byName[name]
                        if (expected == null || name in staged) continue

                        val digest = MessageDigest.getInstance("SHA-256")
                        File(stagingDir, name).outputStream().use { out ->
                            while (true) {
                                val n = zip.read(buffer)
                                if (n < 0) break
                                digest.update(buffer, 0, n)
                                out.write(buffer, 0, n)
                                coroutineContext.ensureActive()
                            }
                        }
                        val actual = digest.digest().toHex()
                        if (expected.sha256 != null && !expected.sha256.equals(actual, ignoreCase = true)) {
                            Log.w(TAG, "SHA-256 mismatch for $name")
                            mismatched += name
                        } else {
                            staged += name
                        }
                    }
                }
            } catch (e: ZipException) {
                Log.w(TAG, "Not a valid ZIP archive", e)
                return ImportResult.NotAnArchive
            }

            if (mismatched.isNotEmpty()) return ImportResult.ChecksumMismatch(mismatched)
            if (staged.isEmpty()) return ImportResult.NotAnArchive
            val missing = files.map { it.filename }.filter { it !in staged }
            if (missing.isNotEmpty()) return ImportResult.MissingFiles(missing)

            // Swap the verified model in with one rename.
            modelDir.deleteRecursively()
            if (!stagingDir.renameTo(modelDir)) return ImportResult.IoError("Cannot install ${modelDir.name}")
            return ImportResult.Success
        } catch (e: IOException) {
            return ImportResult.IoError(e.localizedMessage ?: e.javaClass.simpleName)
        } finally {
            // No-op after a successful rename; discards partial files otherwise (also on cancel).
            stagingDir.deleteRecursively()
        }
    }

    private const val DEFAULT_BUFFER = 256 * 1024

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    /** Reports the total number of bytes read through it to [onProgress]. */
    private class CountingInputStream(
        input: InputStream,
        private val onProgress: (Long) -> Unit,
    ) : FilterInputStream(input) {
        private var count = 0L
        private var lastReported = 0L

        override fun read(): Int = super.read().also { if (it >= 0) advance(1) }

        override fun read(b: ByteArray, off: Int, len: Int): Int =
            super.read(b, off, len).also { if (it > 0) advance(it.toLong()) }

        override fun skip(n: Long): Long = super.skip(n).also { advance(it) }

        private fun advance(n: Long) {
            count += n
            // Throttle to ~1 MB steps so progress updates don't flood the StateFlow.
            if (count - lastReported >= 1 shl 20) {
                lastReported = count
                onProgress(count)
            }
        }
    }
}

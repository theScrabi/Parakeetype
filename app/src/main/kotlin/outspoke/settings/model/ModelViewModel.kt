package dev.brgr.outspoke.settings.model

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.brgr.outspoke.R
import dev.brgr.outspoke.inference.InferenceService
import dev.brgr.outspoke.settings.preferences.AppPreferences
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

private const val TAG = "ModelViewModel"

/**
 * Manages the install state for every model in [ModelRegistry] and the currently
 * selected active model.
 *
 * Outspoke has no network access: models are downloaded by the user in their browser
 * ([ModelInfo.archiveUrl]) and imported from local storage via [importArchive], which
 * delegates to the process-wide [ModelImporter]. [ModelImporter.importStates] is merged
 * into [modelStates] so the UI always reflects the latest progress.
 *
 * [selectedModelId] reflects the model whose engine will be loaded by [InferenceService].
 * Calling [selectModel] persists the choice to [AppPreferences] so it survives restarts.
 */
class ModelViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = AppPreferences(application)

    private fun diskState(info: ModelInfo): ModelState =
        if (ModelStorageManager.isModelReady(getApplication(), info)) ModelState.Ready
        else ModelState.NotDownloaded

    private val _modelStates = MutableStateFlow(
        // Eagerly check on-disk state so the UI is correct on first render.
        ModelRegistry.all.associate { info -> info.id to diskState(info) }
    )
    val modelStates: StateFlow<Map<ModelId, ModelState>> = _modelStates.asStateFlow()

    private val _selectedModelId = MutableStateFlow(ModelId.DEFAULT)
    val selectedModelId: StateFlow<ModelId> = _selectedModelId.asStateFlow()

    private val _messages = MutableSharedFlow<String>(replay = 0)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    init {
        // Hydrate the selected model from DataStore on first launch.
        viewModelScope.launch {
            _selectedModelId.value = prefs.selectedModelId.first()
        }

        // Running imports override the disk snapshot; when an import ends (its entry
        // disappears) re-check the disk.
        viewModelScope.launch {
            ModelImporter.importStates.collect { importing ->
                _modelStates.value = ModelRegistry.all.associate { info ->
                    info.id to (importing[info.id] ?: diskState(info))
                }
            }
        }

        viewModelScope.launch {
            ModelImporter.results.collect { (id, result) ->
                _modelStates.update { it + (id to diskState(ModelRegistry[id])) }
                describe(result)?.let { _messages.emit(it) }
            }
        }
    }

    private fun describe(result: ImportResult): String? {
        val app = getApplication<Application>()
        return when (result) {
            ImportResult.Success -> app.getString(R.string.model_import_success)
            ImportResult.NotAnArchive -> app.getString(R.string.model_import_not_archive)
            is ImportResult.MissingFiles ->
                app.getString(R.string.model_import_missing_files, result.names.joinToString())
            is ImportResult.ChecksumMismatch ->
                app.getString(R.string.model_import_corrupted, result.names.joinToString())
            is ImportResult.IoError -> app.getString(R.string.model_import_io_error, result.message)
        }
    }

    /** Imports the model archive the user picked at [uri] for [modelId]. */
    fun importArchive(modelId: ModelId, uri: Uri) {
        ModelImporter.import(getApplication(), ModelRegistry[modelId], uri)
        Log.d(TAG, "Import requested for $modelId")
    }

    /** Cancels a running import for [modelId]. */
    fun cancelImport(modelId: ModelId) {
        ModelImporter.cancel(modelId)
        Log.d(TAG, "Import cancelled for $modelId")
    }

    /** Deletes all files for [modelId] from internal storage. */
    fun deleteModel(modelId: ModelId) {
        ModelStorageManager.deleteModel(getApplication(), modelId)
        _modelStates.update { it + (modelId to ModelState.NotDownloaded) }
        // Fall back to the default model if the active model was just deleted.
        if (_selectedModelId.value == modelId && modelId != ModelId.DEFAULT) {
            selectModel(ModelId.DEFAULT)
        }
        Log.d(TAG, "Model $modelId deleted from internal storage")
    }

    /**
     * Marks [modelId] as the active speech recognition model and persists the choice.
     * [InferenceService] observes the persisted preference and reloads its engine accordingly.
     */
    fun selectModel(modelId: ModelId) {
        _selectedModelId.value = modelId
        viewModelScope.launch { prefs.setSelectedModelId(modelId) }
        Log.d(TAG, "Selected model changed to: $modelId")
    }
}

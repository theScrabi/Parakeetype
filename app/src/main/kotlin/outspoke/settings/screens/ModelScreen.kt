package dev.brgr.outspoke.settings.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Sync
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.brgr.outspoke.R
import dev.brgr.outspoke.settings.model.*
import dev.brgr.outspoke.ui.theme.OutspokeTheme
import kotlinx.coroutines.launch

/**
 * Displays the full model catalog - one card per registered model - and allows the user
 * to install (download in the browser, then import the archive), delete, and select the
 * active speech recognition model. Outspoke itself has no network access.
 */
@Composable
fun ModelScreen(
    viewModel: ModelViewModel = viewModel(),
) {
    val modelStates by viewModel.modelStates.collectAsState()
    val selectedModel by viewModel.selectedModelId.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message ->
            snackbarHostState.showSnackbar(message = message, duration = SnackbarDuration.Long)
        }
    }

    val noBrowserMessage = stringResource(R.string.model_no_browser)

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        ModelListContent(
            modelStates = modelStates,
            selectedModel = selectedModel,
            modifier = Modifier.padding(padding),
            onImport = { id, uri -> viewModel.importArchive(id, uri) },
            onNoBrowser = { scope.launch { snackbarHostState.showSnackbar(noBrowserMessage) } },
            onCancel = { viewModel.cancelImport(it) },
            onDelete = { viewModel.deleteModel(it) },
            onSelect = { viewModel.selectModel(it) },
        )
    }
}

@Composable
private fun ModelListContent(
    modelStates: Map<ModelId, ModelState>,
    selectedModel: ModelId?,
    onImport: (ModelId, Uri) -> Unit,
    onNoBrowser: () -> Unit,
    onCancel: (ModelId) -> Unit,
    onDelete: (ModelId) -> Unit,
    onSelect: (ModelId) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                text = stringResource(R.string.model_screen_title),
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.model_screen_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        items(ModelRegistry.all, key = { it.id.name }) { modelInfo ->
            val state = modelStates[modelInfo.id] ?: ModelState.NotDownloaded
            val isSelected = selectedModel == modelInfo.id
            ModelCard(
                modelInfo = modelInfo,
                state = state,
                isSelected = isSelected,
                onImport = { uri -> onImport(modelInfo.id, uri) },
                onNoBrowser = onNoBrowser,
                onCancel = { onCancel(modelInfo.id) },
                onDelete = { onDelete(modelInfo.id) },
                onSelect = { onSelect(modelInfo.id) },
            )
        }
    }
}

@Composable
private fun ModelCard(
    modelInfo: ModelInfo,
    state: ModelState,
    isSelected: Boolean,
    onImport: (Uri) -> Unit,
    onNoBrowser: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    onSelect: () -> Unit,
) {
    val borderColor = when {
        isSelected -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outlineVariant
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        border = BorderStroke(width = if (isSelected) 2.dp else 1.dp, color = borderColor),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
            else
                MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Title row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = modelInfo.displayName,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(R.string.model_size_format, modelInfo.approximateSizeMb),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (isSelected) {
                    Icon(
                        imageVector = Icons.Rounded.CheckCircle,
                        contentDescription = stringResource(R.string.cd_active_model),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }

            Text(
                text = modelInfo.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // State-specific actions
            when (state) {
                is ModelState.NotDownloaded -> InstallActions(modelInfo, onImport, onNoBrowser)
                is ModelState.Importing -> ImportingActions(state.progressFraction, onCancel)
                is ModelState.Ready -> ReadyActions(isSelected, onSelect, onDelete)
            }
        }
    }
}

/**
 * Two-step install: (1) open the model archive URL in the browser, (2) import the
 * downloaded archive through the system file picker (no storage permission needed).
 */
@Composable
private fun InstallActions(
    modelInfo: ModelInfo,
    onImport: (Uri) -> Unit,
    onNoBrowser: () -> Unit,
) {
    val context = LocalContext.current
    val pickArchive = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onImport(uri)
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.model_install_explanation, modelInfo.approximateSizeMb),
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(
            onClick = {
                try {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(modelInfo.archiveUrl))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } catch (_: ActivityNotFoundException) {
                    onNoBrowser()
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Rounded.Download, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.action_download_in_browser))
        }
        Button(
            // "*/*": browsers do not reliably tag the download as application/zip.
            onClick = { pickArchive.launch(arrayOf("*/*")) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Rounded.CloudDownload, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.action_import_model_file))
        }
    }
}

@Composable
private fun ImportingActions(progress: Float, onCancel: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.model_importing_format, (progress * 100).toInt()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Icon(
                imageVector = Icons.Rounded.Sync,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.action_cancel)) }
    }
}

@Composable
private fun ReadyActions(
    isSelected: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
) {
    var isDeleteDialogVisible by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (!isSelected) {
            Button(
                onClick = onSelect,
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.action_set_as_active))
            }
        } else {
            Text(
                text = stringResource(R.string.model_active_label),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .weight(1f)
                    .align(Alignment.CenterVertically),
            )
        }
        OutlinedButton(
            onClick = { isDeleteDialogVisible = true },
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.error,
            ),
        ) {
            Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.cd_delete_model))
        }
    }

    if (isDeleteDialogVisible) {
        DeleteConfirmDialog(
            onConfirm = {
                onDelete()
                isDeleteDialogVisible = false
            },
            onDismiss = { isDeleteDialogVisible = false }
        )
    }
}

@Composable
private fun DeleteConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_delete_title)) },
        text = { Text(stringResource(R.string.dialog_delete_message)) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Text(stringResource(R.string.action_delete))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

private val previewModelSmall = ModelInfo(
    id = ModelId.PARAKEET_V3,
    displayName = "Parakeet-V3 (Default)",
    description = "Fast and compact English on-device ASR. Recommended for most devices.",
    approximateSizeMb = 700,
    archiveUrl = "https://example.com/model.zip",
    files = listOf(ModelFile("model.onnx")),
)

private val previewModelLarge = ModelInfo(
    id = ModelId.WHISPER_SMALL,
    displayName = "Whisper Large-v3 Turbo (INT8)",
    description = "OpenAI Whisper Large-v3 with a turbo decoder. Multilingual, INT8 (~1.1 GB).",
    approximateSizeMb = 1_037,
    archiveUrl = "https://example.com/model.zip",
    files = listOf(ModelFile("encoder.onnx"), ModelFile("decoder.onnx")),
)

@Preview(showBackground = true, name = "Model Screen · Mixed States")
@Composable
private fun ModelListContentPreview() {
    OutspokeTheme {
        ModelListContent(
            modelStates = mapOf(
                ModelId.PARAKEET_V3 to ModelState.Ready,
                ModelId.WHISPER_SMALL to ModelState.NotDownloaded,
            ),
            selectedModel = ModelId.PARAKEET_V3,
            onImport = { _, _ -> }, onNoBrowser = {}, onCancel = {}, onDelete = {}, onSelect = {},
        )
    }
}

@Preview(showBackground = true, name = "Model Card · Not Installed")
@Composable
private fun ModelCardNotInstalledPreview() {
    OutspokeTheme {
        ModelCard(
            modelInfo = previewModelSmall,
            state = ModelState.NotDownloaded,
            isSelected = false,
            onImport = {}, onNoBrowser = {}, onCancel = {}, onDelete = {}, onSelect = {},
        )
    }
}

@Preview(showBackground = true, name = "Model Card · Importing 45%")
@Composable
private fun ModelCardImportingPreview() {
    OutspokeTheme {
        ModelCard(
            modelInfo = previewModelLarge,
            state = ModelState.Importing(0.45f),
            isSelected = false,
            onImport = {}, onNoBrowser = {}, onCancel = {}, onDelete = {}, onSelect = {},
        )
    }
}

@Preview(showBackground = true, name = "Model Card · Ready (selected)")
@Composable
private fun ModelCardReadySelectedPreview() {
    OutspokeTheme {
        ModelCard(
            modelInfo = previewModelSmall,
            state = ModelState.Ready,
            isSelected = true,
            onImport = {}, onNoBrowser = {}, onCancel = {}, onDelete = {}, onSelect = {},
        )
    }
}

@Preview(showBackground = true, name = "Action · Ready (not selected)")
@Composable
private fun ReadyActionsNotSelectedPreview() {
    OutspokeTheme { ReadyActions(isSelected = false, onSelect = {}, onDelete = {}) }
}

@Preview(showBackground = true, name = "Delete Confirm Dialog")
@Composable
private fun DeleteConfirmDialogPreview() {
    OutspokeTheme { DeleteConfirmDialog(onConfirm = {}, onDismiss = {}) }
}

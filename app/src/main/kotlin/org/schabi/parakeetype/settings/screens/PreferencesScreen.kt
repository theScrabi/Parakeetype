package org.schabi.parakeetype.settings.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import org.schabi.parakeetype.R
import org.schabi.parakeetype.settings.preferences.PreferencesViewModel
import org.schabi.parakeetype.ui.theme.ParakeetypeTheme

/**
 * Category 1 — Microphone, Trigger, Immediate Mode, Delete Button & Keyboard Position.
 *
 * Microphone calibration entry point, the recording trigger mode, immediate mode
 * (listen on switching to the keyboard, switch back afterwards), the
 * behaviour of the keyboard's delete (trash) button, and where the keyboard
 * controls sit in portrait and landscape.
 * Backed by [PreferencesViewModel] / DataStore; settings persist across
 * process restarts.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InputPreferencesScreen(
    viewModel: PreferencesViewModel = viewModel(),
    onNavigateToCalibration: () -> Unit = {},
) {
    val triggerMode by viewModel.triggerMode.collectAsState()
    val immediateMode by viewModel.immediateMode.collectAsState()
    val deleteButtonMode by viewModel.deleteButtonMode.collectAsState()
    val rawMicCapture by viewModel.rawMicCapture.collectAsState()
    val positionPortrait by viewModel.keyboardPositionPortrait.collectAsState()
    val positionLandscape by viewModel.keyboardPositionLandscape.collectAsState()

    PreferencesColumn {
        MicSection(
            onNavigateToCalibration = onNavigateToCalibration,
            rawMicCapture = rawMicCapture,
            onRawMicCaptureChange = viewModel::setRawMicCapture,
        )
        HorizontalDivider()
        TriggerModeSection(
            triggerMode = triggerMode,
            onTriggerModeChange = viewModel::setTriggerMode,
        )
        HorizontalDivider()
        ImmediateModeSection(
            immediateMode = immediateMode,
            onImmediateModeChange = viewModel::setImmediateMode,
        )
        HorizontalDivider()
        DeleteButtonSection(
            deleteButtonMode = deleteButtonMode,
            onDeleteButtonModeChange = viewModel::setDeleteButtonMode,
        )
        HorizontalDivider()
        KeyboardPositionSection(
            positionPortrait = positionPortrait,
            positionLandscape = positionLandscape,
            onPositionPortraitChange = viewModel::setKeyboardPositionPortrait,
            onPositionLandscapeChange = viewModel::setKeyboardPositionLandscape,
        )
    }
}

/**
 * Category 2 — Speech Processing.
 *
 * Voice activity detection, transcript post-processing, and keeping the model loaded
 * across keyboard switches. Backed by [PreferencesViewModel] / DataStore.
 */
@Composable
fun SpeechPreferencesScreen(
    viewModel: PreferencesViewModel = viewModel(),
) {
    val vadSensitivity by viewModel.vadSensitivity.collectAsState()
    val postprocessingEnabled by viewModel.postprocessingEnabled.collectAsState()
    val keepModelLoaded by viewModel.keepModelLoaded.collectAsState()

    // The keep-loaded foreground service must show a notification; ask for the permission
    // (API 33+) when the user turns the feature on. Denial is fine — the service still runs,
    // Android just hides the notification.
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    val onKeepModelLoadedChange: (Boolean) -> Unit = { enabled ->
        if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        viewModel.setKeepModelLoaded(enabled)
    }

    PreferencesColumn {
        VadSection(
            vadSensitivity = vadSensitivity,
            onVadSensitivityChange = viewModel::setVadSensitivity,
        )
        HorizontalDivider()
        PostprocessingSection(
            postprocessingEnabled = postprocessingEnabled,
            onPostprocessingChange = viewModel::setPostprocessingEnabled,
        )
        HorizontalDivider()
        KeepModelLoadedSection(
            keepModelLoaded = keepModelLoaded,
            onKeepModelLoadedChange = onKeepModelLoadedChange,
        )
    }
}

/**
 * Category 3 — Tools.
 *
 * The pipeline diagnostics toggle.
 * Backed by [PreferencesViewModel] / DataStore.
 */
@Composable
fun ToolsPreferencesScreen(
    viewModel: PreferencesViewModel = viewModel(),
) {
    val showPipelineDiagnostics by viewModel.showPipelineDiagnostics.collectAsState()

    PreferencesColumn {
        DiagnosticsSection(
            showPipelineDiagnostics = showPipelineDiagnostics,
            onShowPipelineDiagnosticsChange = viewModel::setShowPipelineDiagnostics,
        )
    }
}

/** Shared scroll container for the category preference screens. */
@Composable
private fun PreferencesColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        content()
    }
}

@Composable
private fun MicSection(
    onNavigateToCalibration: () -> Unit,
    rawMicCapture: Boolean,
    onRawMicCaptureChange: (Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.pref_mic_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(R.string.pref_mic_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = onNavigateToCalibration,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.pref_mic_calibrate))
        }

        Text(
            text = stringResource(R.string.pref_raw_mic_title),
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            text = stringResource(R.string.pref_raw_mic_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = if (rawMicCapture) stringResource(R.string.state_enabled)
                else stringResource(R.string.state_disabled),
                style = MaterialTheme.typography.bodyMedium,
            )
            Switch(
                checked = rawMicCapture,
                onCheckedChange = onRawMicCaptureChange,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TriggerModeSection(
    triggerMode: String,
    onTriggerModeChange: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.pref_trigger_mode_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(R.string.pref_trigger_mode_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = triggerMode == "HOLD",
                onClick = { onTriggerModeChange("HOLD") },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            ) {
                Text(stringResource(R.string.pref_trigger_hold))
            }
            SegmentedButton(
                selected = triggerMode == "TAP_TOGGLE",
                onClick = { onTriggerModeChange("TAP_TOGGLE") },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            ) {
                Text(stringResource(R.string.pref_trigger_tap_toggle))
            }
        }
    }
}

@Composable
private fun ImmediateModeSection(
    immediateMode: Boolean,
    onImmediateModeChange: (Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.pref_immediate_mode_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(R.string.pref_immediate_mode_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = if (immediateMode) stringResource(R.string.state_enabled)
                else stringResource(R.string.state_disabled),
                style = MaterialTheme.typography.bodyMedium,
            )
            Switch(
                checked = immediateMode,
                onCheckedChange = onImmediateModeChange,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeleteButtonSection(
    deleteButtonMode: String,
    onDeleteButtonModeChange: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.pref_delete_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(R.string.pref_delete_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = deleteButtonMode == "DELETE_ALL",
                onClick = { onDeleteButtonModeChange("DELETE_ALL") },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            ) {
                Text(stringResource(R.string.pref_delete_all))
            }
            SegmentedButton(
                selected = deleteButtonMode == "DELETE_LAST_SENTENCE",
                onClick = { onDeleteButtonModeChange("DELETE_LAST_SENTENCE") },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            ) {
                Text(stringResource(R.string.pref_delete_last_sentence))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KeyboardPositionSection(
    positionPortrait: String,
    positionLandscape: String,
    onPositionPortraitChange: (String) -> Unit,
    onPositionLandscapeChange: (String) -> Unit,
) {
    val portraitOptions = listOf(
        "LEFT" to R.string.pref_position_left,
        "CENTER" to R.string.pref_position_center,
        "RIGHT" to R.string.pref_position_right,
    )
    // Landscape is always docked to an edge so the controls stay within thumb reach.
    val landscapeOptions = listOf(
        "LEFT" to R.string.pref_position_left,
        "RIGHT" to R.string.pref_position_right,
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.pref_position_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(R.string.pref_position_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PositionChoiceRow(
            label = stringResource(R.string.pref_position_portrait),
            options = portraitOptions,
            selected = positionPortrait,
            onSelect = onPositionPortraitChange,
        )
        PositionChoiceRow(
            label = stringResource(R.string.pref_position_landscape),
            options = landscapeOptions,
            selected = positionLandscape,
            onSelect = onPositionLandscapeChange,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PositionChoiceRow(
    label: String,
    options: List<Pair<String, Int>>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Text(text = label, style = MaterialTheme.typography.titleSmall)
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (value, labelRes) ->
            SegmentedButton(
                selected = selected == value,
                onClick = { onSelect(value) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
            ) {
                Text(stringResource(labelRes))
            }
        }
    }
}

@Composable
private fun VadSection(
    vadSensitivity: Boolean,
    onVadSensitivityChange: (Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.pref_vad_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(R.string.pref_vad_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = if (vadSensitivity) stringResource(R.string.state_enabled)
                else stringResource(R.string.state_disabled),
                style = MaterialTheme.typography.bodyMedium,
            )
            Switch(
                checked = vadSensitivity,
                onCheckedChange = onVadSensitivityChange,
            )
        }
    }
}

@Composable
private fun PostprocessingSection(
    postprocessingEnabled: Boolean,
    onPostprocessingChange: (Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.pref_postprocessing_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(R.string.pref_postprocessing_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = if (postprocessingEnabled) stringResource(R.string.state_enabled)
                else stringResource(R.string.pref_postprocessing_disabled_raw),
                style = MaterialTheme.typography.bodyMedium,
            )
            Switch(
                checked = postprocessingEnabled,
                onCheckedChange = onPostprocessingChange,
            )
        }
    }
}

@Composable
private fun KeepModelLoadedSection(
    keepModelLoaded: Boolean,
    onKeepModelLoadedChange: (Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.pref_keep_loaded_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(R.string.pref_keep_loaded_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = if (keepModelLoaded) stringResource(R.string.state_enabled)
                else stringResource(R.string.state_disabled),
                style = MaterialTheme.typography.bodyMedium,
            )
            Switch(
                checked = keepModelLoaded,
                onCheckedChange = onKeepModelLoadedChange,
            )
        }
    }
}

@Composable
private fun DiagnosticsSection(
    showPipelineDiagnostics: Boolean,
    onShowPipelineDiagnosticsChange: (Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.pref_diagnostics_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(R.string.pref_diagnostics_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = if (showPipelineDiagnostics) stringResource(R.string.pref_diagnostics_visible)
                else stringResource(R.string.pref_diagnostics_hidden),
                style = MaterialTheme.typography.bodyMedium,
            )
            Switch(
                checked = showPipelineDiagnostics,
                onCheckedChange = onShowPipelineDiagnosticsChange,
            )
        }
    }
}

@Preview(showBackground = true, name = "Prefs · Microphone")
@Composable
private fun MicSectionPreview() {
    ParakeetypeTheme {
        PreferencesColumn {
            MicSection(onNavigateToCalibration = {}, rawMicCapture = false, onRawMicCaptureChange = {})
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview(showBackground = true, name = "Prefs · Trigger Mode (Hold)")
@Composable
private fun TriggerModeSectionHoldPreview() {
    ParakeetypeTheme {
        PreferencesColumn {
            TriggerModeSection(triggerMode = "HOLD", onTriggerModeChange = {})
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview(showBackground = true, name = "Prefs · Trigger Mode (Tap Toggle)")
@Composable
private fun TriggerModeSectionTapTogglePreview() {
    ParakeetypeTheme {
        PreferencesColumn {
            TriggerModeSection(triggerMode = "TAP_TOGGLE", onTriggerModeChange = {})
        }
    }
}

@Preview(showBackground = true, name = "Prefs · Immediate Mode")
@Composable
private fun ImmediateModeSectionPreview() {
    ParakeetypeTheme {
        PreferencesColumn {
            ImmediateModeSection(immediateMode = true, onImmediateModeChange = {})
        }
    }
}

@Preview(showBackground = true, name = "Prefs · Keyboard Position")
@Composable
private fun KeyboardPositionSectionPreview() {
    ParakeetypeTheme {
        PreferencesColumn {
            KeyboardPositionSection(
                positionPortrait = "CENTER",
                positionLandscape = "RIGHT",
                onPositionPortraitChange = {},
                onPositionLandscapeChange = {},
            )
        }
    }
}

@Preview(showBackground = true, name = "Prefs · VAD Enabled")
@Composable
private fun VadSectionPreview() {
    ParakeetypeTheme {
        PreferencesColumn {
            VadSection(vadSensitivity = true, onVadSensitivityChange = {})
        }
    }
}

@Preview(showBackground = true, name = "Prefs · Post-Processing Disabled")
@Composable
private fun PostprocessingSectionPreview() {
    ParakeetypeTheme {
        PreferencesColumn {
            PostprocessingSection(postprocessingEnabled = false, onPostprocessingChange = {})
        }
    }
}

@Preview(showBackground = true, name = "Prefs · Keep Model Loaded")
@Composable
private fun KeepModelLoadedSectionPreview() {
    ParakeetypeTheme {
        PreferencesColumn {
            KeepModelLoadedSection(keepModelLoaded = true, onKeepModelLoadedChange = {})
        }
    }
}

@Preview(showBackground = true, name = "Prefs · Diagnostics Visible")
@Composable
private fun DiagnosticsSectionPreview() {
    ParakeetypeTheme {
        PreferencesColumn {
            DiagnosticsSection(showPipelineDiagnostics = true, onShowPipelineDiagnosticsChange = {})
        }
    }
}

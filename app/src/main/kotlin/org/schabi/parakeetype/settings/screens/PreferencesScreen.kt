package org.schabi.parakeetype.settings.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.viewmodel.compose.viewModel
import org.schabi.parakeetype.R
import org.schabi.parakeetype.settings.preferences.PreferencesViewModel
import org.schabi.parakeetype.ui.theme.ParakeetypeTheme

/**
 * Category 1 — Microphone, Trigger, Instant Mode, Delete Button & Keyboard Position.
 *
 * Microphone calibration entry point, the recording trigger mode, instant mode
 * (listen on switching to the keyboard, switch back afterwards), the
 * behaviour of the keyboard's delete (trash) button, and where the keyboard
 * controls sit in portrait and landscape.
 * Backed by [PreferencesViewModel] / DataStore; settings persist across
 * process restarts.
 */
@Composable
fun InputPreferencesScreen(
    viewModel: PreferencesViewModel = viewModel(),
    onNavigateToCalibration: () -> Unit = {},
) {
    val triggerMode by viewModel.triggerMode.collectAsState()
    val instantMode by viewModel.instantMode.collectAsState()
    val deleteButtonMode by viewModel.deleteButtonMode.collectAsState()
    val rawMicCapture by viewModel.rawMicCapture.collectAsState()
    val positionPortrait by viewModel.keyboardPositionPortrait.collectAsState()
    val positionLandscape by viewModel.keyboardPositionLandscape.collectAsState()
    val leftHandedMode by viewModel.leftHandedMode.collectAsState()

    SettingsColumn {
        SectionHeader(stringResource(R.string.pref_mic_title))
        SettingsGroup(
            listOf(
                {
                    SettingsItem(
                        title = stringResource(R.string.pref_mic_calibrate),
                        subtitle = stringResource(R.string.pref_mic_subtitle),
                        shape = it,
                        onClick = onNavigateToCalibration,
                    )
                },
                {
                    SwitchItem(
                        title = stringResource(R.string.pref_raw_mic_title),
                        subtitle = stringResource(R.string.pref_raw_mic_subtitle),
                        checked = rawMicCapture,
                        onCheckedChange = viewModel::setRawMicCapture,
                        shape = it,
                    )
                },
            ),
        )
        GroupSpacer()
        SettingsGroup(
            listOf(
                {
                    ChoiceItem(
                        title = stringResource(R.string.pref_trigger_mode_title),
                        subtitle = stringResource(R.string.pref_trigger_mode_subtitle),
                        options = listOf(
                            "HOLD" to stringResource(R.string.pref_trigger_hold),
                            "TAP_TOGGLE" to stringResource(R.string.pref_trigger_tap_toggle),
                        ),
                        selected = triggerMode,
                        onSelect = viewModel::setTriggerMode,
                        shape = it,
                    )
                },
                {
                    SwitchItem(
                        title = stringResource(R.string.pref_instant_mode_title),
                        subtitle = stringResource(R.string.pref_instant_mode_subtitle),
                        checked = instantMode,
                        onCheckedChange = viewModel::setInstantMode,
                        shape = it,
                    )
                },
                {
                    ChoiceItem(
                        title = stringResource(R.string.pref_delete_title),
                        subtitle = stringResource(R.string.pref_delete_subtitle),
                        options = listOf(
                            "DELETE_ALL" to stringResource(R.string.pref_delete_all),
                            "DELETE_LAST_SENTENCE" to stringResource(R.string.pref_delete_last_sentence),
                        ),
                        selected = deleteButtonMode,
                        onSelect = viewModel::setDeleteButtonMode,
                        shape = it,
                    )
                },
            ),
        )
        GroupSpacer()
        SettingsGroup(
            listOf(
                {
                    KeyboardPositionItem(
                        positionPortrait = positionPortrait,
                        positionLandscape = positionLandscape,
                        onPositionPortraitChange = viewModel::setKeyboardPositionPortrait,
                        onPositionLandscapeChange = viewModel::setKeyboardPositionLandscape,
                        shape = it,
                    )
                },
                {
                    SwitchItem(
                        title = stringResource(R.string.pref_left_handed_title),
                        subtitle = stringResource(R.string.pref_left_handed_subtitle),
                        checked = leftHandedMode,
                        onCheckedChange = viewModel::setLeftHandedMode,
                        shape = it,
                    )
                },
            ),
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

    SettingsColumn {
        GroupSpacer()
        SettingsGroup(
            listOf(
                {
                    SwitchItem(
                        title = stringResource(R.string.pref_vad_title),
                        subtitle = stringResource(R.string.pref_vad_subtitle),
                        checked = vadSensitivity,
                        onCheckedChange = viewModel::setVadSensitivity,
                        shape = it,
                    )
                },
                {
                    SwitchItem(
                        title = stringResource(R.string.pref_postprocessing_title),
                        subtitle = stringResource(R.string.pref_postprocessing_subtitle),
                        checked = postprocessingEnabled,
                        onCheckedChange = viewModel::setPostprocessingEnabled,
                        shape = it,
                    )
                },
                {
                    SwitchItem(
                        title = stringResource(R.string.pref_keep_loaded_title),
                        subtitle = stringResource(R.string.pref_keep_loaded_subtitle),
                        checked = keepModelLoaded,
                        onCheckedChange = onKeepModelLoadedChange,
                        shape = it,
                    )
                },
            ),
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

    SettingsColumn {
        GroupSpacer()
        SettingsGroup(
            listOf(
                {
                    SwitchItem(
                        title = stringResource(R.string.pref_diagnostics_title),
                        subtitle = stringResource(R.string.pref_diagnostics_subtitle),
                        checked = showPipelineDiagnostics,
                        onCheckedChange = viewModel::setShowPipelineDiagnostics,
                        shape = it,
                    )
                },
            ),
        )
    }
}

/**
 * Where the keyboard controls sit: a choice for portrait and one for landscape (always docked
 * to an edge there, so the controls stay within thumb reach).
 */
@Composable
private fun KeyboardPositionItem(
    positionPortrait: String,
    positionLandscape: String,
    onPositionPortraitChange: (String) -> Unit,
    onPositionLandscapeChange: (String) -> Unit,
    shape: Shape,
) {
    val left = "LEFT" to stringResource(R.string.pref_position_left)
    val center = "CENTER" to stringResource(R.string.pref_position_center)
    val right = "RIGHT" to stringResource(R.string.pref_position_right)
    SettingsCard(shape) {
        Column {
            ItemTitle(stringResource(R.string.pref_position_title))
            ItemSummary(stringResource(R.string.pref_position_subtitle))
        }
        Text(
            text = stringResource(R.string.pref_position_portrait),
            style = MaterialTheme.typography.titleSmall,
        )
        RadioChoice(listOf(left, center, right), positionPortrait, onPositionPortraitChange)
        Text(
            text = stringResource(R.string.pref_position_landscape),
            style = MaterialTheme.typography.titleSmall,
        )
        RadioChoice(listOf(left, right), positionLandscape, onPositionLandscapeChange)
    }
}

@Preview(showBackground = true, name = "Prefs · Keyboard Position")
@Composable
private fun KeyboardPositionItemPreview() {
    ParakeetypeTheme {
        KeyboardPositionItem(
            positionPortrait = "CENTER",
            positionLandscape = "RIGHT",
            onPositionPortraitChange = {},
            onPositionLandscapeChange = {},
            shape = groupItemShape(0, 1),
        )
    }
}

@Preview(showBackground = true, name = "Prefs · Switch")
@Composable
private fun SwitchItemPreview() {
    ParakeetypeTheme {
        SwitchItem(
            title = "Voice Activity Detection (VAD)",
            subtitle = "Filters out silence before transcription.",
            checked = true,
            onCheckedChange = {},
            shape = groupItemShape(0, 1),
        )
    }
}

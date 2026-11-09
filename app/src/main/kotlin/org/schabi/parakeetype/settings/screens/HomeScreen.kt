package org.schabi.parakeetype.settings.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.FindReplace
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Warning
import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.schabi.parakeetype.R
import org.schabi.parakeetype.audio.PermissionHelper
import org.schabi.parakeetype.inference.InferenceService
import org.schabi.parakeetype.settings.model.ModelRegistry
import org.schabi.parakeetype.settings.model.ModelStorageManager
import org.schabi.parakeetype.ui.theme.ParakeetypeTheme

/**
 * The home / dashboard screen of the Parakeetype companion app.
 *
 * Shows the setup steps:
 *  1. IME enabled in system settings
 *  2. Microphone permission granted
 *  3. Model installed
 *  4. Parakeetype selected as the system's voice input (optional, for other apps)
 *
 * All statuses are re-evaluated every time the screen comes into the foreground
 * so that changes made in system Settings or the file system are reflected immediately.
 */
@Composable
fun HomeScreen(
    onNavigateToModel: () -> Unit,
    onNavigateToInput: () -> Unit,
    onNavigateToSpeech: () -> Unit,
    onNavigateToTools: () -> Unit,
    onNavigateToReplacements: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    // Initialise from the live sources of truth (all checks are
    // synchronous and cheap) rather than from defaults. After a process-death
    // relaunch the ON_RESUME event may already have fired before this observer
    // is registered, so relying on it alone leaves the screen stuck on the
    // unconfigured view.
    var isImeEnabled by remember { mutableStateOf(isParakeetypeImeEnabled(context)) }
    var hasMicPermission by remember { mutableStateOf(PermissionHelper.hasRecordPermission(context)) }
    var isModelReady by remember { mutableStateOf(ModelRegistry.all.any { ModelStorageManager.isModelReady(context, it) }) }
    var isDefaultRecognizer by remember { mutableStateOf(isParakeetypeDefaultRecognizer(context)) }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasMicPermission = granted
        if (granted && ModelStorageManager.isModelReady(context)) {
            context.startForegroundService(Intent(context, InferenceService::class.java))
        }
    }

    // Refresh all statuses on every ON_RESUME - catches changes made in other apps/settings.
    DisposableEffect(lifecycle) {
        val observer = object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) {
                isImeEnabled = isParakeetypeImeEnabled(context)
                hasMicPermission = PermissionHelper.hasRecordPermission(context)
                isModelReady = ModelRegistry.all.any { ModelStorageManager.isModelReady(context, it) }
                isDefaultRecognizer = isParakeetypeDefaultRecognizer(context)
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    HomeScreenContent(
        isImeEnabled = isImeEnabled,
        hasMicPermission = hasMicPermission,
        isModelReady = isModelReady,
        isDefaultRecognizer = isDefaultRecognizer,
        onOpenImeSettings = { context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) },
        onRequestMicPermission = { micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
        onOpenVoiceInputSettings = { openVoiceInputSettings(context) },
        onNavigateToModel = onNavigateToModel,
        onNavigateToInput = onNavigateToInput,
        onNavigateToSpeech = onNavigateToSpeech,
        onNavigateToTools = onNavigateToTools,
        onNavigateToReplacements = onNavigateToReplacements,
    )
}

@Composable
private fun HomeScreenContent(
    isImeEnabled: Boolean,
    hasMicPermission: Boolean,
    isModelReady: Boolean,
    isDefaultRecognizer: Boolean,
    onOpenImeSettings: () -> Unit,
    onRequestMicPermission: () -> Unit,
    onOpenVoiceInputSettings: () -> Unit,
    onNavigateToModel: () -> Unit,
    onNavigateToInput: () -> Unit,
    onNavigateToSpeech: () -> Unit,
    onNavigateToTools: () -> Unit,
    onNavigateToReplacements: () -> Unit,
) {
    SettingsColumn {
        SectionHeader(stringResource(R.string.home_section_setup))
        SettingsGroup(
            listOf(
                // 1. IME enabled
                {
                    SettingsItem(
                        icon = if (isImeEnabled) Icons.Rounded.Keyboard else Icons.Rounded.Warning,
                        title = if (isImeEnabled) stringResource(R.string.home_ime_enabled)
                        else stringResource(R.string.home_ime_not_enabled),
                        subtitle = if (isImeEnabled) stringResource(R.string.home_ime_enabled_subtitle)
                        else stringResource(R.string.home_ime_not_enabled_subtitle),
                        onClickLabel = stringResource(R.string.action_open_settings),
                        onClick = if (!isImeEnabled) onOpenImeSettings else null,
                        shape = it,
                    )
                },
                // 2. Microphone permission
                {
                    SettingsItem(
                        icon = if (hasMicPermission) Icons.Rounded.Mic else Icons.Rounded.MicOff,
                        title = if (hasMicPermission) stringResource(R.string.home_mic_granted)
                        else stringResource(R.string.home_mic_required),
                        subtitle = if (hasMicPermission) stringResource(R.string.home_mic_granted_subtitle)
                        else stringResource(R.string.home_mic_required_subtitle),
                        onClickLabel = stringResource(R.string.action_grant_permission),
                        onClick = if (!hasMicPermission) onRequestMicPermission else null,
                        shape = it,
                    )
                },
                // 3. Model installed - always clickable so the model screen stays reachable
                {
                    SettingsItem(
                        icon = if (isModelReady) Icons.Rounded.CheckCircle else Icons.Rounded.CloudDownload,
                        title = if (isModelReady) stringResource(R.string.home_model_ready)
                        else stringResource(R.string.home_model_not_downloaded),
                        subtitle = if (isModelReady) stringResource(R.string.home_model_ready_subtitle)
                        else stringResource(R.string.home_model_not_downloaded_subtitle),
                        onClickLabel = if (isModelReady) stringResource(R.string.action_manage)
                        else stringResource(R.string.action_download),
                        onClick = onNavigateToModel,
                        shape = it,
                    )
                },
                // 4. System voice input - optional, lets other apps use Parakeetype for speech-to-text
                {
                    SettingsItem(
                        icon = if (isDefaultRecognizer) Icons.Rounded.RecordVoiceOver else Icons.Rounded.Warning,
                        title = if (isDefaultRecognizer) stringResource(R.string.home_recognizer_default)
                        else stringResource(R.string.home_recognizer_not_default),
                        subtitle = when {
                            isDefaultRecognizer -> stringResource(R.string.home_recognizer_default_subtitle)
                            VOICE_INPUT_IN_LANGUAGE_SETTINGS -> stringResource(R.string.home_recognizer_not_default_speech_subtitle)
                            else -> stringResource(R.string.home_recognizer_not_default_subtitle)
                        },
                        onClickLabel = stringResource(R.string.action_open_settings),
                        onClick = if (!isDefaultRecognizer) onOpenVoiceInputSettings else null,
                        shape = it,
                    )
                },
            ),
        )

        SectionHeader(stringResource(R.string.home_section_configuration))
        SettingsGroup(
            listOf(
                {
                    SettingsItem(
                        icon = Icons.Rounded.Mic,
                        title = stringResource(R.string.home_pref_input),
                        shape = it,
                        onClick = onNavigateToInput,
                    )
                },
                {
                    SettingsItem(
                        icon = Icons.Rounded.Keyboard,
                        title = stringResource(R.string.home_pref_speech),
                        shape = it,
                        onClick = onNavigateToSpeech,
                    )
                },
                {
                    SettingsItem(
                        icon = Icons.Rounded.FindReplace,
                        title = stringResource(R.string.home_pref_replacements),
                        shape = it,
                        onClick = onNavigateToReplacements,
                    )
                },
                {
                    SettingsItem(
                        icon = Icons.Rounded.Settings,
                        title = stringResource(R.string.home_pref_tools),
                        shape = it,
                        onClick = onNavigateToTools,
                    )
                },
            ),
        )
    }
}

/** Returns `true` if the Parakeetype IME appears in the system's enabled-input-methods list. */
private fun isParakeetypeImeEnabled(context: Context): Boolean {
    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    return imm.enabledInputMethodList.any { it.packageName == context.packageName }
}

/**
 * Returns `true` if Parakeetype's `RecognitionService` is the system's voice input
 * (`Settings.Secure.voice_recognition_service`, hidden but readable). `false` if the
 * setting can't be read.
 */
private fun isParakeetypeDefaultRecognizer(context: Context): Boolean {
    val service = try {
        Settings.Secure.getString(context.contentResolver, "voice_recognition_service")
    } catch (e: SecurityException) {
        null
    }
    return service?.let { ComponentName.unflattenFromString(it) }?.packageName == context.packageName
}

/**
 * Since Android 14 the voice-input picker sits in Settings › Language › Speech, a fragment no
 * intent opens, and [Settings.ACTION_VOICE_INPUT_SETTINGS] shows the digital-assistant page
 * instead. There the home screen opens the language settings and its subtitle names the path.
 */
private val VOICE_INPUT_IN_LANGUAGE_SETTINGS = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

/**
 * Opens the settings page holding the system's voice-input picker. Some devices don't implement
 * that screen, so fall back to the Settings app itself.
 */
private fun openVoiceInputSettings(context: Context) {
    val actions = listOf(
        if (VOICE_INPUT_IN_LANGUAGE_SETTINGS) Settings.ACTION_LOCALE_SETTINGS
        else Settings.ACTION_VOICE_INPUT_SETTINGS,
        Settings.ACTION_SETTINGS,
    )
    for (action in actions) {
        try {
            context.startActivity(Intent(action))
            return
        } catch (e: ActivityNotFoundException) {
            // try the next one
        }
    }
}

@Preview(showBackground = true, name = "Home · Nothing Set Up")
@Composable
private fun HomeScreenNothingSetupPreview() {
    ParakeetypeTheme {
        HomeScreenContent(
            isImeEnabled = false,
            hasMicPermission = false,
            isModelReady = false,
            isDefaultRecognizer = false,
            onOpenImeSettings = {},
            onRequestMicPermission = {},
            onOpenVoiceInputSettings = {},
            onNavigateToModel = {},
            onNavigateToInput = {},
            onNavigateToSpeech = {},
            onNavigateToTools = {},
            onNavigateToReplacements = {},
        )
    }
}

@Preview(showBackground = true, name = "Home · All Ready")
@Composable
private fun HomeScreenAllReadyPreview() {
    ParakeetypeTheme {
        HomeScreenContent(
            isImeEnabled = true,
            hasMicPermission = true,
            isModelReady = true,
            isDefaultRecognizer = true,
            onOpenImeSettings = {},
            onRequestMicPermission = {},
            onOpenVoiceInputSettings = {},
            onNavigateToModel = {},
            onNavigateToInput = {},
            onNavigateToSpeech = {},
            onNavigateToTools = {},
            onNavigateToReplacements = {},
        )
    }
}

@Preview(showBackground = true, name = "Home · Partial Setup (IME done, no mic, no model)")
@Composable
private fun HomeScreenPartialSetupPreview() {
    ParakeetypeTheme {
        HomeScreenContent(
            isImeEnabled = true,
            hasMicPermission = false,
            isModelReady = false,
            isDefaultRecognizer = false,
            onOpenImeSettings = {},
            onRequestMicPermission = {},
            onOpenVoiceInputSettings = {},
            onNavigateToModel = {},
            onNavigateToInput = {},
            onNavigateToSpeech = {},
            onNavigateToTools = {},
            onNavigateToReplacements = {},
        )
    }
}

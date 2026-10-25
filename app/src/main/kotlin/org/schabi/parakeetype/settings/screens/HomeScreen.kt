package org.schabi.parakeetype.settings.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudDownload
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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
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
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.home_section_setup),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // 1. IME enabled
        StatusRow(
            icon = if (isImeEnabled) Icons.Rounded.Keyboard else Icons.Rounded.Warning,
            iconTint = if (isImeEnabled) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.error,
            title = if (isImeEnabled) stringResource(R.string.home_ime_enabled)
            else stringResource(R.string.home_ime_not_enabled),
            subtitle = if (isImeEnabled) stringResource(R.string.home_ime_enabled_subtitle)
            else stringResource(R.string.home_ime_not_enabled_subtitle),
            actionLabel = stringResource(R.string.action_open_settings),
            action = if (!isImeEnabled) onOpenImeSettings else null,
        )

        // 2. Microphone permission
        StatusRow(
            icon = if (hasMicPermission) Icons.Rounded.Mic else Icons.Rounded.MicOff,
            iconTint = if (hasMicPermission) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.error,
            title = if (hasMicPermission) stringResource(R.string.home_mic_granted)
            else stringResource(R.string.home_mic_required),
            subtitle = if (hasMicPermission) stringResource(R.string.home_mic_granted_subtitle)
            else stringResource(R.string.home_mic_required_subtitle),
            actionLabel = stringResource(R.string.action_grant_permission),
            action = if (!hasMicPermission) onRequestMicPermission else null,
        )

        // 3. Model installed - always show an action so the model screen stays reachable
        StatusRow(
            icon = if (isModelReady) Icons.Rounded.CheckCircle else Icons.Rounded.CloudDownload,
            iconTint = if (isModelReady) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.error,
            title = if (isModelReady) stringResource(R.string.home_model_ready)
            else stringResource(R.string.home_model_not_downloaded),
            subtitle = if (isModelReady) stringResource(R.string.home_model_ready_subtitle)
            else stringResource(R.string.home_model_not_downloaded_subtitle),
            actionLabel = if (isModelReady) stringResource(R.string.action_manage)
            else stringResource(R.string.action_download),
            action = onNavigateToModel,
        )

        // 4. System voice input - optional, lets other apps use Parakeetype for speech-to-text
        StatusRow(
            icon = if (isDefaultRecognizer) Icons.Rounded.RecordVoiceOver else Icons.Rounded.Warning,
            iconTint = if (isDefaultRecognizer) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.error,
            title = if (isDefaultRecognizer) stringResource(R.string.home_recognizer_default)
            else stringResource(R.string.home_recognizer_not_default),
            subtitle = when {
                isDefaultRecognizer -> stringResource(R.string.home_recognizer_default_subtitle)
                VOICE_INPUT_IN_LANGUAGE_SETTINGS -> stringResource(R.string.home_recognizer_not_default_speech_subtitle)
                else -> stringResource(R.string.home_recognizer_not_default_subtitle)
            },
            actionLabel = stringResource(R.string.action_open_settings),
            action = if (!isDefaultRecognizer) onOpenVoiceInputSettings else null,
        )

        Spacer(Modifier.height(8.dp))
        HorizontalDivider()
        Spacer(Modifier.height(4.dp))

        Text(
            text = stringResource(R.string.home_section_configuration),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        ConfigurationButton(
            icon = Icons.Rounded.Mic,
            label = stringResource(R.string.home_pref_input),
            onClick = onNavigateToInput,
        )
        ConfigurationButton(
            icon = Icons.Rounded.Keyboard,
            label = stringResource(R.string.home_pref_speech),
            onClick = onNavigateToSpeech,
        )
        ConfigurationButton(
            icon = Icons.Rounded.Settings,
            label = stringResource(R.string.home_pref_tools),
            onClick = onNavigateToTools,
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

/** A labelled outlined button in the Configuration section of the home screen. */
@Composable
private fun ConfigurationButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
}

@Composable
private fun StatusRow(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    subtitle: String,
    actionLabel: String,
    action: (() -> Unit)?,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(32.dp),
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (action != null) {
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = action) { Text(actionLabel) }
            }
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
        )
    }
}

@Preview(showBackground = true, name = "Status Row · Done (no action)")
@Composable
private fun StatusRowDonePreview() {
    ParakeetypeTheme {
        StatusRow(
            icon = Icons.Rounded.CheckCircle,
            iconTint = Color.Unspecified,
            title = "Keyboard enabled",
            subtitle = "Parakeetype is in the keyboard list.",
            actionLabel = "Open Settings",
            action = null,
        )
    }
}

@Preview(showBackground = true, name = "Status Row · Pending (with action)")
@Composable
private fun StatusRowPendingPreview() {
    ParakeetypeTheme {
        StatusRow(
            icon = Icons.Rounded.Warning,
            iconTint = Color.Unspecified,
            title = "Keyboard not enabled",
            subtitle = "Add Parakeetype in system keyboard settings.",
            actionLabel = "Open Settings",
            action = {},
        )
    }
}


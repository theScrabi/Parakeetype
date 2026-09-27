package dev.brgr.outspoke.ui.keyboard.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.brgr.outspoke.R
import dev.brgr.outspoke.inference.PipelineDiagnostics
import dev.brgr.outspoke.ui.keyboard.KeyboardUiState
import dev.brgr.outspoke.ui.theme.MyIcons
import dev.brgr.outspoke.ui.theme.OutspokeKeyboardTheme

/**
 * Crossfades between the visual states driven by [uiState].
 *
 * - [KeyboardUiState.Idle]          → nothing (diagnostics badge if non-clean)
 * - [KeyboardUiState.Listening]     → nothing (the talk button and waveform show it)
 * - [KeyboardUiState.Processing]    → partial transcript text
 * - [KeyboardUiState.Transcribing]  → "Transcribing…" label (mic off, engine busy)
 * - [KeyboardUiState.Error]         → warning icon + error message + recovery action(s)
 * - [KeyboardUiState.EngineLoading] → loading message + "Open Outspoke" action
 * - [KeyboardUiState.NoSpeech]      → brief "didn't catch that" label
 *
 * For transient errors ([KeyboardUiState.ErrorReason.TranscriptionFailed],
 * [KeyboardUiState.ErrorReason.AudioCaptureFailed], [KeyboardUiState.ErrorReason.MicInitFailed])
 * a "Try again" button is shown alongside the error so the user can retry immediately without
 * leaving the keyboard. For errors that require external action (permission denied, engine load
 * failed) the "Open Outspoke" button is shown instead.
 *
 * @param diagnostics Pipeline counters from the most recent recording session. When non-clean
 *                    a compact summary (e.g. "2T · 1R") is shown in the idle state,
 *                    giving immediate visibility into whether any trims or alignment recoveries
 *                    fired - without opening logcat.
 * @param onOpenCompanionApp Called when the user taps the "Open Outspoke" action button shown
 *                           in [KeyboardUiState.Error] and [KeyboardUiState.EngineLoading] states.
 * @param onRetry Called when the user taps "Try again" for transient errors. Should trigger
 *                a new recording attempt and transition the UI back to [KeyboardUiState.Listening].
 */
@Composable
fun StatusIndicator(
    uiState: KeyboardUiState,
    modifier: Modifier = Modifier,
    diagnostics: PipelineDiagnostics = PipelineDiagnostics(),
    onOpenCompanionApp: (() -> Unit)? = null,
    onRetry: (() -> Unit)? = null,
) {
    AnimatedContent(
        targetState = uiState,
        transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(150)) },
        contentAlignment = Alignment.Center,
        label = "statusIndicatorContent",
        modifier = modifier,
    ) { state ->
        when (state) {
            is KeyboardUiState.Idle -> IdleIndicator(diagnostics = diagnostics)
            is KeyboardUiState.Listening -> Unit
            is KeyboardUiState.Processing -> ProcessingIndicator(partial = state.partial)
            is KeyboardUiState.Transcribing -> TranscribingIndicator()
            is KeyboardUiState.Error -> {
                val isTransient = state.reason in setOf(
                    KeyboardUiState.ErrorReason.TranscriptionFailed,
                    KeyboardUiState.ErrorReason.AudioCaptureFailed,
                    KeyboardUiState.ErrorReason.MicInitFailed,
                )
                ErrorIndicator(
                    message = localizedErrorMessage(state),
                    onOpenCompanionApp = if (isTransient) null else onOpenCompanionApp,
                    onRetry = if (isTransient) onRetry else null,
                )
            }

            is KeyboardUiState.EngineLoading -> EngineLoadingIndicator(
                message = localizedLoadingMessage(state),
                onOpenCompanionApp = onOpenCompanionApp,
            )

            is KeyboardUiState.NoSpeech -> NoSpeechIndicator()
        }
    }
}

/** Maps [KeyboardUiState.Error] to a localized, user-facing message string. */
@Composable
private fun localizedErrorMessage(state: KeyboardUiState.Error): String {
    val primary = stringResource(
        when (state.reason) {
            KeyboardUiState.ErrorReason.MicPermissionDenied -> R.string.status_error_mic_permission
            KeyboardUiState.ErrorReason.MicInitFailed -> R.string.status_error_mic_init
            KeyboardUiState.ErrorReason.TranscriptionFailed -> R.string.status_error_transcription
            KeyboardUiState.ErrorReason.AudioCaptureFailed -> R.string.status_error_audio_capture
            KeyboardUiState.ErrorReason.EngineLoadFailed -> R.string.status_error_engine_load
        }
    )
    return if (state.detail != null) "$primary: ${state.detail}" else primary
}

/** Maps [KeyboardUiState.EngineLoading] to a localized, user-facing message string. */
@Composable
private fun localizedLoadingMessage(state: KeyboardUiState.EngineLoading): String =
    stringResource(
        when (state.reason) {
            KeyboardUiState.LoadingReason.ModelNotDownloaded -> R.string.status_engine_model_missing
            KeyboardUiState.LoadingReason.EngineStarting -> R.string.status_engine_loading
        }
    )

@Composable
private fun IdleIndicator(diagnostics: PipelineDiagnostics = PipelineDiagnostics()) {
    if (!diagnostics.isClean) {
        Text(
            text = diagnostics.summary(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.tertiary,
            maxLines = 1,
        )
    }
}

@Composable
private fun ProcessingIndicator(partial: String) {
    if (partial.isNotEmpty()) {
        Text(
            text = partial,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
    }
}

/** Shown after the mic stops while the engine is still running its final inference pass. */
@Composable
private fun TranscribingIndicator() {
    Text(
        text = stringResource(R.string.status_transcribing),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
    )
}

/** Shown briefly when the model detected audio but couldn't resolve a word. */
@Composable
private fun NoSpeechIndicator() {
    Text(
        text = stringResource(R.string.status_no_speech),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
    )
}

@Composable
private fun ErrorIndicator(
    message: String,
    onOpenCompanionApp: (() -> Unit)? = null,
    onRetry: (() -> Unit)? = null,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = MyIcons.Warning,
                contentDescription = stringResource(R.string.cd_status_error),
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 2,
            )
        }
        if (onRetry != null) {
            Spacer(modifier = Modifier.height(2.dp))
            TextButton(
                onClick = onRetry,
                modifier = Modifier.height(36.dp),
            ) {
                Text(
                    text = stringResource(R.string.action_retry),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        } else if (onOpenCompanionApp != null) {
            Spacer(modifier = Modifier.height(2.dp))
            TextButton(
                onClick = onOpenCompanionApp,
                modifier = Modifier.height(36.dp),
            ) {
                Text(
                    text = stringResource(R.string.action_open_outspoke),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun EngineLoadingIndicator(
    message: String,
    onOpenCompanionApp: (() -> Unit)? = null,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
        )
        if (onOpenCompanionApp != null) {
            Spacer(modifier = Modifier.height(2.dp))
            TextButton(
                onClick = onOpenCompanionApp,
                modifier = Modifier.height(36.dp),
            ) {
                Text(
                    text = stringResource(R.string.action_open_outspoke),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF111111)
@Composable
private fun StatusProcessingPreview() {
    OutspokeKeyboardTheme {
        StatusIndicator(uiState = KeyboardUiState.Processing("The quick brown fox…"))
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF111111)
@Composable
private fun StatusTranscribingPreview() {
    OutspokeKeyboardTheme {
        StatusIndicator(uiState = KeyboardUiState.Transcribing)
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF111111)
@Composable
private fun StatusErrorPreview() {
    OutspokeKeyboardTheme {
        StatusIndicator(uiState = KeyboardUiState.Error(KeyboardUiState.ErrorReason.MicPermissionDenied))
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF111111)
@Composable
private fun StatusErrorTransientPreview() {
    OutspokeKeyboardTheme {
        StatusIndicator(
            uiState = KeyboardUiState.Error(KeyboardUiState.ErrorReason.TranscriptionFailed, detail = "ONNX error"),
            onRetry = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF111111)
@Composable
private fun StatusEngineLoadingPreview() {
    OutspokeKeyboardTheme {
        StatusIndicator(uiState = KeyboardUiState.EngineLoading(KeyboardUiState.LoadingReason.EngineStarting))
    }
}

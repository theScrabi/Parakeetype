package org.schabi.parakeetype.ui.keyboard.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Warning
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
import org.schabi.parakeetype.R
import org.schabi.parakeetype.inference.PipelineDiagnostics
import org.schabi.parakeetype.ui.keyboard.KeyboardUiState
import org.schabi.parakeetype.ui.theme.ParakeetypeKeyboardTheme

/**
 * Crossfades between the visual states driven by [uiState].
 *
 * - [KeyboardUiState.Idle]          → nothing (diagnostics badge if non-clean)
 * - [KeyboardUiState.Listening]     → nothing (the talk button shows it)
 * - [KeyboardUiState.Processing]    → nothing (the partial is already in the text field;
 *                                     this row only ever shows status messages)
 * - [KeyboardUiState.Transcribing]  → "Transcribing…" label (mic off, engine busy)
 * - [KeyboardUiState.Error]         → warning icon + error message + recovery action(s)
 * - [KeyboardUiState.EngineLoading] → loading / model-missing message (the "Open Parakeetype"
 *                                     key for a missing model lives in the keyboard's button row)
 * - [KeyboardUiState.NoSpeech]      → brief "didn't catch that" label
 *
 * For transient errors ([KeyboardUiState.ErrorReason.TranscriptionFailed],
 * [KeyboardUiState.ErrorReason.AudioCaptureFailed], [KeyboardUiState.ErrorReason.MicInitFailed])
 * a "Try again" button is shown alongside the error so the user can retry immediately without
 * leaving the keyboard. For errors that require external action (permission denied, engine load
 * failed) the "Open Parakeetype" button is shown instead.
 *
 * @param diagnostics Pipeline counters from the most recent recording session. When non-clean
 *                    a compact summary (e.g. "2T · 1R") is shown in the idle state,
 *                    giving immediate visibility into whether any trims or alignment recoveries
 *                    fired - without opening logcat.
 * @param onOpenCompanionApp Called when the user taps the "Open Parakeetype" action button shown
 *                           in [KeyboardUiState.Error] states.
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
        // Pure fade, no size animation.
        transitionSpec = { (fadeIn(tween(200)) togetherWith fadeOut(tween(150))).using(null) },
        contentAlignment = Alignment.CenterStart,
        // Key by state type: each new partial transcript is a new Processing(...) value;
        // without this every partial restarted the transition. Only real state changes
        // (listening → processing → transcribing …) fade.
        contentKey = { it::class },
        label = "statusIndicatorContent",
        modifier = modifier,
    ) { state ->
        when (state) {
            is KeyboardUiState.Idle -> IdleIndicator(diagnostics = diagnostics)
            is KeyboardUiState.Listening -> Unit
            is KeyboardUiState.Processing -> Unit
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

            is KeyboardUiState.EngineLoading -> EngineLoadingIndicator(message = localizedLoadingMessage(state))

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
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.tertiary,
            maxLines = 1,
        )
    }
}

/** Shown after the mic stops while the engine is still running its final inference pass. */
@Composable
private fun TranscribingIndicator() {
    Text(
        text = stringResource(R.string.status_transcribing),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
    )
}

/** Shown briefly when the model detected audio but couldn't resolve a word. */
@Composable
private fun NoSpeechIndicator() {
    Text(
        text = stringResource(R.string.status_no_speech),
        style = MaterialTheme.typography.bodyLarge,
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
                imageVector = Icons.Rounded.Warning,
                contentDescription = stringResource(R.string.cd_status_error),
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
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
                    style = MaterialTheme.typography.labelLarge,
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
                    text = stringResource(R.string.action_open_parakeetype),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun EngineLoadingIndicator(message: String) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF111111)
@Composable
private fun StatusTranscribingPreview() {
    ParakeetypeKeyboardTheme {
        StatusIndicator(uiState = KeyboardUiState.Transcribing)
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF111111)
@Composable
private fun StatusErrorPreview() {
    ParakeetypeKeyboardTheme {
        StatusIndicator(uiState = KeyboardUiState.Error(KeyboardUiState.ErrorReason.MicPermissionDenied))
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF111111)
@Composable
private fun StatusErrorTransientPreview() {
    ParakeetypeKeyboardTheme {
        StatusIndicator(
            uiState = KeyboardUiState.Error(KeyboardUiState.ErrorReason.TranscriptionFailed, detail = "ONNX error"),
            onRetry = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF111111)
@Composable
private fun StatusEngineLoadingPreview() {
    ParakeetypeKeyboardTheme {
        StatusIndicator(uiState = KeyboardUiState.EngineLoading(KeyboardUiState.LoadingReason.EngineStarting))
    }
}

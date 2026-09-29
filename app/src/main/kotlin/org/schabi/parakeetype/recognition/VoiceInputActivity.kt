package org.schabi.parakeetype.recognition

import android.Manifest
import android.app.Activity
import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material3.Button
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import org.schabi.parakeetype.R
import org.schabi.parakeetype.audio.PermissionHelper
import org.schabi.parakeetype.settings.SettingsActivity
import org.schabi.parakeetype.ui.theme.ParakeetypeTheme

private const val TAG = "VoiceInputActivity"

private sealed class VoiceInputUiState {
    /**
     * [level] is the microphone level in [0, 1]. While [modelLoading] the audio is buffered
     * until the model has loaded.
     */
    data class Listening(val level: Float = 0f, val modelLoading: Boolean = false) : VoiceInputUiState()
    data object Transcribing : VoiceInputUiState()
    data class Failed(@StringRes val message: Int, val canRetry: Boolean, val openApp: Boolean) : VoiceInputUiState()
}

/**
 * Handles [RecognizerIntent.ACTION_RECOGNIZE_SPEECH]: the "tap the mic" voice input other apps
 * start with `startActivityForResult`. Shows a small listening sheet, runs one
 * [RecognitionSession] and returns the text in [RecognizerIntent.EXTRA_RESULTS] (or sends it
 * to [RecognizerIntent.EXTRA_RESULTS_PENDINGINTENT]).
 */
class VoiceInputActivity : ComponentActivity() {

    private lateinit var inference: InferenceConnection
    private var session: RecognitionSession? = null
    private var uiState by mutableStateOf<VoiceInputUiState>(VoiceInputUiState.Listening())

    /** Result reported when the user closes the sheet: the last error, or cancelled. */
    private var closeResult = Activity.RESULT_CANCELED

    /** The permission dialog must not count as leaving the activity (see [onStop]). */
    private var awaitingPermission = false

    private val requestPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            awaitingPermission = false
            if (granted) startSession() else finishWith(RecognizerIntent.RESULT_AUDIO_ERROR)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        inference = InferenceConnection(this).also { it.bind() }
        val prompt = intent.getStringExtra(RecognizerIntent.EXTRA_PROMPT)

        enableEdgeToEdge()
        setContent {
            ParakeetypeTheme {
                VoiceInputSheet(
                    state = uiState,
                    prompt = prompt,
                    onDone = { session?.stop() },
                    onClose = { finishWith(closeResult) },
                    onRetry = ::startSession,
                    onOpenApp = {
                        startActivity(Intent(this, SettingsActivity::class.java))
                        finishWith(closeResult)
                    },
                )
            }
        }

        if (PermissionHelper.hasRecordPermission(this)) {
            startSession()
        } else {
            awaitingPermission = true
            requestPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    override fun onStop() {
        super.onStop()
        // Recording needs the activity in the foreground; leaving it cancels the input.
        if (!isFinishing && !isChangingConfigurations && !awaitingPermission) {
            finishWith(Activity.RESULT_CANCELED)
        }
    }

    override fun onDestroy() {
        session?.cancel()
        session = null
        inference.unbind()
        super.onDestroy()
    }

    private fun startSession() {
        session?.cancel()
        closeResult = Activity.RESULT_CANCELED
        uiState = VoiceInputUiState.Listening()
        session = RecognitionSession(
            audioContext = this,
            inference = inference,
            // The sheet closes as soon as the result is in, so partial text is never shown.
            options = RecognitionOptions.from(intent).copy(partialResults = false, segmented = false),
            listener = SessionListener(),
            scope = lifecycleScope,
        ).also { it.start() }
    }

    private inner class SessionListener : RecognitionSession.Listener {
        override fun onModelLoading(loading: Boolean) {
            val listening = uiState as? VoiceInputUiState.Listening ?: return
            uiState = listening.copy(modelLoading = loading)
        }

        override fun onReadyForSpeech() = Unit
        override fun onBeginningOfSpeech() = Unit

        override fun onRmsChanged(rmsdB: Float) {
            val listening = uiState as? VoiceInputUiState.Listening ?: return
            uiState = listening.copy(level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
        }

        override fun onPartialResult(text: String) = Unit
        override fun onSegmentResult(text: String, confidence: Float) = Unit

        override fun onEndOfSpeech() {
            if (uiState is VoiceInputUiState.Listening) uiState = VoiceInputUiState.Transcribing
        }

        override fun onResult(text: String, confidence: Float) {
            session = null
            deliver(text, confidence)
        }

        override fun onEndOfSegmentedSession() = Unit

        override fun onError(error: Int) {
            session = null
            Log.w(TAG, "Recognition error $error")
            closeResult = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> RecognizerIntent.RESULT_NO_MATCH
                SpeechRecognizer.ERROR_AUDIO, SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> RecognizerIntent.RESULT_AUDIO_ERROR
                SpeechRecognizer.ERROR_CLIENT -> RecognizerIntent.RESULT_CLIENT_ERROR
                else -> RecognizerIntent.RESULT_SERVER_ERROR
            }
            uiState = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                    VoiceInputUiState.Failed(R.string.status_no_speech, canRetry = true, openApp = false)

                SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
                    VoiceInputUiState.Failed(R.string.voice_input_error_no_model, canRetry = false, openApp = true)

                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                    VoiceInputUiState.Failed(R.string.voice_input_error_permission, canRetry = false, openApp = true)

                SpeechRecognizer.ERROR_AUDIO ->
                    VoiceInputUiState.Failed(R.string.voice_input_error_audio, canRetry = true, openApp = false)

                else -> VoiceInputUiState.Failed(R.string.voice_input_error_generic, canRetry = true, openApp = false)
            }
        }
    }

    private fun deliver(text: String, confidence: Float) {
        val results = Intent().apply {
            putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS, arrayListOf(text))
            putExtra(RecognizerIntent.EXTRA_CONFIDENCE_SCORES, floatArrayOf(confidence))
        }
        val pendingIntent = IntentCompat.getParcelableExtra(
            intent, RecognizerIntent.EXTRA_RESULTS_PENDINGINTENT, PendingIntent::class.java,
        )
        if (pendingIntent != null) {
            val fillIn = Intent().apply {
                intent.getBundleExtra(RecognizerIntent.EXTRA_RESULTS_PENDINGINTENT_BUNDLE)?.let { putExtras(it) }
                putExtras(results)
            }
            // Android 14+ only lets the PendingIntent start an activity when the (foreground)
            // sender opts in.
            val options = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ActivityOptions.makeBasic()
                    .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                    .toBundle()
            } else null
            try {
                pendingIntent.send(this, Activity.RESULT_OK, fillIn, null, null, null, options)
            } catch (e: PendingIntent.CanceledException) {
                Log.w(TAG, "Results PendingIntent was cancelled", e)
            }
        }
        setResult(Activity.RESULT_OK, results)
        finish()
    }

    private fun finishWith(resultCode: Int) {
        session?.cancel()
        session = null
        setResult(resultCode)
        finish()
    }
}

/** Bottom sheet over the calling app (not dimmed); tapping outside the sheet closes it. */
@Composable
private fun VoiceInputSheet(
    state: VoiceInputUiState,
    prompt: String?,
    onDone: () -> Unit,
    onClose: () -> Unit,
    onRetry: () -> Unit,
    onOpenApp: () -> Unit,
) {
    val sheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClose,
            ),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Surface(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                // Nothing dims the app behind, so a shadow cast upwards marks the sheet's top
                // edge (an elevation shadow falls mostly below a bottom-anchored sheet).
                .dropShadow(
                    shape = sheetShape,
                    shadow = Shadow(
                        radius = 8.dp,
                        color = Color.Black,
                        offset = DpOffset(0.dp, (-2).dp),
                        alpha = 0.35f,
                    ),
                )
                // Swallow taps so they do not reach the close-on-tap area behind the sheet.
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ),
            shape = sheetShape,
            tonalElevation = 3.dp,
        ) {
            Column(
                modifier = Modifier
                    .navigationBarsPadding()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Title, mic and buttons only: the sheet closes as soon as the result is in,
                // so recognised text would never be seen.
                val title = when (state) {
                    is VoiceInputUiState.Listening -> when {
                        state.modelLoading -> stringResource(R.string.status_engine_loading)
                        else -> prompt ?: stringResource(R.string.voice_input_speak_now)
                    }
                    is VoiceInputUiState.Transcribing -> stringResource(R.string.voice_input_transcribing)
                    is VoiceInputUiState.Failed -> stringResource(state.message)
                }
                Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)

                MicIndicator(state = state, onClick = onDone)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    TextButton(onClick = onClose) { Text(stringResource(R.string.action_cancel)) }
                    when (state) {
                        is VoiceInputUiState.Listening -> Button(onClick = onDone) {
                            Text(stringResource(R.string.voice_input_done))
                        }

                        is VoiceInputUiState.Transcribing -> Button(onClick = {}, enabled = false) {
                            Text(stringResource(R.string.voice_input_done))
                        }

                        is VoiceInputUiState.Failed -> when {
                            state.openApp -> Button(onClick = onOpenApp) {
                                Text(stringResource(R.string.action_open_parakeetype))
                            }

                            state.canRetry -> Button(onClick = onRetry) {
                                Text(stringResource(R.string.voice_input_retry))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Mic button with a halo that follows the input level; tapping it while listening stops. */
@Composable
private fun MicIndicator(state: VoiceInputUiState, onClick: () -> Unit) {
    val listening = state is VoiceInputUiState.Listening
    val level = (state as? VoiceInputUiState.Listening)?.level ?: 0f
    val haloScale by animateFloatAsState(targetValue = 1f + level * 0.6f, label = "micLevel")
    Box(modifier = Modifier.size(112.dp), contentAlignment = Alignment.Center) {
        if (listening) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .scale(haloScale)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.24f), CircleShape),
            )
        }
        FilledIconButton(
            onClick = onClick,
            enabled = listening,
            modifier = Modifier.size(72.dp),
        ) {
            Icon(
                imageVector = if (state is VoiceInputUiState.Failed) Icons.Rounded.MicOff else Icons.Rounded.Mic,
                contentDescription = stringResource(R.string.voice_input_done),
                modifier = Modifier.size(36.dp),
            )
        }
    }
}

//  Previews

@Composable
private fun VoiceInputSheetPreview(state: VoiceInputUiState, prompt: String? = null) {
    ParakeetypeTheme {
        VoiceInputSheet(
            state = state,
            prompt = prompt,
            onDone = {}, onClose = {}, onRetry = {}, onOpenApp = {},
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFEEEEEE, widthDp = 360, heightDp = 480, name = "Sheet · Listening")
@Composable
private fun VoiceInputSheetListeningPreview() {
    VoiceInputSheetPreview(VoiceInputUiState.Listening())
}

@Preview(showBackground = true, backgroundColor = 0xFFEEEEEE, widthDp = 360, heightDp = 480, name = "Sheet · Listening (prompt, speaking)")
@Composable
private fun VoiceInputSheetListeningPromptPreview() {
    VoiceInputSheetPreview(VoiceInputUiState.Listening(level = 0.6f), prompt = "What should I remind you of?")
}

@Preview(
    showBackground = true, backgroundColor = 0xFF202020, widthDp = 360, heightDp = 480,
    uiMode = Configuration.UI_MODE_NIGHT_YES, name = "Sheet · Listening (dark)",
)
@Composable
private fun VoiceInputSheetListeningDarkPreview() {
    VoiceInputSheetPreview(VoiceInputUiState.Listening(level = 0.4f))
}

@Preview(showBackground = true, backgroundColor = 0xFFEEEEEE, widthDp = 360, heightDp = 480, name = "Sheet · Listening (model loading)")
@Composable
private fun VoiceInputSheetModelLoadingPreview() {
    VoiceInputSheetPreview(VoiceInputUiState.Listening(modelLoading = true))
}

@Preview(showBackground = true, backgroundColor = 0xFFEEEEEE, widthDp = 360, heightDp = 480, name = "Sheet · Transcribing")
@Composable
private fun VoiceInputSheetTranscribingPreview() {
    VoiceInputSheetPreview(VoiceInputUiState.Transcribing)
}

@Preview(showBackground = true, backgroundColor = 0xFFEEEEEE, widthDp = 360, heightDp = 480, name = "Sheet · Failed (retry)")
@Composable
private fun VoiceInputSheetFailedRetryPreview() {
    VoiceInputSheetPreview(VoiceInputUiState.Failed(R.string.status_no_speech, canRetry = true, openApp = false))
}

@Preview(showBackground = true, backgroundColor = 0xFFEEEEEE, widthDp = 360, heightDp = 480, name = "Sheet · Failed (no model)")
@Composable
private fun VoiceInputSheetFailedNoModelPreview() {
    VoiceInputSheetPreview(VoiceInputUiState.Failed(R.string.voice_input_error_no_model, canRetry = false, openApp = true))
}

@Preview(showBackground = true, name = "Mic · Listening (quiet)")
@Composable
private fun MicIndicatorQuietPreview() {
    ParakeetypeTheme { MicIndicator(state = VoiceInputUiState.Listening(level = 0f), onClick = {}) }
}

@Preview(showBackground = true, name = "Mic · Listening (loud)")
@Composable
private fun MicIndicatorLoudPreview() {
    ParakeetypeTheme { MicIndicator(state = VoiceInputUiState.Listening(level = 1f), onClick = {}) }
}

@Preview(showBackground = true, name = "Mic · Transcribing")
@Composable
private fun MicIndicatorTranscribingPreview() {
    ParakeetypeTheme { MicIndicator(state = VoiceInputUiState.Transcribing, onClick = {}) }
}

@Preview(showBackground = true, name = "Mic · Failed")
@Composable
private fun MicIndicatorFailedPreview() {
    ParakeetypeTheme {
        MicIndicator(state = VoiceInputUiState.Failed(R.string.voice_input_error_audio, canRetry = true, openApp = false), onClick = {})
    }
}

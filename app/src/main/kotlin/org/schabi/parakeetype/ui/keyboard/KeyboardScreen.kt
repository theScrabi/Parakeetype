package org.schabi.parakeetype.ui.keyboard

import android.content.res.Configuration
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SubdirectoryArrowLeft
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import org.schabi.parakeetype.R
import org.schabi.parakeetype.ime.EnterAction
import org.schabi.parakeetype.inference.PipelineDiagnostics
import org.schabi.parakeetype.ui.keyboard.components.*
import org.schabi.parakeetype.ui.theme.ParakeetypeKeyboardTheme

/**
 * Root composable for the keyboard input view.
 *
 * Layout (top → bottom):
 *  1. Top row: a left slot plus the Switch Keyboard button on the right. The left slot shows
 *     the [LanguageSelector] (Whisper models only, while idle) or otherwise the
 *     [StatusIndicator] (listening / processing / transcribing / error / loading).
 *  2. Bottom row (left → right):
 *       [Delete All] · [TalkButton] · [Delete Word] · [Enter]
 *     The two delete keys flank the TalkButton; Enter is pinned to the right edge. The
 *     TalkButton stays centred with equal weight on both sides.
 *
 * @param uiState                Current UI state collected from [KeyboardViewModel.uiState].
 * @param isContinuous           `true` when continuous (locked) recording mode is active.
 * @param triggerMode            `"HOLD"` (default) or `"TAP_TOGGLE"`.
 * @param isWhisperEngine        `true` when the active engine is a Whisper variant - only then
 *                               can the language selector appear.
 * @param whisperLanguage        Currently selected Whisper language tag (`"auto"`, `"en"`, …).
 * @param onWhisperLanguageSelected Called when the user taps a language pill.
 * @param onRecordStart          Callback fired when the user presses the talk button.
 * @param onRecordStop           Callback fired when the user releases / stops recording.
 * @param onContinuousModeEnabled Callback fired when the drag-left lock threshold is crossed.
 * @param onDeleteWord           Delete backward to the previous word boundary.
 * @param onDeleteAll            Delete all text in the current editor.
 * @param onEnterAction          Perform the context-aware Enter action (newline or IME action).
 * @param enterAction            The semantic action for the Enter key in the current editor.
 * @param onSwitchKeyboard       Switches the active IME back to the previous keyboard.
 * @param onOpenCompanionApp     Opens the Parakeetype companion app (e.g. to grant permission or download the model).
 * @param diagnostics            Pipeline counters from the most recent recording session.
 */
@Composable
fun KeyboardScreen(
    uiState: KeyboardUiState,
    isContinuous: Boolean,
    triggerMode: String,
    isWhisperEngine: Boolean,
    whisperLanguage: String,
    onWhisperLanguageSelected: (String) -> Unit,
    onRecordStart: () -> Unit,
    onRecordStop: () -> Unit,
    onContinuousModeEnabled: () -> Unit,
    onRetry: (() -> Unit)? = null,
    onDeleteWord: () -> Unit,
    onDeleteAll: () -> Unit,
    onEnterAction: () -> Unit,
    enterAction: EnterAction = EnterAction.DONE,
    onSwitchKeyboard: () -> Unit,
    onOpenCompanionApp: () -> Unit,
    modifier: Modifier = Modifier,
    diagnostics: PipelineDiagnostics = PipelineDiagnostics(),
    previewForceLockHint: Boolean = false,
    /**
     * Fixed height in pixels for the main keyboard content area (buttons, status row).
     * When non-zero this is used directly so the content has a stable size. Defaults to 0 for previews, which fall back to [Modifier.weight].
     */
    keyboardContentHeightPx: Int = 0,
    /**
     * Navigation bar height in pixels from [WindowManager.currentWindowMetrics] at the
     * service level. Applied as explicit bottom padding on the keyboard content column so
     * that buttons are never drawn behind the system navigation bar.
     *
     * We do NOT use [Modifier.navigationBarsPadding] here because inset dispatch inside an IME window
     * is unreliable on some OEM ROMs — if insets are never delivered the modifier is a
     * silent no-op and the buttons draw behind the nav bar. The service-level value is
     * authoritative and always correct.
     *
     * 0 on gesture-navigation devices (no bar to avoid), real height on button-nav devices.
     */
    navBarHeightPx: Int = 0,
    /**
     * Horizontal position of the keyboard UI: `"CENTER"` (full width), `"LEFT"` or `"RIGHT"`
     * (docked to that screen edge at most [DOCKED_KEYBOARD_WIDTH] wide, so it stays within
     * thumb reach in landscape and on tablets). Physical left / right, also in RTL locales.
     */
    keyboardPosition: String = "CENTER",
    /** Left / right system insets (side nav bar, display cutout) in pixels, kept clear. */
    leftInsetPx: Int = 0,
    rightInsetPx: Int = 0,
) {
    val density = LocalDensity.current
    // Convert the service-provided pixel heights to Dp once; stay constant per session.
    val mainContentHeight = if (keyboardContentHeightPx > 0) {
        with(density) { keyboardContentHeightPx.toDp() }
    } else null
    // Explicit nav bar bottom padding — authoritative service-level value.
    // 0 on gesture nav, real height on button-nav devices.
    val navBarPaddingDp = with(density) { navBarHeightPx.toDp() }
    val leftInsetDp = with(density) { leftInsetPx.toDp() }
    val rightInsetDp = with(density) { rightInsetPx.toDp() }
    val docked = keyboardPosition == "LEFT" || keyboardPosition == "RIGHT"

    Box(
        modifier = modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.background)
            .absolutePadding(left = leftInsetDp, right = rightInsetDp),
    ) {
        // Main keyboard content — pinned to the bottom with a fixed height. Its content area
        // is the keyboard area above the nav bar: the top row is pinned to its top edge and
        // the button row is centred vertically in it (see below).
        // When docked to an edge it is at most DOCKED_KEYBOARD_WIDTH wide.
        BoxWithConstraints(
            modifier = Modifier
                .align(
                    when (keyboardPosition) {
                        "LEFT" -> AbsoluteAlignment.BottomLeft
                        "RIGHT" -> AbsoluteAlignment.BottomRight
                        else -> Alignment.BottomCenter
                    }
                )
                .then(if (docked) Modifier.widthIn(max = DOCKED_KEYBOARD_WIDTH) else Modifier)
                .fillMaxWidth()
                .then(
                    if (mainContentHeight != null) Modifier.height(mainContentHeight)
                    else Modifier.fillMaxHeight()
                )
                .padding(start = 16.dp, end = 16.dp, bottom = navBarPaddingDp),
        ) {
            // Top row: pinned to the top edge.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .padding(top = TOP_ROW_TOP_PADDING),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (uiState is KeyboardUiState.Error) {
                    Box(modifier = Modifier.weight(1f)) {}
                    Spacer(modifier = Modifier.width(8.dp))
                }

                // Left slot: the language pills while idle (Whisper models only - Parakeet
                // never shows them), otherwise the status line.
                Crossfade(
                    targetState = isWhisperEngine && uiState is KeyboardUiState.Idle,
                    label = "topLeftSlot",
                ) { showLanguageSelector ->
                    if (showLanguageSelector) {
                        LanguageSelector(
                            selectedLanguage = whisperLanguage,
                            onLanguageSelected = onWhisperLanguageSelected,
                        )
                    } else {
                        StatusIndicator(
                            uiState = uiState,
                            diagnostics = diagnostics,
                            onOpenCompanionApp = onOpenCompanionApp,
                            onRetry = onRetry ?: onRecordStart,
                        )
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))

                Box(modifier = Modifier.weight(1f)) {
                    KeyboardActionButton(
                        icon = Icons.Rounded.Keyboard,
                        contentDescription = stringResource(R.string.cd_switch_keyboard),
                        onClick = onSwitchKeyboard,
                        modifier = Modifier.align(Alignment.CenterEnd),
                    )
                }
            }

            //  Button row: 4 buttons with TalkButton centred horizontally.
            // Left and right groups each have weight(1f) so the centre button stays
            // exactly in the middle regardless of screen width.
            //
            // Vertically the row is centred exactly in the keyboard area on every screen
            // size (its height is fixed to the talk button's, so its middle is the talk
            // button's middle). Only on very short keyboards, where the centred row would
            // run into the top row, is it pushed down just far enough to clear it.
            val centredTop = (maxHeight - BUTTON_ROW_HEIGHT) / 2
            // True while the talk button shows its lock hint over the delete-all key.
            var lockHintVisible by remember { mutableStateOf(false) }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(BUTTON_ROW_HEIGHT)
                    .align(Alignment.TopCenter)
                    .offset(y = centredTop.coerceAtLeast(TOP_ROW_CLEARANCE)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Left group: [Delete All], directly left of the talk button. Hidden while the
                // talk button's lock hint is shown, since the hint floats over this spot.
                Box(modifier = Modifier.weight(1f)) {
                    // Fully qualified: the enclosing Row's RowScope overload isn't usable here.
                    androidx.compose.animation.AnimatedVisibility(
                        visible = !lockHintVisible,
                        enter = fadeIn(tween(150)),
                        exit = fadeOut(tween(120)),
                        modifier = Modifier.align(Alignment.CenterEnd),
                    ) {
                        DeleteKey(
                            icon = Icons.Rounded.DeleteForever,
                            contentDescription = stringResource(R.string.cd_delete_all),
                            onClick = onDeleteAll,
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Centre: talk button
                // isListening is true for Listening and Processing (mic active, audio flowing).
                // Transcribing means audio has stopped but the engine is still working - mic off,
                // button disabled until the Final result arrives and the state returns to Idle.
                TalkButton(
                    isListening = uiState is KeyboardUiState.Listening || uiState is KeyboardUiState.Processing,
                    isContinuous = isContinuous,
                    triggerMode = triggerMode,
                    onRecordStart = onRecordStart,
                    onRecordStop = onRecordStop,
                    onContinuousModeEnabled = onContinuousModeEnabled,
                    enabled = uiState !is KeyboardUiState.EngineLoading && uiState !is KeyboardUiState.Error && uiState !is KeyboardUiState.Transcribing,
                    previewForceLockHint = previewForceLockHint,
                    onLockHintVisibleChange = { lockHintVisible = it },
                )

                Spacer(modifier = Modifier.width(8.dp))

                // Right group: [Delete Word] directly right of the talk button, [Enter] far right
                Box(modifier = Modifier.weight(1f)) {
                    DeleteKey(
                        icon = Icons.AutoMirrored.Rounded.Backspace,
                        contentDescription = stringResource(R.string.cd_delete_word),
                        onClick = onDeleteWord,
                        modifier = Modifier.align(Alignment.CenterStart),
                    )

                    // Far-right: context-aware Enter action
                    val (enterIcon, enterDescription) = when (enterAction) {
                        EnterAction.SEARCH -> Icons.Rounded.Search to stringResource(R.string.cd_action_search)
                        EnterAction.GO -> Icons.AutoMirrored.Rounded.ArrowForward to stringResource(R.string.cd_action_go)
                        EnterAction.NEXT -> Icons.AutoMirrored.Rounded.ArrowForward to stringResource(R.string.cd_action_next)
                        EnterAction.SEND,
                        EnterAction.DONE,
                        EnterAction.ENTER_KEY,
                        EnterAction.NEWLINE -> Icons.Rounded.SubdirectoryArrowLeft to stringResource(R.string.cd_action_enter)
                    }
                    // A filled, larger key: the old 40 dp icon-only button was easy to miss
                    // and looked like the delete buttons.
                    KeyboardActionButton(
                        icon = enterIcon,
                        contentDescription = enterDescription,
                        onClick = onEnterAction,
                        // Disable auto-repeat for action buttons - search/send/go should only fire once.
                        repeatEnabled = enterAction == EnterAction.NEWLINE,
                        size = DpSize(64.dp, 52.dp),
                        iconSize = 28.dp,
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        shape = KEY_SHAPE,
                        modifier = Modifier.align(Alignment.CenterEnd),
                    )

                }
            }
        } // end main content Box
    } // end outer Box
}

private val KEY_SHAPE = RoundedCornerShape(16.dp)

/**
 * Maximum width of the keyboard UI when docked to the left or right edge (landscape, or the
 * portrait LEFT / RIGHT setting): a typical phone portrait width, so every key stays within
 * one thumb's reach.
 */
private val DOCKED_KEYBOARD_WIDTH = 400.dp

/** Height of the button row: the talk button's fixed 72 dp size (the tallest key). */
private val BUTTON_ROW_HEIGHT = 72.dp

/** Space between the keyboard's top edge and the top row. */
private val TOP_ROW_TOP_PADDING = 2.dp

/**
 * Minimum distance from the keyboard's top edge to the button row: the top row
 * ([TOP_ROW_TOP_PADDING] + the 40 dp switch-keyboard key) plus a small gap. Only binds on
 * very short keyboards; normally the button row is exactly centred.
 */
private val TOP_ROW_CLEARANCE = TOP_ROW_TOP_PADDING + 40.dp + 4.dp

/**
 * A delete key (trash / delete word): a regular Material 3 [IconButton] — fires on release
 * like any button, standard ripple clipped to [KEY_SHAPE], no background. 52×48 dp: a
 * larger target than a default icon button, a bit smaller than the Enter key. Each click
 * gives the standard keyboard key vibration, like [KeyboardActionButton].
 */
@Composable
private fun DeleteKey(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    IconButton(
        onClick = {
            haptics.performHapticFeedback(HapticFeedbackType.KeyboardTap)
            onClick()
        },
        modifier = modifier.size(DpSize(52.dp, 48.dp)),
        colors = IconButtonDefaults.iconButtonColors(
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        shape = KEY_SHAPE,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(26.dp),
        )
    }
}

/**
 * Convenience overload that reads directly from a [KeyboardViewModel]'s state flows.
 * Used by [org.schabi.parakeetype.ime.ParakeetypeInputMethodService] to set up the content.
 *
 * @param navBarHeightPx Navigation bar height in pixels from [WindowManager.currentWindowMetrics],
 *                       kept clear below the keyboard content.
 * @param leftInsetPx / [rightInsetPx] Side system insets (side nav bar, cutout) in pixels.
 *
 * The keyboard position follows the orientation: the landscape setting (docked right by
 * default) in landscape, the portrait setting (centred by default) otherwise.
 */
@Composable
fun KeyboardScreen(
    viewModel: KeyboardViewModel,
    onSwitchKeyboard: () -> Unit,
    onOpenCompanionApp: () -> Unit,
    keyboardContentHeightPx: Int = 0,
    navBarHeightPx: Int = 0,
    leftInsetPx: Int = 0,
    rightInsetPx: Int = 0,
) {
    val uiState by viewModel.uiState.collectAsState()
    val isContinuous by viewModel.isContinuousMode.collectAsState()
    val triggerMode by viewModel.triggerMode.collectAsState()
    val isWhisperEngine by viewModel.isWhisperEngine.collectAsState()
    val whisperLanguage by viewModel.whisperLanguage.collectAsState()
    val rawDiagnostics by viewModel.diagnostics.collectAsState()
    val showPipelineDiagnostics by viewModel.showPipelineDiagnostics.collectAsState()
    val enterAction by viewModel.enterAction.collectAsState()
    val positionPortrait by viewModel.keyboardPositionPortrait.collectAsState()
    val positionLandscape by viewModel.keyboardPositionLandscape.collectAsState()
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    // Only surface real diagnostics counters when the user has enabled the badge in settings.
    val diagnostics = if (showPipelineDiagnostics) rawDiagnostics else PipelineDiagnostics()

    KeyboardScreen(
        uiState = uiState,
        isContinuous = isContinuous,
        triggerMode = triggerMode,
        isWhisperEngine = isWhisperEngine,
        whisperLanguage = whisperLanguage,
        onWhisperLanguageSelected = viewModel::setWhisperLanguage,
        onRecordStart = viewModel::onRecordStart,
        onRecordStop = viewModel::onRecordStop,
        onContinuousModeEnabled = viewModel::onContinuousModeEnabled,
        onRetry = viewModel::onRetry,
        onDeleteWord = viewModel::deleteWord,
        onDeleteAll = viewModel::deleteAll,
        onEnterAction = viewModel::performEnterAction,
        enterAction = enterAction,
        onSwitchKeyboard = onSwitchKeyboard,
        onOpenCompanionApp = onOpenCompanionApp,
        diagnostics = diagnostics,
        keyboardContentHeightPx = keyboardContentHeightPx,
        navBarHeightPx = navBarHeightPx,
        keyboardPosition = if (isLandscape) positionLandscape else positionPortrait,
        leftInsetPx = leftInsetPx,
        rightInsetPx = rightInsetPx,
    )
}

@Composable
private fun KeyboardScreenPreviewScaffold(
    uiState: KeyboardUiState,
    isContinuous: Boolean = false,
    isWhisperEngine: Boolean = false,
    whisperLanguage: String = "auto",
    showLockHint: Boolean = false,
    enterAction: EnterAction = EnterAction.DONE,
    keyboardPosition: String = "CENTER",
    height: Int = 220,
) {
    ParakeetypeKeyboardTheme {
        Box(modifier = Modifier.height(height.dp)) {
            KeyboardScreen(
                uiState = uiState,
                isContinuous = isContinuous,
                triggerMode = "HOLD",
                isWhisperEngine = isWhisperEngine,
                whisperLanguage = whisperLanguage,
                onWhisperLanguageSelected = {},
                onRecordStart = {},
                onRecordStop = {},
                onContinuousModeEnabled = {},
                onDeleteWord = {},
                onDeleteAll = {},
                onEnterAction = {},
                enterAction = enterAction,
                onSwitchKeyboard = {},
                onOpenCompanionApp = {},
                previewForceLockHint = showLockHint,
                keyboardPosition = keyboardPosition,
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF111111, widthDp = 800, name = "Landscape · docked right")
@Composable
private fun KeyboardScreenLandscapeRightPreview() {
    KeyboardScreenPreviewScaffold(uiState = KeyboardUiState.Idle, keyboardPosition = "RIGHT", height = 140)
}

@Preview(showBackground = true, backgroundColor = 0xFF111111, widthDp = 800, name = "Landscape · docked left")
@Composable
private fun KeyboardScreenLandscapeLeftPreview() {
    KeyboardScreenPreviewScaffold(
        uiState = KeyboardUiState.Processing("Hello world…"),
        keyboardPosition = "LEFT",
        height = 140,
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF111111)
@Composable
private fun KeyboardScreenIdlePreview() {
    KeyboardScreenPreviewScaffold(uiState = KeyboardUiState.Idle, showLockHint = false)
}

@Preview(showBackground = true, backgroundColor = 0xFF111111)
@Composable
private fun KeyboardScreenWhisperIdlePreview() {
    KeyboardScreenPreviewScaffold(
        uiState = KeyboardUiState.Idle,
        isWhisperEngine = true,
        whisperLanguage = "de",
        showLockHint = true,
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF111111)
@Composable
private fun KeyboardScreenListeningPreview() {
    KeyboardScreenPreviewScaffold(uiState = KeyboardUiState.Listening, showLockHint = true)
}

@Preview(showBackground = true, backgroundColor = 0xFF111111)
@Composable
private fun KeyboardScreenContinuousPreview() {
    KeyboardScreenPreviewScaffold(
        uiState = KeyboardUiState.Listening,
        isContinuous = true,
        showLockHint = true,
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF111111)
@Composable
private fun KeyboardScreenProcessingPreview() {
    KeyboardScreenPreviewScaffold(uiState = KeyboardUiState.Processing("Hello world…"), showLockHint = true)
}

@Preview(showBackground = true, backgroundColor = 0xFF111111)
@Composable
private fun KeyboardScreenErrorPreview() {
    KeyboardScreenPreviewScaffold(
        uiState = KeyboardUiState.Error(KeyboardUiState.ErrorReason.MicPermissionDenied),
        showLockHint = false
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF111111)
@Composable
private fun KeyboardScreenSearchActionPreview() {
    KeyboardScreenPreviewScaffold(uiState = KeyboardUiState.Idle, enterAction = EnterAction.SEARCH)
}

@Preview(showBackground = true, backgroundColor = 0xFF111111)
@Composable
private fun KeyboardScreenSendActionPreview() {
    KeyboardScreenPreviewScaffold(uiState = KeyboardUiState.Idle, enterAction = EnterAction.SEND)
}

@Preview(showBackground = true, backgroundColor = 0xFF111111)
@Composable
private fun KeyboardScreenNewlineActionPreview() {
    KeyboardScreenPreviewScaffold(uiState = KeyboardUiState.Idle, enterAction = EnterAction.NEWLINE)
}

@Preview(showBackground = true, backgroundColor = 0xFF111111)
@Composable
private fun KeyboardScreenTransientErrorPreview() {
    KeyboardScreenPreviewScaffold(
        uiState = KeyboardUiState.Error(KeyboardUiState.ErrorReason.TranscriptionFailed, detail = "ONNX error")
    )
}


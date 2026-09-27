package dev.brgr.outspoke.ui.keyboard.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.brgr.outspoke.ui.theme.OutspokeKeyboardTheme

/** How many dp to the left the user must drag to engage continuous mode. */
private const val CONTINUOUS_DRAG_THRESHOLD_DP = 56

/** Gap between the TalkButton's left edge and the right edge of the lock hint indicator. */
private const val LOCK_HINT_GAP_DP = 8

/**
 * The primary interaction element of the keyboard.
 *
 * **HOLD mode** - press-and-hold to record; release to stop.
 *
 * **Drag-left-to-lock** (HOLD mode only) - while holding, drag left past [CONTINUOUS_DRAG_THRESHOLD_DP] dp
 * to engage continuous mode.  A lock indicator floats to the left of the button while the user
 * holds, similar to the WhatsApp voice-message lock UI: a bouncing left chevron invites the
 * swipe, and the lock closes and fills with colour as the drag threshold is approached.
 * The button scales and turns to the error container colour to confirm the lock.
 * Recording continues without the user needing to keep touching the screen.
 *
 * **Continuous mode** (HOLD) - button shows a pulsing [Stop] icon.  Tap once to stop recording.
 *
 * **TAP_TOGGLE mode** - single tap starts recording; another tap stops it.  No hold needed.
 *
 * Every finger down and finger up on the button gives the standard keyboard key vibration.
 *
 * @param triggerMode          `"HOLD"` (default) or `"TAP_TOGGLE"`.
 * @param isContinuous         `true` when continuous (locked) mode is active (HOLD mode only).
 * @param onContinuousModeEnabled Callback fired when the drag-left threshold is crossed (HOLD mode only).
 * @param onLockHintVisibleChange Called whenever the lock hint (left of the button) appears or
 *                                disappears, so the caller can hide what it would cover.
 */
@Composable
fun TalkButton(
    isListening: Boolean,
    isContinuous: Boolean,
    onRecordStart: () -> Unit,
    onRecordStop: () -> Unit,
    onContinuousModeEnabled: () -> Unit,
    modifier: Modifier = Modifier,
    triggerMode: String = "HOLD",
    enabled: Boolean = true,
    previewForceLockHint: Boolean = false, // For previews: force lock hint visible
    onLockHintVisibleChange: (Boolean) -> Unit = {},
) {
    val effectiveListening = isListening && enabled
    val isContinuousActive = isContinuous && effectiveListening

    // True while the user's finger is down in HOLD mode (drives the lock hint visibility).
    var isHolding by remember { mutableStateOf(false) }
    //  Drag-progress state (0 = no drag, 1 = threshold reached)
    var dragProgress by remember { mutableFloatStateOf(0f) }
    // For preview: force lock hint visible
    if (previewForceLockHint) {
        isHolding = true
        dragProgress = 0.5f
    }

    // Reset holding state if the button becomes disabled mid-gesture.
    LaunchedEffect(enabled) {
        if (!enabled) {
            isHolding = false
            dragProgress = 0f
        }
    }

    //  Base scale: animates on press/release
    val baseScale by animateFloatAsState(
        targetValue = when {
            !enabled -> 1f
            isContinuousActive || effectiveListening -> 1.12f
            else -> 1f
        },
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "talkButtonBaseScale",
    )

    //  Continuous-mode pulse
    val infiniteTransition = rememberInfiniteTransition(label = "talkButtonContinuousPulse")
    val pulse by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 650, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "talkButtonPulseScale",
    )

    // Combine base scale with drag-progress grow and continuous pulse.
    val finalScale = baseScale *
            (if (isContinuousActive) pulse else 1f) +
            (if (effectiveListening && !isContinuousActive) dragProgress * 0.05f else 0f)

    //  Colours - Material 3 tonal container pairs: listening uses the same
    //  secondaryContainer as the Enter key, locked (continuous) mode the errorContainer.
    val backgroundColor by animateColorAsState(
        targetValue = when {
            !enabled -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
            isContinuousActive -> MaterialTheme.colorScheme.errorContainer
            effectiveListening -> MaterialTheme.colorScheme.secondaryContainer
            else -> MaterialTheme.colorScheme.surfaceVariant
        },
        label = "talkButtonBackground",
    )
    val iconTint by animateColorAsState(
        targetValue = when {
            !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
            isContinuousActive -> MaterialTheme.colorScheme.onErrorContainer
            effectiveListening -> MaterialTheme.colorScheme.onSecondaryContainer
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        label = "talkButtonIconTint",
    )

    //  Capture latest callbacks so pointerInput coroutine always calls the 
    //  current lambdas without restarting the gesture handler.             
    val currentOnRecordStart by rememberUpdatedState(onRecordStart)
    val currentOnRecordStop by rememberUpdatedState(onRecordStop)
    val currentOnContinuousMode by rememberUpdatedState(onContinuousModeEnabled)
    val currentIsContinuous by rememberUpdatedState(isContinuous)
    val currentIsListening by rememberUpdatedState(isListening)

    // Keyboard key vibration on finger down and finger up (honours the system's
    // keyboard-vibration setting), matching the other keyboard keys.
    val haptics = LocalHapticFeedback.current
    val buzz = { haptics.performHapticFeedback(HapticFeedbackType.KeyboardTap) }

    //  Root container: fixed 72dp, handles all gestures
    // Box does NOT clip children by default, which lets the LockHint render
    // above the 72dp bounds without affecting layout measurement.
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(72.dp)
            .then(
                if (enabled) Modifier.pointerInput(triggerMode, true) {
                    val thresholdPx = CONTINUOUS_DRAG_THRESHOLD_DP * density

                    awaitPointerEventScope {
                        while (true) {
                            if (triggerMode == "TAP_TOGGLE") {
                                //  TAP_TOGGLE mode: tap once to start, tap again to stop 
                                awaitFirstDown(requireUnconsumed = false)
                                buzz()
                                waitForUpOrCancellation()
                                buzz()
                                if (currentIsListening) {
                                    currentOnRecordStop()
                                } else {
                                    currentOnRecordStart()
                                }
                            } else {
                                //  HOLD mode: hold to record, drag left to lock 
                                val down = awaitFirstDown(requireUnconsumed = false)
                                buzz()

                                if (currentIsContinuous) {
                                    // Continuous mode: single tap to stop
                                    waitForUpOrCancellation()
                                    buzz()
                                    dragProgress = 0f
                                    currentOnRecordStop()
                                } else {
                                    isHolding = true
                                    currentOnRecordStart()
                                    var locked = false
                                    val startX = down.position.x

                                    // try/finally guarantees recording stops when the gesture ends
                                    // for ANY reason — not only an explicit finger-up. A lost
                                    // up event otherwise leaves the handler stuck in
                                    // awaitPointerEvent() with recording still active and the
                                    // button showing "listening", forcing the user to tap again
                                    // to stop. Lost up events happen in the IME context when the
                                    // host app scrolls/relayouts after a mid-session commit
                                    // (InputConnection.commitText can interrupt the IME touch
                                    // stream with an empty/cancelled pointer event), or when the
                                    // keyboard view is recreated/cancelled mid-hold. The previous
                                    // code broke out of the loop on `event.changes.firstOrNull()
                                    // == null` and on coroutine cancellation WITHOUT calling
                                    // onRecordStop — both leaked an active recording.
                                    try {
                                        while (true) {
                                            val event = awaitPointerEvent()
                                            val change = event.changes.firstOrNull()
                                            if (change == null) break  // empty/cancelled event → release
                                            if (!change.pressed) break  // finger up → release

                                            change.consume()
                                            val leftDelta = startX - change.position.x
                                            dragProgress = (leftDelta / thresholdPx).coerceIn(0f, 1f)

                                            if (!locked && leftDelta > thresholdPx) {
                                                locked = true
                                                dragProgress = 0f
                                                isHolding = false
                                                currentOnContinuousMode()
                                            }
                                        }
                                    } finally {
                                        // Locked (continuous) mode is excluded: the user explicitly
                                        // engaged continuous recording, which is stopped via the
                                        // tap-to-stop path above, not by gesture exit.
                                        dragProgress = 0f
                                        isHolding = false
                                        buzz()
                                        if (!locked) currentOnRecordStop()
                                    }
                                }
                            }
                        }
                    }
                } else Modifier
            ),
    ) {
        //  Visual circle (scaled independently of the lock hint)
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .matchParentSize()
                .scale(finalScale)
                .clip(CircleShape)
                .background(backgroundColor),
        ) {
            Icon(
                imageVector = if (isContinuousActive) Icons.Rounded.Stop else Icons.Rounded.Mic,
                contentDescription = when {
                    !enabled -> "Talk button disabled - engine not ready"
                    isContinuousActive -> "Stop continuous recording"
                    isListening && triggerMode == "TAP_TOGGLE" -> "Tap to stop recording"
                    isListening -> "Stop recording"
                    triggerMode == "TAP_TOGGLE" -> "Tap to start recording"
                    else -> "Start recording (hold) · swipe left to lock"
                },
                tint = iconTint,
                modifier = Modifier.size(32.dp),
            )
        }

        //  Lock hint: floats to the left without disturbing layout
        // Modifier.layout reports (0, 0) to the parent Box so the button's
        // measured position is never shifted.  The placeable is then placed
        // with a negative X offset so it renders left of the button bounds, where
        // the delete-all key sits; KeyboardScreen hides that key while the hint is
        // visible (see onLockHintVisibleChange).
        val lockHintVisible = triggerMode == "HOLD" && isHolding && !isContinuousActive
        val currentOnLockHintVisibleChange by rememberUpdatedState(onLockHintVisibleChange)
        LaunchedEffect(lockHintVisible) { currentOnLockHintVisibleChange(lockHintVisible) }
        if (triggerMode == "HOLD") {
            AnimatedVisibility(
                visible = lockHintVisible,
                enter = fadeIn(animationSpec = tween(150)) +
                        scaleIn(
                            initialScale = 0.75f,
                            transformOrigin = TransformOrigin(1f, 0.5f),
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                stiffness = Spring.StiffnessMedium,
                            ),
                        ),
                exit = fadeOut(animationSpec = tween(120)) +
                        scaleOut(
                            targetScale = 0.75f,
                            transformOrigin = TransformOrigin(1f, 0.5f),
                            animationSpec = tween(120),
                        ),
                modifier = Modifier.layout { measurable, constraints ->
                    // Allow the hint to be wider than the 72dp button by measuring
                    // without a width cap.
                    val placeable = measurable.measure(
                        constraints.copy(
                            minWidth = 0,
                            minHeight = 0,
                            maxWidth = Constraints.Infinity,
                        ),
                    )
                    val gapPx = LOCK_HINT_GAP_DP.dp.roundToPx()
                    // The zero-size child sits at the Box centre (constraints.maxWidth / 2
                    // from the left).  Subtract that offset so the hint's right edge aligns
                    // with the button's left edge, vertically centred on the button.
                    val buttonHalfPx = constraints.maxWidth / 2
                    layout(0, 0) {
                        placeable.place(
                            x = -placeable.width - gapPx - buttonHalfPx,
                            y = -placeable.height / 2,
                        )
                    }
                },
            ) {
                // The lock gesture is always a physical drag to the left, so keep the hint
                // left-to-right even in RTL locales (no mirrored chevron / reversed order).
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    LockHint(dragProgress = dragProgress)
                }
            }
        }
    }
}

/**
 * Visual hint displayed to the left of [TalkButton] while the user holds in HOLD mode.
 *
 * Mimics the WhatsApp voice-message lock indicator:
 * - A bouncing left chevron (closest to the button) invites the leftward swipe.
 * - A lock icon pill left of it switches from open → closed, and its colour lerps from
 *   `onSurfaceVariant` → `primary` as [dragProgress] approaches 1, confirming the lock is near.
 */
@Composable
private fun LockHint(
    dragProgress: Float,
    modifier: Modifier = Modifier,
) {
    val idleColor = MaterialTheme.colorScheme.onSurfaceVariant
    val activeColor = MaterialTheme.colorScheme.primary
    val lockColor = lerp(idleColor, activeColor, dragProgress)

    val lockScale = 0.65f + dragProgress * 0.35f
    val lockAlpha = 0.50f + dragProgress * 0.50f

    // Infinite bounce animation for the left chevron.
    val arrowTransition = rememberInfiniteTransition(label = "lockHintArrow")
    val arrowOffsetDp by arrowTransition.animateFloat(
        initialValue = 0f,
        targetValue = -5f,
        animationSpec = infiniteRepeatable(
            animation = tween(480, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "lockArrowOffset",
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(0.dp),
        modifier = modifier,
    ) {
        // Lock icon inside a circular pill that scales and brightens with drag progress.
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(65.dp)
                .scale(lockScale)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = lockAlpha)),
        ) {
            Icon(
                imageVector = if (dragProgress >= 0.85f) Icons.Rounded.Lock else Icons.Rounded.LockOpen,
                contentDescription = null,
                tint = lockColor,
                modifier = Modifier.size(40.dp),
            )
        }

        // Left chevron: bounces to signal the swipe-left gesture.
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
            modifier = Modifier
                .size(25.dp)
                .offset(x = arrowOffsetDp.dp),
        )
    }
}

//  Previews 

@Preview(showBackground = true, backgroundColor = 0xFF111111, name = "Idle")
@Composable
private fun TalkButtonIdlePreview() {
    OutspokeKeyboardTheme {
        TalkButton(
            isListening = false, isContinuous = false,
            onRecordStart = {}, onRecordStop = {}, onContinuousModeEnabled = {})
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF111111, name = "Listening (hold)")
@Composable
private fun TalkButtonListeningPreview() {
    OutspokeKeyboardTheme {
        TalkButton(
            isListening = true, isContinuous = false,
            onRecordStart = {}, onRecordStop = {}, onContinuousModeEnabled = {})
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF111111, name = "Continuous (locked)")
@Composable
private fun TalkButtonContinuousPreview() {
    OutspokeKeyboardTheme {
        TalkButton(
            isListening = true, isContinuous = true,
            onRecordStart = {}, onRecordStop = {}, onContinuousModeEnabled = {})
    }
}

/** Shows all three drag-progress states of [LockHint] stacked. */
@Preview(
    showBackground = true, backgroundColor = 0xFF111111, name = "LockHint - all states",
    widthDp = 120, heightDp = 230
)
@Composable
private fun LockHintPreview() {
    OutspokeKeyboardTheme {
        Column(
            verticalArrangement = Arrangement.SpaceEvenly,
            horizontalAlignment = Alignment.End,
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
        ) {
            // Just appeared (finger just touched down)
            LockHint(dragProgress = 0f)
            // Mid-drag
            LockHint(dragProgress = 0.5f)
            // Threshold nearly reached
            LockHint(dragProgress = 1f)
        }
    }
}

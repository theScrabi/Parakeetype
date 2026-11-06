@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package org.schabi.parakeetype.bubble

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.schabi.parakeetype.R

/** What the bubble is doing. */
enum class BubblePhase { Idle, Listening, Finishing }

private const val BAR_COUNT = 18

/** Observable state of the bubble overlay, owned by [BubbleAccessibilityService]. */
class BubbleUiState {
    var phase by mutableStateOf(BubblePhase.Idle)

    /** The model is still loading; audio is buffered meanwhile. */
    var modelLoading by mutableStateOf(false)

    /** The bubble sits at the right screen edge (expands leftwards) or the left one. */
    var dockedRight by mutableStateOf(true)

    /** Recent microphone levels, oldest first, each 0..1. */
    val levels = mutableStateListOf<Float>().apply { repeat(BAR_COUNT) { add(0f) } }

    fun pushLevel(level: Float) {
        levels.removeAt(0)
        levels.add(level.coerceIn(0f, 1f))
    }

    fun resetLevels() {
        for (i in levels.indices) levels[i] = 0f
    }
}

/** Space around the visible bubble inside the overlay window, so its shadow is not clipped. */
val BUBBLE_WINDOW_PADDING = 8.dp
val BUBBLE_SIZE = 56.dp

/**
 * The overlay content: a round microphone button while idle, which grows into a recording pill
 * (cancel · live waveform · done) while dictating.
 */
@Composable
fun BubbleContent(
    state: BubbleUiState,
    onStart: () -> Unit,
    onHide: () -> Unit,
    onFinish: () -> Unit,
    onCancel: () -> Unit,
) {
    Box(Modifier.padding(BUBBLE_WINDOW_PADDING)) {
        AnimatedContent(
            targetState = state.phase == BubblePhase.Idle,
            transitionSpec = {
                // Grow/shrink the pill monotonically (a spring here overshoots and makes the
                // overlay window relayout jerk), crossfading mic and pill over the same window.
                ContentTransform(
                    targetContentEnter = fadeIn(tween(150, delayMillis = 30)),
                    initialContentExit = fadeOut(tween(150)),
                    sizeTransform = SizeTransform(clip = true) { _, _ -> tween(220, easing = FastOutSlowInEasing) },
                )
            },
            contentAlignment = if (state.dockedRight) AbsoluteAlignment.CenterRight else AbsoluteAlignment.CenterLeft,
            label = "bubble",
        ) { idle ->
            if (idle) {
                MicButton(onStart = onStart, onHide = onHide)
            } else {
                RecordingPill(state = state, onFinish = onFinish, onCancel = onCancel)
            }
        }
    }
}

@Composable
private fun MicButton(onStart: () -> Unit, onHide: () -> Unit) {
    val start = stringResource(R.string.bubble_start_dictation)
    val hide = stringResource(R.string.bubble_hide)
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shadowElevation = 6.dp,
        tonalElevation = 2.dp,
        modifier = Modifier.size(BUBBLE_SIZE),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .clip(CircleShape)
                .combinedClickable(
                    onClickLabel = start,
                    onLongClickLabel = hide,
                    onLongClick = onHide,
                    onClick = onStart,
                )
                .semantics { contentDescription = start },
        ) {
            Icon(Icons.Rounded.Mic, contentDescription = null, modifier = Modifier.size(28.dp))
        }
    }
}

@Composable
private fun RecordingPill(state: BubbleUiState, onFinish: () -> Unit, onCancel: () -> Unit) {
    val finishing = state.phase == BubblePhase.Finishing
    val onPill = MaterialTheme.colorScheme.inverseOnSurface
    Surface(
        shape = RoundedCornerShape(percent = 50),
        color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = onPill,
        shadowElevation = 8.dp,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            // Absolute, so done stays on the docked side in RTL locales too.
            horizontalArrangement = Arrangement.Absolute.spacedBy(6.dp),
            modifier = Modifier.padding(6.dp),
        ) {
            val cancel: @Composable () -> Unit = {
                PillButton(
                    label = stringResource(R.string.bubble_cancel),
                    background = onPill.copy(alpha = 0.14f),
                    enabled = !finishing,
                    onClick = onCancel,
                ) { Icon(Icons.Rounded.Close, contentDescription = null, tint = onPill, modifier = Modifier.size(22.dp)) }
            }
            val done: @Composable () -> Unit = {
                PillButton(
                    label = stringResource(R.string.bubble_done),
                    background = MaterialTheme.colorScheme.primary,
                    enabled = !finishing,
                    onClick = onFinish,
                ) {
                    if (finishing) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.5.dp,
                            modifier = Modifier.size(20.dp),
                        )
                    } else {
                        Icon(
                            Icons.Rounded.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
            }
            // The done button sits where the round bubble was, under the user's thumb.
            if (state.dockedRight) cancel() else done()
            Middle(state, finishing)
            if (state.dockedRight) done() else cancel()
        }
    }
}

@Composable
private fun Middle(state: BubbleUiState, finishing: Boolean) {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.width(128.dp).padding(horizontal = 4.dp)) {
        when {
            state.modelLoading -> Text(
                text = stringResource(R.string.bubble_model_loading),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
            )

            finishing -> Text(
                text = stringResource(R.string.bubble_transcribing),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
            )

            else -> Row(verticalAlignment = Alignment.CenterVertically) {
                RecordingDot()
                Spacer(Modifier.width(8.dp))
                Waveform(state)
            }
        }
    }
}

@Composable
private fun RecordingDot() {
    val pulse = rememberInfiniteTransition(label = "dot")
    val alpha by pulse.animateFloat(
        initialValue = 1f,
        targetValue = 0.35f,
        animationSpec = infiniteRepeatable(tween(700, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "dotAlpha",
    )
    Box(
        Modifier
            .size(8.dp)
            .alpha(alpha)
            .background(MaterialTheme.colorScheme.error, CircleShape)
    )
}

@Composable
private fun Waveform(state: BubbleUiState) {
    val color = MaterialTheme.colorScheme.inverseOnSurface
    Canvas(Modifier.width(104.dp).size(width = 104.dp, height = 28.dp)) {
        val count = state.levels.size
        val gap = 2.dp.toPx()
        val barWidth = (size.width - gap * (count - 1)) / count
        val minHeight = 3.dp.toPx()
        state.levels.forEachIndexed { i, level ->
            // Fade the oldest bars so the waveform appears to scroll in from the right.
            val age = (i + 1f) / count
            val height = minHeight + (size.height - minHeight) * level
            drawRoundRect(
                color = color.copy(alpha = 0.35f + 0.65f * age),
                topLeft = Offset(i * (barWidth + gap), (size.height - height) / 2f),
                size = Size(barWidth, height),
                cornerRadius = CornerRadius(barWidth / 2f),
            )
        }
    }
}

@Composable
private fun PillButton(
    label: String,
    background: androidx.compose.ui.graphics.Color,
    enabled: Boolean,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val scale by animateFloatAsState(if (enabled) 1f else 0.92f, label = "pillButton")
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(44.dp)
            .scale(scale)
            .clip(CircleShape)
            .background(background)
            .combinedClickable(enabled = enabled, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label },
    ) { content() }
}

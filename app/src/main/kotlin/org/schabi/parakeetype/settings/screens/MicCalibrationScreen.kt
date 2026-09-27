package org.schabi.parakeetype.settings.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mic
import android.os.SystemClock
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.viewmodel.compose.viewModel
import org.schabi.parakeetype.R
import org.schabi.parakeetype.audio.MicInfo
import org.schabi.parakeetype.audio.MicScore
import org.schabi.parakeetype.settings.preferences.CalibrationState
import org.schabi.parakeetype.settings.preferences.MicCalibrationViewModel
import org.schabi.parakeetype.settings.preferences.MicResult
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private val RecordGreen = Color(0xFF2E7D32)

/** Live "recording now" state — the pulsing capture indicator. */
private val CaptureRed = Color(0xFFD32F2F)

/** "Get ready" standby state — the calm capture indicator before each window opens. */
private val CaptureAmber = Color(0xFFEF6C00)

/**
 * Microphone calibration screen. Records a short "thanks" reference clip on every input
 * microphone, scores them by high-frequency energy + SNR, and persists the winner as the
 * preferred mic. Back navigation is handled by the settings nav host's top bar.
 */
@Composable
fun MicCalibrationScreen(
    viewModel: MicCalibrationViewModel = viewModel(),
) {
    val mics by viewModel.mics.collectAsState()
    val state by viewModel.state.collectAsState()
    val preferredMicId by viewModel.preferredMicId.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.calib_subtitle),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(R.string.calib_instructions),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))

        when (val s = state) {
            CalibrationState.Idle -> {
                if (mics.isEmpty()) {
                    Text(stringResource(R.string.calib_no_mics))
                } else {
                    mics.forEach { mic ->
                        MicRow(
                            mic = mic,
                            score = null,
                            isBest = false,
                            isCurrent = mic.id == preferredMicId,
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    RecordButton(
                        onClick = viewModel::startCalibration,
                        label = stringResource(R.string.calib_start),
                        enabled = mics.isNotEmpty(),
                    )
                }
            }

            is CalibrationState.GetReady -> {
                val mic = mics.getOrNull(s.index)
                if (mic != null) {
                    CaptureStage(mic = mic, index = s.index, total = s.total, isLive = false)
                }
            }

            is CalibrationState.Recording -> {
                val mic = mics.getOrNull(s.index)
                if (mic != null) {
                    CaptureStage(mic = mic, index = s.index, total = s.total, isLive = true)
                }
            }

            is CalibrationState.Done -> {
                Text(
                    text = stringResource(R.string.calib_done),
                    style = MaterialTheme.typography.titleMedium,
                )
                s.results
                    .sortedByDescending { it.score.score }
                    .forEach { result ->
                        MicRow(
                            mic = result.mic,
                            score = result.score,
                            isBest = result.mic.id == s.bestMicId,
                            isCurrent = false,
                        )
                    }
                if (s.bestMicId != 0) {
                    Text(
                        text = stringResource(R.string.calib_saved),
                        style = MaterialTheme.typography.bodySmall,
                        color = RecordGreen,
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    Surface(onClick = viewModel::reset) {
                        Text(
                            text = stringResource(R.string.calib_run_again),
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }

            is CalibrationState.Error -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = s.message,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Surface(onClick = viewModel::reset) {
                            Text(
                                text = stringResource(R.string.action_retry),
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * A single microphone row. When [score] is non-null (calibration complete) the high-frequency
 * percentage and SNR are shown; [isBest] / [isCurrent] add a badge on the trailing edge.
 */
@Composable
private fun MicRow(
    mic: MicInfo,
    score: MicScore?,
    isBest: Boolean,
    isCurrent: Boolean,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = mic.label, style = MaterialTheme.typography.bodyLarge)
                if (score != null) {
                    Text(
                        text = "${stringResource(R.string.calib_hf)} ${(score.hfFraction * 100f).roundToInt()}%  ·  " +
                            "${stringResource(R.string.calib_snr)} ${score.snrDb.roundToInt()} dB",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            when {
                isBest -> Text(
                    text = stringResource(R.string.calib_best),
                    style = MaterialTheme.typography.labelLarge,
                    color = RecordGreen,
                )
                isCurrent -> Text(
                    text = stringResource(R.string.calib_current),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** A circular green record-style button with a mic glyph and a caption beneath it. */
@Composable
private fun RecordButton(
    onClick: () -> Unit,
    label: String,
    enabled: Boolean,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(88.dp)
                .background(
                    color = if (enabled) RecordGreen else MaterialTheme.colorScheme.surfaceVariant,
                    shape = CircleShape,
                )
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Mic,
                contentDescription = label,
                tint = if (enabled) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(40.dp),
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * The full-screen capture stage shown while a microphone is being calibrated. It tells the user
 * which mic is next / live, and — via [isLive] — whether to speak now ([isLive] = true) or to
 * prepare ([isLive] = false, the "get ready" pause between mics).
 */
@Composable
private fun CaptureStage(
    mic: MicInfo,
    index: Int,
    total: Int,
    isLive: Boolean,
) {
    val fraction = rememberCaptureProgress(index, isLive)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.calib_mic_of, index + 1, total),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = mic.label,
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(modifier = Modifier.height(28.dp))
        CaptureIndicator(isLive = isLive)
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = stringResource(if (isLive) R.string.calib_speak_now else R.string.calib_get_ready),
            style = MaterialTheme.typography.headlineSmall,
            color = if (isLive) CaptureRed else CaptureAmber,
        )
        if (isLive) {
            Spacer(modifier = Modifier.height(20.dp))
            CaptureProgress(fraction = fraction)
        }
        Spacer(modifier = Modifier.height(28.dp))
        MicProgressDots(current = index, total = total, isLive = isLive)
    }
}

/**
 * Drives the capture progress [fraction] (0..1) over one [MicCalibrationViewModel.RECORD_MS]
 * window. Re-arms whenever [index] or [isLive] changes so each mic's bar starts empty.
 */
@Composable
private fun rememberCaptureProgress(index: Int, isLive: Boolean): Float {
    var fraction by remember { mutableStateOf(0f) }
    LaunchedEffect(index, isLive) {
        if (!isLive) {
            fraction = 0f
            return@LaunchedEffect
        }
        val duration = MicCalibrationViewModel.RECORD_MS.toLong()
        val start = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - start < duration) {
            delay(50L)
            fraction = ((SystemClock.elapsedRealtime() - start).toFloat() / duration).coerceIn(0f, 1f)
        }
        fraction = 1f
    }
    return fraction
}

/**
 * The large circular capture indicator, styled like the start button. In the live state it's
 * red and pulses with an expanding "ping" ring to signal "recording now"; otherwise it's a calm
 * amber standby.
 */
@Composable
private fun CaptureIndicator(isLive: Boolean) {
    val color = if (isLive) CaptureRed else CaptureAmber
    val transition = rememberInfiniteTransition(label = "capture")
    val ping by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = EaseOutCubic),
            repeatMode = RepeatMode.Restart,
        ),
        label = "ping",
    )
    val pingAlpha by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = EaseOutCubic),
            repeatMode = RepeatMode.Restart,
        ),
        label = "pingAlpha",
    )
    val scale by transition.animateFloat(
        initialValue = 1f,
        targetValue = if (isLive) 1.07f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = EaseOutCubic),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "scale",
    )

    Box(contentAlignment = Alignment.Center) {
        if (isLive) {
            Box(
                modifier = Modifier
                    .size(88.dp * ping)
                    .graphicsLayer {
                        alpha = pingAlpha
                    }
                    .background(color = color, shape = CircleShape),
            )
        }
        Box(
            modifier = Modifier
                .size(88.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .background(color = color, shape = CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Mic,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(40.dp),
            )
        }
    }
}

/** A determinate progress bar filling over the capture window. */
@Composable
private fun CaptureProgress(fraction: Float) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .background(CaptureRed, RoundedCornerShape(3.dp)),
        )
    }
}

/**
 * One dot per microphone: filled for captured, a ring for the current mic, outlined for the
 * upcoming ones.
 */
@Composable
private fun MicProgressDots(current: Int, total: Int, isLive: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        for (i in 0 until total) {
            val state = when {
                i < current -> DotState.Done
                i == current -> DotState.Current
                else -> DotState.Upcoming
            }
            Box(
                modifier = Modifier
                    .size(if (state == DotState.Current) 14.dp else 10.dp)
                    .background(
                        color = when (state) {
                            DotState.Done -> RecordGreen
                            DotState.Current -> if (isLive) CaptureRed else CaptureAmber
                            DotState.Upcoming -> MaterialTheme.colorScheme.surfaceVariant
                        },
                        shape = CircleShape,
                    ),
            )
        }
    }
}

private enum class DotState { Done, Current, Upcoming }

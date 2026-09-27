package dev.brgr.outspoke.ui.keyboard.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.brgr.outspoke.ui.theme.MyIcons
import dev.brgr.outspoke.ui.theme.OutspokeKeyboardTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

/**
 * A keyboard key that fires [onClick] the moment a finger touches it.
 *
 * The action is triggered directly from the pointer-down event. (The previous
 * implementation fired from a `LaunchedEffect` keyed on the pressed state, which only runs
 * on the next recomposition: a tap whose press and release both landed before the next
 * frame — a quick tap, or any tap while the main thread was busy injecting text — was
 * silently dropped.)
 *
 * @param repeatEnabled When `true` the action auto-repeats while held (500 ms initial
 *                      delay, then every 60 ms). When `false` it fires exactly once per press.
 * @param size          Visual size and touch target of the key.
 * @param iconSize      Size of the [icon] inside the key.
 * @param containerColor Background of the key; transparent by default (icon-only key).
 * @param shape         Clip shape for the background and the ripple.
 */
@Composable
fun KeyboardActionButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    repeatEnabled: Boolean = true,
    size: DpSize = DpSize(40.dp, 40.dp),
    iconSize: Dp = 22.dp,
    containerColor: Color = Color.Transparent,
    shape: Shape = CircleShape,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val currentOnClick by rememberUpdatedState(onClick)
    val scope = rememberCoroutineScope()

    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(containerColor)
            .indication(interactionSource, ripple())
            .pointerInput(repeatEnabled) {
                detectTapGestures(
                    onPress = { offset ->
                        val press = PressInteraction.Press(offset)
                        interactionSource.emit(press)
                        currentOnClick()
                        val repeatJob = if (repeatEnabled) {
                            scope.launch {
                                delay(500.milliseconds)
                                while (isActive) {
                                    currentOnClick()
                                    delay(60.milliseconds)
                                }
                            }
                        } else null
                        val released = tryAwaitRelease()
                        repeatJob?.cancel()
                        interactionSource.emit(
                            if (released) PressInteraction.Release(press) else PressInteraction.Cancel(press)
                        )
                    },
                )
            }
            .semantics(mergeDescendants = true) {
                role = Role.Button
                onClick {
                    currentOnClick()
                    true
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(iconSize),
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF111111)
@Composable
private fun KeyboardActionButtonAllPreview() {
    OutspokeKeyboardTheme {
        Row(
            modifier = Modifier.padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            KeyboardActionButton(
                icon = MyIcons.DeleteForever,
                contentDescription = "Delete all",
                onClick = {},
            )
            KeyboardActionButton(
                icon = MyIcons.BackspaceOutlined,
                contentDescription = "Delete word",
                onClick = {},
            )
            KeyboardActionButton(
                icon = MyIcons.SubdirectoryArrowLeft,
                contentDescription = "Enter",
                onClick = {},
                size = DpSize(64.dp, 52.dp),
                iconSize = 28.dp,
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                shape = RoundedCornerShape(16.dp),
            )
            KeyboardActionButton(
                icon = MyIcons.Keyboard,
                contentDescription = "Switch keyboard",
                onClick = {},
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF111111)
@Composable
private fun KeyboardActionButtonDisabledPreview() {
    OutspokeKeyboardTheme {
        KeyboardActionButton(
            icon = MyIcons.BackspaceOutlined,
            contentDescription = "Delete word (disabled)",
            onClick = {},
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
        )
    }
}


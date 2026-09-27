package dev.brgr.outspoke.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = Purple80,
    secondary = PurpleGrey80,
    tertiary = Pink80
)

private val LightColorScheme = lightColorScheme(
    primary = Purple40,
    secondary = PurpleGrey40,
    tertiary = Pink40
)

@Composable
fun OutspokeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}

// Keyboard colour schemes - one for each system theme setting.
private val KeyboardDarkColorScheme = darkColorScheme(
    primary = KeyboardAccent,
    onPrimary = KeyboardOnAccent,
    background = KeyboardBackground,
    onBackground = KeyboardOnSurface,
    surface = KeyboardSurface,
    onSurface = KeyboardOnSurface,
    surfaceVariant = KeyboardSurfaceVariant,
    onSurfaceVariant = KeyboardOnSurfaceVariant,
    error = KeyboardError,
)

private val KeyboardLightColorScheme = lightColorScheme(
    primary = KeyboardLightAccent,
    onPrimary = KeyboardLightOnAccent,
    background = KeyboardLightBackground,
    onBackground = KeyboardLightOnSurface,
    surface = KeyboardLightSurface,
    onSurface = KeyboardLightOnSurface,
    surfaceVariant = KeyboardLightSurfaceVariant,
    onSurfaceVariant = KeyboardLightOnSurfaceVariant,
    error = KeyboardLightError,
)

/**
 * Theme wrapper for the keyboard UI. Follows the system dark/light mode setting so the
 * keyboard blends naturally on both dark and light-themed devices.
 *
 * Also provides [LocalContentColor]: the keyboard is drawn on a plain background, not a
 * [Surface], so nothing else sets it and it would stay at its default (black). Ripples and
 * [IconButton] colours derive from it, which made every ripple black and therefore
 * invisible on the dark keyboard.
 */
@Composable
fun OutspokeKeyboardTheme(content: @Composable () -> Unit) {
    val colorScheme = if (isSystemInDarkTheme()) KeyboardDarkColorScheme else KeyboardLightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
    ) {
        CompositionLocalProvider(LocalContentColor provides colorScheme.onBackground, content = content)
    }
}





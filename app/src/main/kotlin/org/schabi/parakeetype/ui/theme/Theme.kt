package org.schabi.parakeetype.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext

/**
 * Material You colour scheme derived from the system (wallpaper) palette, following the system
 * dark/light mode. Always available: min SDK is 31. Shared by the keyboard, the companion app
 * and the voice-input sheet so all three look the same.
 */
@Composable
private fun systemColorScheme(): ColorScheme {
    val context = LocalContext.current
    return if (isSystemInDarkTheme()) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
}

@Composable
fun ParakeetypeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = systemColorScheme(),
        typography = Typography,
        content = content
    )
}

/**
 * Theme wrapper for the keyboard UI: the same system colours as [ParakeetypeTheme].
 *
 * Also provides [LocalContentColor]: the keyboard is drawn on a plain background, not a
 * [Surface], so nothing else sets it and it would stay at its default (black). Ripples and
 * [IconButton] colours derive from it, which made every ripple black and therefore
 * invisible on the dark keyboard.
 */
@Composable
fun ParakeetypeKeyboardTheme(content: @Composable () -> Unit) {
    val colorScheme = systemColorScheme()
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
    ) {
        CompositionLocalProvider(LocalContentColor provides colorScheme.onBackground, content = content)
    }
}

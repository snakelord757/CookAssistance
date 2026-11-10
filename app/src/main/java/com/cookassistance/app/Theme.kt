package com.cookassistance.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF286344),
    secondary = Color(0xFF82572D),
    background = Color(0xFFFAF9F5),
    surface = Color(0xFFFAF9F5),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA9D8B6),
    secondary = Color(0xFFE0B98A),
)

@Composable
fun CookTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (darkTheme) DarkColors else LightColors, content = content)
}

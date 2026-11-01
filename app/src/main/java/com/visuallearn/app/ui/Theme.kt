package com.visuallearn.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Ink on paper, to sit next to Anatomy's figures; one warm accent.
private val Accent = Color(0xFFC2603E)
private val AccentDark = Color(0xFFE08A68)

private val LightColors = lightColorScheme(
    primary = Color(0xFF1B1B19),
    onPrimary = Color(0xFFF7F3EA),
    primaryContainer = Color(0xFFE9E2D2),
    onPrimaryContainer = Color(0xFF1B1B19),
    secondary = Accent,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF4DCCF),
    onSecondaryContainer = Color(0xFF4A1F10),
    background = Color(0xFFF7F3EA),
    onBackground = Color(0xFF1B1B19),
    surface = Color(0xFFF7F3EA),
    onSurface = Color(0xFF1B1B19),
    surfaceVariant = Color(0xFFECE6D8),
    onSurfaceVariant = Color(0xFF5C5A52),
    surfaceContainer = Color(0xFFF0EBDF),
    surfaceContainerHigh = Color(0xFFEAE4D6),
    surfaceContainerLow = Color(0xFFF3EEE3),
    outline = Color(0xFFB9B3A3),
    outlineVariant = Color(0xFFD8D1C0),
    error = Color(0xFFB3261E),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFEDE8DC),
    onPrimary = Color(0xFF1B1B19),
    primaryContainer = Color(0xFF34332F),
    onPrimaryContainer = Color(0xFFEDE8DC),
    secondary = AccentDark,
    onSecondary = Color(0xFF2B1209),
    secondaryContainer = Color(0xFF5A2C1C),
    onSecondaryContainer = Color(0xFFFBDDD0),
    background = Color(0xFF151514),
    onBackground = Color(0xFFEDE8DC),
    surface = Color(0xFF151514),
    onSurface = Color(0xFFEDE8DC),
    surfaceVariant = Color(0xFF262624),
    onSurfaceVariant = Color(0xFFB4AFA3),
    surfaceContainer = Color(0xFF1D1D1B),
    surfaceContainerHigh = Color(0xFF262624),
    surfaceContainerLow = Color(0xFF1A1A18),
    outline = Color(0xFF5A5852),
    outlineVariant = Color(0xFF3A3935),
    error = Color(0xFFF2B8B5),
)

@Composable
fun VisualLearnTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, content = content)
}

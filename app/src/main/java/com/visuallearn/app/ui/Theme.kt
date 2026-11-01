package com.visuallearn.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.visuallearn.app.R

// A dark studio: figures are the only bright, detailed things on screen. Colour is kept
// for state (waiting, building, ready, failed), never for decoration.

/** Figure states, the only colours in the interface. Read on black and on white. */
object StatusColors {
    val Waiting = Color(0xFFE0A93B)
    val Building = Color(0xFF5B8CFF)
    val Ready = Color(0xFF34C77B)
    val Failed = Color(0xFFF2545B)
}

private val DarkColors = darkColorScheme(
    primary = Color(0xFFF4F4F1),
    onPrimary = Color(0xFF000000),
    primaryContainer = Color(0xFF1C1C1F),
    onPrimaryContainer = Color(0xFFF4F4F1),
    secondary = StatusColors.Building,
    onSecondary = Color(0xFF000000),
    secondaryContainer = Color(0xFF1C1C1F),
    onSecondaryContainer = Color(0xFFF4F4F1),
    background = Color(0xFF000000),
    onBackground = Color(0xFFF4F4F1),
    surface = Color(0xFF000000),
    onSurface = Color(0xFFF4F4F1),
    surfaceVariant = Color(0xFF1C1C1F),
    onSurfaceVariant = Color(0xFF8B8B92),
    surfaceContainerLowest = Color(0xFF000000),
    surfaceContainerLow = Color(0xFF0B0B0C),
    surfaceContainer = Color(0xFF121214),
    surfaceContainerHigh = Color(0xFF1C1C1F),
    surfaceContainerHighest = Color(0xFF26262A),
    outline = Color(0xFF3A3A40),
    outlineVariant = Color(0xFF26262A),
    error = StatusColors.Failed,
    onError = Color(0xFF000000),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF000000),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFEDEDEF),
    onPrimaryContainer = Color(0xFF000000),
    secondary = Color(0xFF2F64E8),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFEDEDEF),
    onSecondaryContainer = Color(0xFF000000),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF000000),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF000000),
    surfaceVariant = Color(0xFFEDEDEF),
    onSurfaceVariant = Color(0xFF6B6B73),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF8F8F9),
    surfaceContainer = Color(0xFFF3F3F5),
    surfaceContainerHigh = Color(0xFFEDEDEF),
    surfaceContainerHighest = Color(0xFFE4E4E7),
    outline = Color(0xFFD0D0D5),
    outlineVariant = Color(0xFFE4E4E7),
    error = Color(0xFFD63A42),
    onError = Color(0xFFFFFFFF),
)

private fun geist(weight: Int) = Font(
    R.font.geist,
    FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

val Geist = FontFamily(geist(400), geist(500), geist(600), geist(700))

private fun style(size: Int, line: Int, weight: Int, tracking: Double = 0.0) = TextStyle(
    fontFamily = Geist,
    fontSize = size.sp,
    lineHeight = line.sp,
    fontWeight = FontWeight(weight),
    letterSpacing = tracking.em,
)

// Headings set tight, the way display type is; body text left at its natural spacing.
private val VLTypography = Typography(
    displaySmall = style(34, 38, 600, -0.03),
    headlineLarge = style(30, 34, 600, -0.03),
    headlineMedium = style(26, 30, 600, -0.025),
    headlineSmall = style(22, 27, 600, -0.02),
    titleLarge = style(20, 26, 600, -0.015),
    titleMedium = style(17, 23, 600, -0.01),
    titleSmall = style(15, 20, 600, -0.005),
    bodyLarge = style(16, 24, 400),
    bodyMedium = style(15, 22, 400),
    bodySmall = style(13, 18, 400),
    labelLarge = style(15, 20, 500),
    labelMedium = style(13, 18, 500),
    labelSmall = style(12, 16, 500),
)

// Small for controls, large for the figure plates and the composer.
private val VLShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun VisualLearnTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = VLTypography,
        shapes = VLShapes,
        content = content,
    )
}

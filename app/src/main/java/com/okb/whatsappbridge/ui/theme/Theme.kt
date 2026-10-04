package com.okb.whatsappbridge.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Command-center palette: deep navy surfaces, amber "alert" accent, signal colours for status.
private val Navy950 = Color(0xFF07111B)
private val Navy900 = Color(0xFF0B1622)
private val Navy850 = Color(0xFF0F1E2D)
private val Navy800 = Color(0xFF142739)
private val Navy700 = Color(0xFF1D3449)
private val Steel400 = Color(0xFF8DA4BB)
private val Steel200 = Color(0xFFC9D6E3)
private val Amber = Color(0xFFF5A524)
private val AmberDeep = Color(0xFF9A5B00)

private val DarkColors = darkColorScheme(
    primary = Amber,
    onPrimary = Color(0xFF241500),
    primaryContainer = Color(0xFF3D2A06),
    onPrimaryContainer = Color(0xFFFFDDA8),
    secondary = Color(0xFF6FB3E0),
    onSecondary = Color(0xFF00243A),
    secondaryContainer = Color(0xFF16344A),
    onSecondaryContainer = Color(0xFFCDE6F7),
    background = Navy900,
    onBackground = Color(0xFFE6EDF3),
    surface = Navy900,
    onSurface = Color(0xFFE6EDF3),
    surfaceVariant = Navy800,
    onSurfaceVariant = Steel400,
    surfaceContainerLowest = Navy950,
    surfaceContainerLow = Navy850,
    surfaceContainer = Navy850,
    surfaceContainerHigh = Navy800,
    surfaceContainerHighest = Navy700,
    outline = Color(0xFF2E4760),
    outlineVariant = Color(0xFF1F3347),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF3A0006),
    errorContainer = Color(0xFF4A1218),
    onErrorContainer = Color(0xFFFFD9DB),
)

private val LightColors = lightColorScheme(
    primary = AmberDeep,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE2B5),
    onPrimaryContainer = Color(0xFF2D1A00),
    secondary = Color(0xFF1F5F8B),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD3E6F5),
    onSecondaryContainer = Color(0xFF062236),
    background = Color(0xFFF1F4F8),
    onBackground = Navy900,
    surface = Color(0xFFF1F4F8),
    onSurface = Navy900,
    surfaceVariant = Color(0xFFE2E8EF),
    onSurfaceVariant = Color(0xFF475A6D),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color(0xFFF7F9FB),
    surfaceContainerHighest = Color(0xFFE9EEF3),
    outline = Color(0xFFB7C4D1),
    outlineVariant = Color(0xFFD6DEE6),
    error = Color(0xFFB3261E),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
)

/** Signal colours for status indicators; tuned per theme for contrast. */
@Immutable
data class StatusColors(
    val ok: Color,
    val warning: Color,
    val error: Color,
    val neutral: Color,
    val info: Color,
)

private val DarkStatus = StatusColors(
    ok = Color(0xFF3DD68C),
    warning = Amber,
    error = Color(0xFFFF6B6B),
    neutral = Steel400,
    info = Color(0xFF6FB3E0),
)

private val LightStatus = StatusColors(
    ok = Color(0xFF1B7F45),
    warning = Color(0xFF9A5B00),
    error = Color(0xFFB3261E),
    neutral = Color(0xFF5B6E80),
    info = Color(0xFF1F5F8B),
)

val LocalStatusColors = staticCompositionLocalOf { DarkStatus }

val MonoFamily = FontFamily.Monospace

private val BaseTypography = Typography()

private val BridgeTypography = Typography(
    displaySmall = BaseTypography.displaySmall.copy(fontWeight = FontWeight.Bold),
    headlineMedium = BaseTypography.headlineMedium.copy(fontWeight = FontWeight.Bold),
    headlineSmall = BaseTypography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = BaseTypography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = BaseTypography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    titleSmall = BaseTypography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    bodyLarge = BaseTypography.bodyLarge,
    bodyMedium = BaseTypography.bodyMedium,
    bodySmall = BaseTypography.bodySmall,
    labelLarge = BaseTypography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    labelMedium = BaseTypography.labelMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp),
    labelSmall = BaseTypography.labelSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp),
)

/** Monospace style for timestamps, ids and counters. */
val MonoValue = TextStyle(fontFamily = MonoFamily, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)

@Composable
fun OkbBridgeTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalStatusColors provides if (darkTheme) DarkStatus else LightStatus) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = BridgeTypography,
            content = content,
        )
    }
}

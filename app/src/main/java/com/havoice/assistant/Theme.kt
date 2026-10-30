package com.havoice.assistant

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Design tokens copied from the Home Assistant Companion app (Apache 2.0) so this
// panel looks like the original Assist sheet.

object HaDimens {
    val Space1 = 4.dp
    val Space2 = 8.dp
    val Space3 = 12.dp
    val Space4 = 16.dp
    val Space5 = 20.dp
    val Space6 = 24.dp
    val Space16 = 64.dp
}

object HaRadius {
    val S = 4.dp
    val M = 8.dp
    val XL = 16.dp
    val X3L = 24.dp
}

private object HaPalette {
    val White = Color(0xFFFFFFFF)
    val Black = Color(0xFF000000)
    val Primary05 = Color(0xFF001721)
    val Primary40 = Color(0xFF009AC7)
    val Primary50 = Color(0xFF18BCF2)
    val Primary60 = Color(0xFF37C8FD)
    val Primary70 = Color(0xFF7BD4FB)
    val Primary95 = Color(0xFFEFF9FE)
    val Neutral05 = Color(0xFF141414)
    val Neutral10 = Color(0xFF202020)
    val Neutral40 = Color(0xFF5E5E5E)
    val Neutral50 = Color(0xFF7A7A7A)
    val Neutral60 = Color(0xFF989898)
    val Neutral70 = Color(0xFFB1B1B1)
    val Neutral80 = Color(0xFFCCCCCC)
    val Neutral95 = Color(0xFFF3F3F3)
    val Red40 = Color(0xFFB30532)
}

data class HaColorScheme(
    val fillPrimaryLoud: Color,
    val fillPrimaryQuiet: Color,
    val onPrimaryLoud: Color,
    val onPrimaryNormal: Color,
    val fillDangerLoud: Color,
    val onDangerLoud: Color,
    val onNeutralQuiet: Color,
    val onDisabled: Color,
    val surfaceDefault: Color,
    val surfaceLow: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val borderNeutralQuiet: Color,
    val borderNeutralNormal: Color,
    val borderPrimaryNormal: Color,
    val overlayModal: Color,
)

private val LightHaColors = HaColorScheme(
    fillPrimaryLoud = HaPalette.Primary40,
    fillPrimaryQuiet = HaPalette.Primary95,
    onPrimaryLoud = HaPalette.White,
    onPrimaryNormal = HaPalette.Primary40,
    fillDangerLoud = HaPalette.Red40,
    onDangerLoud = HaPalette.White,
    onNeutralQuiet = HaPalette.Neutral50,
    onDisabled = HaPalette.Neutral70,
    surfaceDefault = HaPalette.White,
    surfaceLow = HaPalette.Neutral95,
    textPrimary = HaPalette.Neutral05,
    textSecondary = HaPalette.Neutral40,
    borderNeutralQuiet = HaPalette.Neutral80,
    borderNeutralNormal = HaPalette.Neutral70,
    borderPrimaryNormal = HaPalette.Primary70,
    overlayModal = HaPalette.Black.copy(alpha = 0.25f),
)

private val DarkHaColors = HaColorScheme(
    fillPrimaryLoud = HaPalette.Primary40,
    fillPrimaryQuiet = HaPalette.Primary05,
    onPrimaryLoud = HaPalette.White,
    onPrimaryNormal = HaPalette.Primary60,
    fillDangerLoud = HaPalette.Red40,
    onDangerLoud = HaPalette.White,
    onNeutralQuiet = HaPalette.Neutral70,
    onDisabled = HaPalette.Neutral60,
    surfaceDefault = HaPalette.Neutral10,
    surfaceLow = HaPalette.Neutral05,
    textPrimary = HaPalette.White,
    textSecondary = HaPalette.Neutral70,
    borderNeutralQuiet = HaPalette.Neutral40,
    borderNeutralNormal = HaPalette.Neutral50,
    borderPrimaryNormal = HaPalette.Primary50,
    overlayModal = HaPalette.Black.copy(alpha = 0.25f),
)

val LocalHaColors = staticCompositionLocalOf { LightHaColors }

object HaText {
    val HeadlineMedium = TextStyle(fontSize = 28.sp, lineHeight = 40.sp, fontWeight = FontWeight.W500)
    val Body = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.sp)
    val BodyMedium = Body.copy(fontSize = 14.sp, lineHeight = 20.sp)
}

/** Home Assistant's look, following the phone's light/dark mode. */
@Composable
fun HaVoiceTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ha = if (dark) DarkHaColors else LightHaColors
    val base = if (dark) darkColorScheme() else lightColorScheme()
    // Material components (dropdowns, text fields, buttons) pick up the HA colors too.
    val material = base.copy(
        primary = ha.fillPrimaryLoud,
        onPrimary = ha.onPrimaryLoud,
        primaryContainer = ha.fillPrimaryQuiet,
        background = ha.surfaceDefault,
        onBackground = ha.textPrimary,
        surface = ha.surfaceDefault,
        onSurface = ha.textPrimary,
        surfaceVariant = ha.surfaceLow,
        onSurfaceVariant = ha.textSecondary,
        surfaceContainer = ha.surfaceDefault,
        surfaceContainerLow = ha.surfaceDefault,
        surfaceContainerHigh = ha.surfaceDefault,
        outline = ha.borderNeutralNormal,
        outlineVariant = ha.borderNeutralQuiet,
        error = ha.fillDangerLoud,
    )
    CompositionLocalProvider(LocalHaColors provides ha) {
        MaterialTheme(colorScheme = material, content = content)
    }
}

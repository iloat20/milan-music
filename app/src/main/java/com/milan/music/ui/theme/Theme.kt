package com.milan.music.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Amber,
    onPrimary = Color.White,
    primaryContainer = AmberPale,
    onPrimaryContainer = GoldInk,
    secondary = AmberBright,
    onSecondary = GoldInk,
    background = Paper,
    onBackground = Ink,
    surface = Cream,
    onSurface = Ink,
    surfaceVariant = PaperDeep,
    onSurfaceVariant = InkMuted,
    outline = Color(0x331B1713),
    outlineVariant = Color(0x1F1B1713),
    error = Color(0xFFB3261E),
    onError = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = AmberBright,
    onPrimary = Color(0xFF2E2410),
    primaryContainer = Color(0xFF4A3714),
    onPrimaryContainer = Moonlight,
    secondary = Amber,
    onSecondary = Moonlight,
    background = Night,
    onBackground = Moonlight,
    surface = NightRaised,
    onSurface = Moonlight,
    surfaceVariant = Color(0xFF2A2217),
    onSurfaceVariant = NightMuted,
    outline = Color(0x3DFFFFFF),
    outlineVariant = Color(0x1FFFFFFF),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
)

data class GlassColors(
    val fillTop: Color,
    val fillBottom: Color,
    val borderTop: Color,
    val borderBottom: Color,
    val sheen: Color,
    val hairline: Color,
    val isDark: Boolean,
)

val LocalGlassColors = staticCompositionLocalOf {
    GlassColors(
        fillTop = GlassLightTop,
        fillBottom = GlassLightBottom,
        borderTop = Color(0xE6FFFFFF),
        borderBottom = Color(0x26FFFFFF),
        sheen = Sheen,
        hairline = HairlineGold,
        isDark = false,
    )
}

@Composable
fun MilanTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val glass = if (darkTheme) {
        GlassColors(
            fillTop = GlassDarkTop,
            fillBottom = GlassDarkBottom,
            borderTop = Color(0x4DFFFFFF),
            borderBottom = Color(0x0FFFFFFF),
            sheen = Color(0x1FFFF3D0),
            hairline = Color(0x33E7CE93),
            isDark = true,
        )
    } else {
        GlassColors(
            fillTop = GlassLightTop,
            fillBottom = GlassLightBottom,
            borderTop = Color(0xE6FFFFFF),
            borderBottom = Color(0x26FFFFFF),
            sheen = Sheen,
            hairline = HairlineGold,
            isDark = false,
        )
    }

    CompositionLocalProvider(LocalGlassColors provides glass) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = MilanTypography,
            content = content,
        )
    }
}

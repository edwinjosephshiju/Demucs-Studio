package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val StudioColorScheme = darkColorScheme(
    primary = VocalCyan,
    onPrimary = Color.Black,
    primaryContainer = VocalCyanMuted,
    onPrimaryContainer = Color.White,
    secondary = KaraokeAmber,
    onSecondary = Color.Black,
    secondaryContainer = KaraokeAmberMuted,
    onSecondaryContainer = Color.White,
    tertiary = StudioLedGreen,
    onTertiary = Color.Black,
    background = StudioBackground,
    onBackground = StudioTextPrimary,
    surface = StudioSurface,
    onSurface = StudioTextPrimary,
    surfaceVariant = StudioSurfaceElevated,
    onSurfaceVariant = StudioTextSecondary,
    outline = StudioCardBorder,
    error = StudioMuteRed,
    onError = Color.White
)

@Composable
fun DemucsStudioTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = StudioColorScheme,
        typography = Typography,
        content = content
    )
}

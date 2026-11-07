package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val TacticalDarkColorScheme = darkColorScheme(
    primary = CyanPrimary,
    onPrimary = Color(0xFF002029),
    primaryContainer = Color(0xFF003644),
    onPrimaryContainer = Color(0xFF8CEEFF),

    secondary = EmeraldSecure,
    onSecondary = Color(0xFF003919),
    secondaryContainer = Color(0xFF005327),
    onSecondaryContainer = Color(0xFF70FF9D),

    tertiary = AmberMesh,
    onTertiary = Color(0xFF3E2800),
    tertiaryContainer = Color(0xFF5A3C00),
    onTertiaryContainer = Color(0xFFFFDFA0),

    background = TacticalBackground,
    onBackground = TextPrimary,

    surface = TacticalSurface,
    onSurface = TextPrimary,

    surfaceVariant = TacticalSurfaceVariant,
    onSurfaceVariant = TextSecondary,

    outline = TacticalBorder,
    outlineVariant = Color(0xFF1B2F4E),

    error = RedAlert,
    onError = Color(0xFF410002)
)

private val TacticalLightColorScheme = lightColorScheme(
    primary = Color(0xFF00677F),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB5E4F5),
    onPrimaryContainer = Color(0xFF001F28),

    secondary = Color(0xFF006E34),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF70FF9D),
    onSecondaryContainer = Color(0xFF00210B),

    tertiary = Color(0xFF7D5700),
    onTertiary = Color.White,

    background = Color(0xFFF6F8FC),
    onBackground = Color(0xFF121B26),

    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF121B26),

    surfaceVariant = Color(0xFFDFE6F0),
    onSurfaceVariant = Color(0xFF404A56),

    outline = Color(0xFFC0CCDC),
    error = RedAlert,
    onError = Color.White
)

@Composable
fun CipherMeshTheme(
    darkTheme: Boolean = true, // Default to sleek tactical dark mode for cyber-mesh aesthetic
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) TacticalDarkColorScheme else TacticalLightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}

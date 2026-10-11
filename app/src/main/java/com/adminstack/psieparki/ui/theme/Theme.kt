package com.adminstack.psieparki.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Grass green and a ball-toy orange. Fixed rather than dynamic colour: the screenshots
// in the store should look like the app people get.
val Grass = Color(0xFF2E6B3F)
val GrassLight = Color(0xFF8FD19E)
val Ball = Color(0xFFE08A1E)

private val Light = lightColorScheme(
    primary = Grass,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCDEBD3),
    onPrimaryContainer = Color(0xFF0B2913),
    secondary = Color(0xFF8A5100),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFDDB8),
    onSecondaryContainer = Color(0xFF2C1700),
    tertiary = Color(0xFF3B6470),
    background = Color(0xFFF7FAF4),
    surface = Color(0xFFF7FAF4),
    surfaceVariant = Color(0xFFDDE5DA),
    surfaceContainer = Color(0xFFECF0E8),
    surfaceContainerHigh = Color(0xFFE6EAE2),
    outlineVariant = Color(0xFFC1C9BE),
)

private val Dark = darkColorScheme(
    primary = GrassLight,
    onPrimary = Color(0xFF003916),
    primaryContainer = Color(0xFF145226),
    onPrimaryContainer = Color(0xFFCDEBD3),
    secondary = Color(0xFFFFB866),
    onSecondary = Color(0xFF4A2800),
    secondaryContainer = Color(0xFF693C00),
    onSecondaryContainer = Color(0xFFFFDDB8),
    tertiary = Color(0xFFA3CDDB),
    background = Color(0xFF101510),
    surface = Color(0xFF101510),
    surfaceVariant = Color(0xFF414941),
    surfaceContainer = Color(0xFF1C211C),
    surfaceContainerHigh = Color(0xFF262B26),
    outlineVariant = Color(0xFF414941),
)

@Composable
fun PsieParkiTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
}

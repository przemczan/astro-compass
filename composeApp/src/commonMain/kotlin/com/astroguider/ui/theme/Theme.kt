package com.astroguider.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

enum class AppTheme { Light, Dark, Night }

/**
 * Night mode uses red-on-black deliberately: red light preserves the eye's dark adaptation
 * at the eyepiece, unlike the standard dark theme's white/blue text. This is the one place
 * the app hardcodes colors instead of using stock Material 3 roles.
 */
private val NightColorScheme = darkColorScheme(
    primary = Color(0xFFFF5252),
    onPrimary = Color(0xFF1A0000),
    primaryContainer = Color(0xFF4D0000),
    onPrimaryContainer = Color(0xFFFFB3B3),
    secondary = Color(0xFFCF6679),
    background = Color.Black,
    onBackground = Color(0xFFFF5252),
    surface = Color(0xFF0D0000),
    onSurface = Color(0xFFFF5252),
    surfaceVariant = Color(0xFF1A0505),
    onSurfaceVariant = Color(0xFFE08080),
    error = Color(0xFFFF8A80),
    outline = Color(0xFF7A2E2E),
)

@Composable
fun GuiderTheme(
    appTheme: AppTheme = if (isSystemInDarkTheme()) AppTheme.Dark else AppTheme.Light,
    content: @Composable () -> Unit,
) {
    val colorScheme = when (appTheme) {
        AppTheme.Light -> lightColorScheme()
        AppTheme.Dark -> darkColorScheme()
        AppTheme.Night -> NightColorScheme
    }
    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}

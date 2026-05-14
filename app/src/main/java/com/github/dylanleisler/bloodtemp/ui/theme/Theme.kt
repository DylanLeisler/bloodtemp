package com.github.dylanleisler.bloodtemp.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val BloodTempColorScheme = darkColorScheme(
    primary = BloodRed,
    onPrimary = BoneWhite,
    primaryContainer = BloodRedDark,
    onPrimaryContainer = BoneWhite,
    secondary = EmberOrange,
    onSecondary = BlackSteel,
    background = BlackSteel,
    onBackground = BoneWhite,
    surface = DarkGray,
    onSurface = BoneWhite,
    surfaceVariant = MediumGray,
    onSurfaceVariant = AshenGray,
    outline = WarmGray,
    error = Color(0xFFCF6679),
    onError = BlackSteel,
)

@Composable
fun BloodTempTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = BloodTempColorScheme,
        typography = Typography,
        content = content
    )
}

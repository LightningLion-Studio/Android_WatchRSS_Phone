package com.lightningstudio.watchrss.phone.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.isSystemInDarkTheme

private val LightColors = lightColorScheme(
    primary = PrimaryRed,
    onPrimary = OnPrimary,
    primaryContainer = GradientMid,
    onPrimaryContainer = OnPrimary,
    secondary = PrimaryRedLight,
    onSecondary = OnPrimary,
    secondaryContainer = GradientEnd,
    onSecondaryContainer = PrimaryRedDark,
    tertiary = PrimaryRedDark,
    onTertiary = OnPrimary,
    background = LightBackground,
    onBackground = OnBackgroundLight,
    surface = LightSurface,
    onSurface = OnBackgroundLight,
    surfaceVariant = LightCardStart,
    onSurfaceVariant = OnSurfaceVariantLight,
    outline = PrimaryRedLight.copy(alpha = 0.5f),
    error = PrimaryRed,
    onError = OnPrimary
)

private val DarkColors = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = OnPrimary,
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = DarkOnPrimaryContainer,
    secondary = PrimaryRedLight,
    onSecondary = OnPrimary,
    secondaryContainer = DarkSurfaceElevated,
    onSecondaryContainer = DarkPrimary,
    tertiary = GradientMid,
    onTertiary = OnPrimary,
    background = DarkBackground,
    onBackground = OnBackgroundDark,
    surface = DarkSurface,
    onSurface = OnBackgroundDark,
    surfaceVariant = DarkSurfaceElevated,
    onSurfaceVariant = OnSurfaceVariantDark,
    outline = DarkPrimary.copy(alpha = 0.4f),
    error = PrimaryRedLight,
    onError = OnPrimary
)

@Composable
fun WatchRssPhoneTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content
    )
}

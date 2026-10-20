package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val HephaistosDarkColorScheme = darkColorScheme(
    primary = HephaistosOrange,
    onPrimary = Color.Black,
    primaryContainer = HephaistosOrangeDark,
    onPrimaryContainer = Color.White,
    secondary = HephaistosCyan,
    onSecondary = Color.Black,
    secondaryContainer = Color(0xFF004D5A),
    onSecondaryContainer = HephaistosCyan,
    tertiary = HephaistosOrangeLight,
    background = CyberBlack,
    onBackground = TextPrimaryDark,
    surface = CyberDarkSurface,
    onSurface = TextPrimaryDark,
    surfaceVariant = CyberCardSurface,
    onSurfaceVariant = TextSecondaryDark,
    outline = CyberBorder,
    outlineVariant = Color(0xFF232936)
)

private val HephaistosLightColorScheme = lightColorScheme(
    primary = LightPrimary,
    onPrimary = LightOnPrimary,
    primaryContainer = LightPrimaryContainer,
    onPrimaryContainer = LightOnPrimaryContainer,
    secondary = Color(0xFF007A8A),
    onSecondary = Color.White,
    tertiary = HephaistosOrangeDark,
    background = LightBackground,
    onBackground = Color(0xFF1F1B18),
    surface = LightSurface,
    onSurface = Color(0xFF1F1B18),
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = Color(0xFF50453E),
    outline = Color(0xFF82746C),
    outlineVariant = Color(0xFFD4C4B9)
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Follows host HephaistOS system dynamic color automatically
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> HephaistosDarkColorScheme
        else -> HephaistosLightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}

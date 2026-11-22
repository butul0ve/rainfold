package com.rainfold.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

@Composable
fun RainfoldTheme(darkTheme: Boolean, amoled: Boolean = false, content: @Composable () -> Unit) {
    val colors = if (darkTheme || amoled) {
        darkColorScheme(
            primary = Color(0xFFD3BCFF),
            onPrimary = Color(0xFF34106B),
            primaryContainer = Color(0xFF4C237F),
            onPrimaryContainer = Color(0xFFECDDFF),
            secondary = Color(0xFFFFB2C8),
            onSecondary = Color(0xFF5F1030),
            secondaryContainer = Color(0xFF792147),
            onSecondaryContainer = Color(0xFFFFD9E3),
            tertiary = Color(0xFF75E5D6),
            onTertiary = Color(0xFF003731),
            surface = Color(0xFF151326),
            onSurface = Color(0xFFE8E0F7),
            surfaceVariant = Color(0xFF24213B),
            onSurfaceVariant = Color(0xFFCBC2D8),
            surfaceContainerHigh = Color(0xFF302C49),
            surfaceContainerHighest = Color(0xFF3A3653),
        )
    } else {
        lightColorScheme(
            primary = Color(0xFF6D3FA3),
            onPrimary = Color.White,
            primaryContainer = Color(0xFFECDCFF),
            onPrimaryContainer = Color(0xFF291044),
            secondary = Color(0xFFA73760),
            onSecondary = Color.White,
            secondaryContainer = Color(0xFFFFD9E3),
            onSecondaryContainer = Color(0xFF3D001B),
            tertiary = Color(0xFF087B71),
            onTertiary = Color.White,
            surface = Color(0xFFFFF8FF),
            onSurface = Color(0xFF201A23),
            surfaceVariant = Color(0xFFF3E8F4),
            onSurfaceVariant = Color(0xFF4E4652),
            surfaceContainerHigh = Color(0xFFF9EFF9),
            surfaceContainerHighest = Color(0xFFF0E6F0),
        )
    }
    MaterialTheme(colorScheme = if (amoled) colors.copy(
        background = Color.Black,
        surface = Color.Black,
        surfaceContainerLow = Color(0xFF080808),
        surfaceContainer = Color(0xFF101010),
        surfaceContainerHigh = Color(0xFF181818),
        surfaceContainerHighest = Color(0xFF202020),
    ) else colors, content = content)
}
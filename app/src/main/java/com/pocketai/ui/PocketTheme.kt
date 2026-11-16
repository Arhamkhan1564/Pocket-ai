package com.pocketai.ui

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val PocketAccent = Color(0xFFB6E875)
val PocketMuted = Color(0xFF9FAAAA)
val PocketSurface = Color(0xFF172023)

@Composable
fun PocketTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = PocketAccent, onPrimary = Color(0xFF1B300D),
            secondary = Color(0xFF99D8D2), background = Color(0xFF0D1518),
            primaryContainer = Color(0xFF263C1D), onPrimaryContainer = PocketAccent,
            secondaryContainer = Color(0xFF263A36), onSecondaryContainer = PocketAccent,
            onBackground = Color(0xFFE7EFEC), surface = PocketSurface,
            onSurface = Color(0xFFE7EFEC), surfaceVariant = Color(0xFF243034),
            onSurfaceVariant = Color(0xFFB8C5C4), outline = Color(0xFF3B4A4D),
            error = Color(0xFFFFB4AB)
        ),
        content = content
    )
}

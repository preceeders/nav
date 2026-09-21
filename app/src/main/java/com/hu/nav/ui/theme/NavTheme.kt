package com.hu.nav.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val NavAccent = Color(0xFF5B5CFF)
val NavAccentDark = Color(0xFF4A4BE0)
val NavMuted = Color(0xFFF3F4F8)
val NavStroke = Color(0xFFE4E6EF)

private val scheme = lightColorScheme(
    primary = NavAccent,
    onPrimary = Color.White,
    secondary = NavAccentDark,
    background = Color(0xFFF7F7FB),
    surface = Color.White,
    outline = NavStroke,
)

@Composable
fun NavTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}

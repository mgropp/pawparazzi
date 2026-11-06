package io.gropp.pawparazzi.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Lime = Color(0xFFA0DE21)

private val PawparazziColors = darkColorScheme(primary = Lime, onPrimary = Color.Black)

@Composable
fun PawparazziTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = PawparazziColors, content = content)
}

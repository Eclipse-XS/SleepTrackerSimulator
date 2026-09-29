package com.example.sleeptrackersimulator.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColors = lightColorScheme(
    primary = NightBlue,
    secondary = CalmGreen,
    tertiary = MotionAmber,
    surface = MistBlue,
)

private val DarkColors = darkColorScheme(
    primary = MoonBlue,
    secondary = CalmGreen,
    tertiary = MotionAmber,
    surface = DarkSurface,
)

@Composable
fun SleepTrackerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}

package com.testconnection.confidence_agent.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val AppColors = lightColorScheme(
    primary = Sage,
    onPrimary = WarmWhite,
    primaryContainer = SagePale,
    onPrimaryContainer = Ink,
    secondary = Terracotta,
    onSecondary = WarmWhite,
    secondaryContainer = TerracottaPale,
    onSecondaryContainer = Ink,
    background = Cream,
    onBackground = Ink,
    surface = WarmWhite,
    onSurface = Ink,
    surfaceVariant = CreamDeep,
    onSurfaceVariant = InkMuted,
    outline = WarmOutline,
    error = Danger,
)

@Composable
fun ConfidenceAgentTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = AppColors, typography = Typography, content = content)
}

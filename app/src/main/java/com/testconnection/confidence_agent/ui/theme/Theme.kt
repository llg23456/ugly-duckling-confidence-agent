package com.testconnection.confidence_agent.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

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
    surfaceContainer = WarmWhite,
    surfaceContainerHigh = Cream,
    onSurface = Ink,
    surfaceVariant = CreamDeep,
    onSurfaceVariant = InkMuted,
    outline = WarmOutline,
    error = Danger,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfidenceAgentTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = AppColors, typography = Typography) {
        CompositionLocalProvider(LocalRippleConfiguration provides null) {
            content()
        }
    }
}

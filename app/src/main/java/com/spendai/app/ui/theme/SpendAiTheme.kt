package com.spendai.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

/**
 * Brand theme for SpendAI. Dynamic color is intentionally disabled
 * so the banana + mango palette stays consistent on Android 12+
 * devices that would otherwise tint everything from the user's
 * wallpaper.
 */
@Composable
fun SpendAiTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) SpendAiDarkColors else SpendAiLightColors,
        typography = SpendAiTypography,
        shapes = SpendAiShapes,
        content = content,
    )
}

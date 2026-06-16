package com.spendai.app.ui.theme

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

/**
 * Material 3 theme for SpendAI. Uses dynamic colors on API 31+ and falls
 * back to a quiet, neutral green-leaning scheme below that. The fallback
 * palette deliberately avoids the project's discouraged one-note hues
 * (purple/blue gradients, beige, espresso) — it is a plain teal-on-neutral
 * scheme suitable for a utility app.
 */
@Composable
fun SpendAiTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkFallback
        else -> LightFallback
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = SpendAiTypography,
        content = content,
    )
}

private val LightFallback = lightColorScheme(
    primary = Color(0xFF1B6B5A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFA8F1DC),
    onPrimaryContainer = Color(0xFF002019),
    secondary = Color(0xFF4C6358),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCEE9DA),
    onSecondaryContainer = Color(0xFF082017),
    background = Color(0xFFFBFDF8),
    onBackground = Color(0xFF191C1A),
    surface = Color(0xFFFBFDF8),
    onSurface = Color(0xFF191C1A),
    surfaceVariant = Color(0xFFDBE5DE),
    onSurfaceVariant = Color(0xFF404944),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
)

private val DarkFallback = darkColorScheme(
    primary = Color(0xFF8CD4C0),
    onPrimary = Color(0xFF00382C),
    primaryContainer = Color(0xFF005141),
    onPrimaryContainer = Color(0xFFA8F1DC),
    secondary = Color(0xFFB3CCBE),
    onSecondary = Color(0xFF1F352B),
    secondaryContainer = Color(0xFF354B41),
    onSecondaryContainer = Color(0xFFCEE9DA),
    background = Color(0xFF101412),
    onBackground = Color(0xFFE1E3DF),
    surface = Color(0xFF101412),
    onSurface = Color(0xFFE1E3DF),
    surfaceVariant = Color(0xFF404944),
    onSurfaceVariant = Color(0xFFBFC9C2),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

package com.spendai.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.spendai.app.R

/**
 * Fredoka is the display font - rounded, chunky, reads as cartoon.
 * Body stays on the platform sans for legibility at small sizes.
 *
 * Fredoka ships from Google Fonts as a single variable font with both
 * the width and weight axes (Fredoka[wdth,wght].ttf). We declare two
 * [Font] entries - one weighted Normal, one weighted Bold - and pin
 * the weight axis via [FontVariation.weight] so the renderer requests
 * the exact weight instead of synthesizing it.
 */
@OptIn(ExperimentalTextApi::class)
private val Fredoka = FontFamily(
    Font(
        resId = R.font.fredoka_variable,
        weight = FontWeight.Normal,
        style = FontStyle.Normal,
        variationSettings = FontVariation.Settings(
            FontVariation.weight(FontWeight.Normal.weight),
        ),
    ),
    Font(
        resId = R.font.fredoka_variable,
        weight = FontWeight.Bold,
        style = FontStyle.Normal,
        variationSettings = FontVariation.Settings(
            FontVariation.weight(FontWeight.Bold.weight),
        ),
    ),
)

private fun display(size: Int, lineHeight: Int, weight: FontWeight = FontWeight.Bold) = TextStyle(
    fontFamily = Fredoka,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = 0.sp,
)

private fun body(size: Int, lineHeight: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = 0.sp,
)

val SpendAiTypography: Typography = Typography(
    displayLarge = display(48, 56),
    displayMedium = display(36, 44),
    displaySmall = display(32, 40),
    headlineLarge = display(28, 36),
    headlineMedium = display(24, 32),
    headlineSmall = display(22, 30),
    titleLarge = display(20, 28, FontWeight.Bold),
    titleMedium = display(17, 24, FontWeight.Bold),
    titleSmall = display(14, 20, FontWeight.Bold),
    bodyLarge = body(16, 24),
    bodyMedium = body(14, 20),
    bodySmall = body(12, 16),
    labelLarge = display(14, 20, FontWeight.Bold),
    labelMedium = display(12, 16, FontWeight.Bold),
    labelSmall = display(11, 16, FontWeight.Bold),
)

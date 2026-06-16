package com.spendai.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// Light scheme - banana cream + mango + dark-ink borders.
// The "ink" tone (Outline) is the brand border color. Primary is the
// saturated mango used for CTAs; tertiary is a teal pop so we can
// break out of the yellow family without abandoning the brand.
private val BananaCream = Color(0xFFFFF7D6)
private val BananaSurface = Color(0xFFFFFAE5)
private val BananaSurfaceVariant = Color(0xFFFFE8A0)
private val Mango = Color(0xFFFFB300)
private val OnMango = Color(0xFF1A0F00)
private val MangoContainer = Color(0xFFFFD966)
private val OnMangoContainer = Color(0xFF3B2400)
private val Tangerine = Color(0xFFFF7A00)
private val OnTangerine = Color(0xFFFFFFFF)
private val TangerineContainer = Color(0xFFFFB870)
private val OnTangerineContainer = Color(0xFF3B1F00)
private val TealPop = Color(0xFF0F9E8E)
private val OnTealPop = Color(0xFFFFFFFF)
private val TealPopContainer = Color(0xFF9DEAE0)
private val OnTealPopContainer = Color(0xFF003B33)
private val InkLight = Color(0xFF1A0F00)
private val InkLightVariant = Color(0xFF7A5C00)
private val OnBananaLight = Color(0xFF1A0F00)
private val OnBananaLightVariant = Color(0xFF5C4A00)
private val DangerRed = Color(0xFFD50000)
private val OnDangerRed = Color(0xFFFFFFFF)
private val DangerRedContainer = Color(0xFFFFB4A2)
private val OnDangerRedContainer = Color(0xFF410001)

// Dark cousin - midnight indigo with hot-yellow primary and a
// yellow-tinted outline. The outline doubles as the offset shadow
// color in StickerCard so the cartoon border keeps reading on dark.
private val Midnight = Color(0xFF0F1226)
private val MidnightSurface = Color(0xFF161A33)
private val MidnightSurfaceVariant = Color(0xFF1F2444)
private val HotYellow = Color(0xFFFFD600)
private val OnHotYellow = Color(0xFF1A1300)
private val HotYellowContainer = Color(0xFF5C4400)
private val OnHotYellowContainer = Color(0xFFFFE082)
private val SunsetOrange = Color(0xFFFF9D45)
private val OnSunsetOrange = Color(0xFF3B1F00)
private val SunsetOrangeContainer = Color(0xFF5A3300)
private val OnSunsetOrangeContainer = Color(0xFFFFD8B0)
private val BrightTeal = Color(0xFF3DD8C4)
private val OnBrightTeal = Color(0xFF003B33)
private val BrightTealContainer = Color(0xFF005248)
private val OnBrightTealContainer = Color(0xFF9DEAE0)
private val InkDark = Color(0xFFFFE082)
private val InkDarkVariant = Color(0xFF5C4400)
private val OnMidnight = Color(0xFFFFF7D6)
private val OnMidnightVariant = Color(0xFFE5D58A)
private val DangerCoral = Color(0xFFFF6B6B)
private val OnDangerCoral = Color(0xFF410001)
private val DangerCoralContainer = Color(0xFF8B0000)
private val OnDangerCoralContainer = Color(0xFFFFD4D4)

internal val SpendAiLightColors: ColorScheme = lightColorScheme(
    background = BananaCream,
    onBackground = OnBananaLight,
    surface = BananaSurface,
    onSurface = OnBananaLight,
    surfaceVariant = BananaSurfaceVariant,
    onSurfaceVariant = OnBananaLightVariant,
    primary = Mango,
    onPrimary = OnMango,
    primaryContainer = MangoContainer,
    onPrimaryContainer = OnMangoContainer,
    secondary = Tangerine,
    onSecondary = OnTangerine,
    secondaryContainer = TangerineContainer,
    onSecondaryContainer = OnTangerineContainer,
    tertiary = TealPop,
    onTertiary = OnTealPop,
    tertiaryContainer = TealPopContainer,
    onTertiaryContainer = OnTealPopContainer,
    error = DangerRed,
    onError = OnDangerRed,
    errorContainer = DangerRedContainer,
    onErrorContainer = OnDangerRedContainer,
    outline = InkLight,
    outlineVariant = InkLightVariant,
    inverseSurface = Midnight,
    inverseOnSurface = OnMidnight,
    inversePrimary = HotYellow,
    scrim = Color(0x99000000),
)

internal val SpendAiDarkColors: ColorScheme = darkColorScheme(
    background = Midnight,
    onBackground = OnMidnight,
    surface = MidnightSurface,
    onSurface = OnMidnight,
    surfaceVariant = MidnightSurfaceVariant,
    onSurfaceVariant = OnMidnightVariant,
    primary = HotYellow,
    onPrimary = OnHotYellow,
    primaryContainer = HotYellowContainer,
    onPrimaryContainer = OnHotYellowContainer,
    secondary = SunsetOrange,
    onSecondary = OnSunsetOrange,
    secondaryContainer = SunsetOrangeContainer,
    onSecondaryContainer = OnSunsetOrangeContainer,
    tertiary = BrightTeal,
    onTertiary = OnBrightTeal,
    tertiaryContainer = BrightTealContainer,
    onTertiaryContainer = OnBrightTealContainer,
    error = DangerCoral,
    onError = OnDangerCoral,
    errorContainer = DangerCoralContainer,
    onErrorContainer = OnDangerCoralContainer,
    outline = InkDark,
    outlineVariant = InkDarkVariant,
    inverseSurface = BananaCream,
    inverseOnSurface = OnBananaLight,
    inversePrimary = Mango,
    scrim = Color(0xCC000000),
)

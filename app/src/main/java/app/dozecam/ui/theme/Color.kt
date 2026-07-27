package app.dozecam.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Brand fallback, used only where the wallpaper-derived scheme is unavailable
 * (previews, host-side tests). A night-sky indigo matching the launcher, with a
 * warm amber accent for the states that ask for attention.
 */
private val Indigo40 = Color(0xFF40597D)
private val Indigo90 = Color(0xFFD5E3FF)
private val Indigo10 = Color(0xFF001C39)
private val Indigo80 = Color(0xFFAAC7FF)
private val Indigo20 = Color(0xFF08305B)
private val Indigo30 = Color(0xFF274873)

private val Slate40 = Color(0xFF545F71)
private val Slate90 = Color(0xFFD8E3F8)
private val Slate10 = Color(0xFF111C2B)
private val Slate80 = Color(0xFFBCC7DC)
private val Slate20 = Color(0xFF263141)
private val Slate30 = Color(0xFF3C4758)

private val Amber40 = Color(0xFF7B5800)
private val Amber90 = Color(0xFFFFDEA6)
private val Amber10 = Color(0xFF271900)
private val Amber80 = Color(0xFFF5BD48)
private val Amber20 = Color(0xFF412D00)
private val Amber30 = Color(0xFF5D4200)

internal val LightColorScheme = lightColorScheme(
    primary = Indigo40,
    onPrimary = Color.White,
    primaryContainer = Indigo90,
    onPrimaryContainer = Indigo10,
    secondary = Slate40,
    onSecondary = Color.White,
    secondaryContainer = Slate90,
    onSecondaryContainer = Slate10,
    tertiary = Amber40,
    onTertiary = Color.White,
    tertiaryContainer = Amber90,
    onTertiaryContainer = Amber10,
)

internal val DarkColorScheme = darkColorScheme(
    primary = Indigo80,
    onPrimary = Indigo20,
    primaryContainer = Indigo30,
    onPrimaryContainer = Indigo90,
    secondary = Slate80,
    onSecondary = Slate20,
    secondaryContainer = Slate30,
    onSecondaryContainer = Slate90,
    tertiary = Amber80,
    onTertiary = Amber20,
    tertiaryContainer = Amber30,
    onTertiaryContainer = Amber90,
)

/**
 * Dim red palette for nighttime use next to a crib: minimal blue light, low
 * luminance, still readable. Every surface role is spelled out — the expressive
 * components lean on the surfaceContainer ramp, and an unset role would fall
 * back to a neutral grey and put blue light back on the screen.
 */
internal val NightRedColorScheme = darkColorScheme(
    primary = Color(0xFFB94A48),
    onPrimary = Color(0xFF1A0000),
    primaryContainer = Color(0xFF3A0A0A),
    onPrimaryContainer = Color(0xFFD98C8A),
    secondary = Color(0xFF8B3A38),
    onSecondary = Color(0xFF1A0000),
    secondaryContainer = Color(0xFF2E0C0C),
    onSecondaryContainer = Color(0xFFC0706E),
    tertiary = Color(0xFFA85250),
    onTertiary = Color(0xFF1A0000),
    tertiaryContainer = Color(0xFF330E0E),
    onTertiaryContainer = Color(0xFFCF8280),
    background = Color(0xFF0A0000),
    onBackground = Color(0xFFB05A58),
    surface = Color(0xFF0A0000),
    onSurface = Color(0xFFB05A58),
    surfaceVariant = Color(0xFF241010),
    onSurfaceVariant = Color(0xFF9A4A48),
    surfaceDim = Color(0xFF080000),
    surfaceBright = Color(0xFF2A1212),
    surfaceContainerLowest = Color(0xFF050000),
    surfaceContainerLow = Color(0xFF100303),
    surfaceContainer = Color(0xFF160505),
    surfaceContainerHigh = Color(0xFF1E0808),
    surfaceContainerHighest = Color(0xFF260B0B),
    inverseSurface = Color(0xFFB05A58),
    inverseOnSurface = Color(0xFF1A0000),
    inversePrimary = Color(0xFF7A2422),
    outline = Color(0xFF5A2A28),
    outlineVariant = Color(0xFF3A1817),
    error = Color(0xFFD96360),
    onError = Color(0xFF1A0000),
    errorContainer = Color(0xFF450E0E),
    onErrorContainer = Color(0xFFE89896),
    scrim = Color.Black,
)

/** Live-state accents that must stay legible on top of arbitrary video. */
internal val StatusLive = Color(0xFF66BB6A)
internal val StatusWaiting = Color(0xFFFFB74D)
internal val StatusOffline = Color(0xFFEF5350)

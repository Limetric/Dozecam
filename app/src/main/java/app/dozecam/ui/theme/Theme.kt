package app.dozecam.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

/**
 * Whether the dim red night palette is in force. Content drawn over video sits
 * outside the colour scheme's control and has to make its own call about how
 * much light it may emit.
 */
val LocalNightTheme = staticCompositionLocalOf { false }

/**
 * Material 3 Expressive theming for Dozecam.
 *
 * Colour follows the user's own theme by default: minSdk 31 means the
 * wallpaper-derived scheme is always available, so it is the primary source
 * rather than a fallback, and light/dark tracks the system setting. Only two
 * things override it — the night palette the user asks for explicitly, and
 * [dynamicColor] = false for previews and host-side tests.
 */
@Composable
fun DozecamTheme(
    nightTheme: Boolean = false,
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme: ColorScheme = when {
        nightTheme -> NightRedColorScheme
        dynamicColor && darkTheme -> dynamicDarkColorScheme(context)
        dynamicColor -> dynamicLightColorScheme(context)
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }
    CompositionLocalProvider(LocalNightTheme provides nightTheme) {
        MaterialExpressiveTheme(
            colorScheme = colorScheme,
            motionScheme = MotionScheme.expressive(),
            content = content,
        )
    }
}

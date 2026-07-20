package app.dozecam.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Dim red palette for nighttime use next to a crib: minimal blue light,
 * low luminance, still readable.
 */
internal fun nightRedColorScheme() = darkColorScheme(
    primary = Color(0xFFB94A48),
    onPrimary = Color(0xFF1A0000),
    primaryContainer = Color(0xFF3A0A0A),
    onPrimaryContainer = Color(0xFFD98C8A),
    secondary = Color(0xFF8B3A38),
    onSecondary = Color(0xFF1A0000),
    background = Color(0xFF0A0000),
    onBackground = Color(0xFFB05A58),
    surface = Color(0xFF140404),
    onSurface = Color(0xFFB05A58),
    surfaceVariant = Color(0xFF241010),
    onSurfaceVariant = Color(0xFF9A4A48),
    outline = Color(0xFF5A2A28),
)

@Composable
fun DozecamTheme(
    nightTheme: Boolean = false,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        nightTheme -> nightRedColorScheme()
        darkTheme -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}

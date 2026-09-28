package app.dozecam.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DozecamThemeTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `default theme follows the user's own colours`() {
        var applied: Color? = null
        var expected: Color? = null
        composeRule.setContent {
            expected = dynamicLightColorScheme(LocalContext.current).primary
            DozecamTheme(darkTheme = false) {
                applied = MaterialTheme.colorScheme.primary
            }
        }

        assertEquals(expected, applied)
    }

    @Test
    fun `dark mode follows the user's own colours`() {
        var applied: Color? = null
        var expected: Color? = null
        composeRule.setContent {
            expected = dynamicDarkColorScheme(LocalContext.current).primary
            DozecamTheme(darkTheme = true) {
                applied = MaterialTheme.colorScheme.primary
            }
        }

        assertEquals(expected, applied)
    }

    @Test
    fun `night theme overrides the user's colours`() {
        var primary: Color? = null
        var surface: Color? = null
        composeRule.setContent {
            DozecamTheme(nightTheme = true) {
                primary = MaterialTheme.colorScheme.primary
                // Overlays over video take their container from this role, so
                // the night palette dims them without a branch of their own.
                surface = MaterialTheme.colorScheme.surfaceContainer
            }
        }

        assertEquals(NightRedColorScheme.primary, primary)
        assertEquals(NightRedColorScheme.surfaceContainer, surface)
    }

    @Test
    fun `brand fallback applies only when dynamic colour is switched off`() {
        var applied: Color? = null
        composeRule.setContent {
            DozecamTheme(darkTheme = false, dynamicColor = false) {
                applied = MaterialTheme.colorScheme.primary
            }
        }

        assertEquals(LightColorScheme.primary, applied)
    }
}

package app.dozecam.ui.settings

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.dozecam.data.AppSettings
import app.dozecam.data.OrientationLock
import app.dozecam.ui.theme.DozecamTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SettingsScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `toggling night theme reports the change`() {
        var changed: AppSettings? = null
        composeRule.setContent {
            DozecamTheme {
                SettingsScreen(
                    settings = AppSettings(nightTheme = false),
                    onSettingsChange = { changed = it(AppSettings(nightTheme = false)) },
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithTag("night-theme-switch").performClick()

        assertEquals(true, changed?.nightTheme)
    }

    @Test
    fun `choosing an orientation lock reports the change`() {
        var changed: AppSettings? = null
        composeRule.setContent {
            DozecamTheme {
                SettingsScreen(
                    settings = AppSettings(),
                    onSettingsChange = { changed = it(AppSettings()) },
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithTag("orientation-LANDSCAPE").performScrollTo().performClick()

        assertEquals(OrientationLock.LANDSCAPE, changed?.orientationLock)
    }

    @Test
    fun `disabling the chime reports the change`() {
        var changed: AppSettings? = null
        composeRule.setContent {
            DozecamTheme {
                SettingsScreen(
                    settings = AppSettings(alertChime = true),
                    onSettingsChange = { changed = it(AppSettings(alertChime = true)) },
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithTag("chime-switch").performClick()

        assertEquals(false, changed?.alertChime)
    }
}

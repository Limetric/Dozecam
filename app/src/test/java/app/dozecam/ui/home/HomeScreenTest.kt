package app.dozecam.ui.home

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import app.dozecam.ui.theme.DozecamTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HomeScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `watch button reflects url validity`() {
        composeRule.setContent {
            DozecamTheme {
                HomeScreen(url = "", canWatch = false, onUrlChange = {}, onWatch = {})
            }
        }

        composeRule.onNodeWithText("Watch").assertIsNotEnabled()
    }

    @Test
    fun `watch button enabled when url is valid`() {
        composeRule.setContent {
            DozecamTheme {
                HomeScreen(
                    url = "rtsp://192.168.1.1:7447/token",
                    canWatch = true,
                    onUrlChange = {},
                    onWatch = {},
                )
            }
        }

        composeRule.onNodeWithText("Watch").assertIsEnabled()
    }

    @Test
    fun `typing in the field forwards input`() {
        var captured = ""
        composeRule.setContent {
            DozecamTheme {
                HomeScreen(
                    url = "",
                    canWatch = false,
                    onUrlChange = { captured = it },
                    onWatch = {},
                )
            }
        }

        composeRule.onNodeWithText("Stream URL").performTextInput("rtsp://x")

        assertEquals("rtsp://x", captured)
    }
}

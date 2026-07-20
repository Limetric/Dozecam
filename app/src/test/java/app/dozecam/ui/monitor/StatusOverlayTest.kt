package app.dozecam.ui.monitor

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.dozecam.player.ConnectionState
import app.dozecam.ui.theme.DozecamTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class StatusOverlayTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `live state shows LIVE without a last-frame suffix`() {
        composeRule.setContent {
            DozecamTheme {
                StatusOverlay(state = ConnectionState.Live, lastFrameAtMs = 123L)
            }
        }

        composeRule.onNodeWithText("LIVE").assertExists()
    }

    @Test
    fun `reconnecting state shows the attempt and last frame time`() {
        composeRule.setContent {
            DozecamTheme {
                StatusOverlay(
                    state = ConnectionState.Reconnecting(3),
                    lastFrameAtMs = 0L,
                )
            }
        }

        composeRule.onNodeWithText("RECONNECTING (attempt 3)", substring = true).assertExists()
        composeRule.onNodeWithText("last frame", substring = true).assertExists()
    }

    @Test
    fun `offline state shows OFFLINE`() {
        composeRule.setContent {
            DozecamTheme {
                StatusOverlay(state = ConnectionState.Offline, lastFrameAtMs = null)
            }
        }

        composeRule.onNodeWithText("OFFLINE").assertExists()
    }
}

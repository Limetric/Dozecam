package app.dozecam.ui.settings

import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import app.dozecam.ui.theme.DozecamTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class JumpTargetTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** The flash is a moment, not a state: the target must let go on its own. */
    @Test
    fun `a jumped-to row marks itself and then releases`() {
        var released = false
        composeRule.setContent {
            DozecamTheme {
                JumpTarget(id = "x", jumpTarget = "x", onJumpDone = { released = true }) {
                    Text("row")
                }
            }
        }

        composeRule.mainClock.advanceTimeBy(2_500)
        composeRule.waitForIdle()

        assertTrue(released)
    }

    @Test
    fun `rows that were not jumped to stay untouched`() {
        var released = false
        composeRule.setContent {
            DozecamTheme {
                JumpTarget(id = "y", jumpTarget = "x", onJumpDone = { released = true }) {
                    Text("row")
                }
            }
        }

        composeRule.mainClock.advanceTimeBy(2_500)
        composeRule.waitForIdle()

        assertFalse(released)
        composeRule.onNodeWithTag("jump-active-y").assertDoesNotExist()
    }
}

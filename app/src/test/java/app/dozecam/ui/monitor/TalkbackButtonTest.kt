package app.dozecam.ui.monitor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import app.dozecam.audio.talkback.TalkbackAvailability
import app.dozecam.ui.theme.DozecamTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TalkbackButtonTest {

    @get:Rule
    val compose = createComposeRule()

    private val events = mutableListOf<String>()

    private fun show(
        availability: TalkbackAvailability,
        talking: Boolean = false,
    ) {
        compose.setContent {
            DozecamTheme {
                TalkbackButton(
                    availability = availability,
                    talking = talking,
                    onPress = { events += "press" },
                    onRelease = { events += "release" },
                    onExplain = { events += "explain" },
                )
            }
        }
    }

    @Test
    fun `holding a ready button talks, and letting go stops`() {
        show(TalkbackAvailability.Ready)

        compose.onNodeWithTag("talkback-button").performTouchInput { down(center) }
        compose.waitForIdle()
        assertEquals(listOf("press"), events)

        compose.onNodeWithTag("talkback-button").performTouchInput { up() }
        compose.waitForIdle()
        assertEquals(listOf("press", "release"), events)
    }

    /**
     * A finger that slides off the control is still a finger that has stopped
     * talking, and the tail has to go out either way.
     */
    @Test
    fun `a cancelled press still releases`() {
        show(TalkbackAvailability.Ready)

        compose.onNodeWithTag("talkback-button").performTouchInput {
            down(center)
            moveTo(center.copy(x = center.x + 2_000f))
            up()
        }
        compose.waitForIdle()

        assertEquals(listOf("press", "release"), events)
    }

    /** Nothing is claimed before the camera has answered. */
    @Test
    fun `a camera still being resolved shows no control at all`() {
        show(TalkbackAvailability.Resolving)

        compose.onNodeWithTag("talkback-button").assertDoesNotExist()
        compose.onNodeWithTag("talkback-notice").assertDoesNotExist()
    }

    @Test
    fun `an unreachable camera explains itself rather than doing nothing`() {
        show(TalkbackAvailability.Unreachable)

        compose.onNodeWithTag("talkback-button").assertIsDisplayed().performClick()
        compose.waitForIdle()

        assertEquals(listOf("explain"), events)
    }

    @Test
    fun `a camera that cannot talk never starts talking`() {
        show(TalkbackAvailability.Unsupported(TalkbackAvailability.Reason.NO_SPEAKER))

        compose.onNodeWithTag("talkback-button").performTouchInput {
            down(center)
            up()
        }
        compose.waitForIdle()

        assertEquals(listOf("explain"), events)
    }

    @Test
    fun `talking says so on screen`() {
        show(TalkbackAvailability.Ready, talking = true)

        compose.onNodeWithTag("talkback-talking").assertIsDisplayed()
    }

    /**
     * Every reason a control can refuse has a sentence behind it. A state added
     * later without one would fail here rather than render an empty bar.
     */
    @Test
    fun `each reason has something to say, and ready has nothing`() {
        var availability: TalkbackAvailability by mutableStateOf(TalkbackAvailability.NeedsPermission)
        compose.setContent { DozecamTheme { TalkbackNotice(availability = availability) } }

        val reasons = listOf(
            TalkbackAvailability.NeedsPermission,
            TalkbackAvailability.NeedsUnlock,
            TalkbackAvailability.Unreachable,
        ) + TalkbackAvailability.Reason.entries.map { TalkbackAvailability.Unsupported(it) }

        reasons.forEach { reason ->
            availability = reason
            compose.waitForIdle()
            compose.onNodeWithTag("talkback-notice").assertIsDisplayed()
        }

        availability = TalkbackAvailability.Ready
        compose.waitForIdle()
        compose.onNodeWithTag("talkback-notice").assertDoesNotExist()
    }
}

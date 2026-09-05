package app.dozecam.ui.monitor

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.height
import androidx.compose.ui.Modifier
import app.dozecam.player.ConnectionState
import app.dozecam.ui.theme.DozecamTheme
import app.dozecam.ui.theme.NightRedColorScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
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
        composeRule.onNodeWithTag("status-icon-live").assertExists()
    }

    /**
     * The age of the picture, not the clock time it arrived at: "12 seconds
     * ago" answers how long the room has been unseen without arithmetic
     * against the phone's clock.
     */
    @Test
    fun `reconnecting state shows the attempt and how long ago the last frame was`() {
        composeRule.setContent {
            DozecamTheme {
                StatusOverlay(
                    state = ConnectionState.Reconnecting(3),
                    lastFrameAtMs = 0L,
                    clock = { 12_400L },
                )
            }
        }

        composeRule.onNodeWithText("RECONNECTING (attempt 3) · last frame 12 seconds ago")
            .assertExists()
        composeRule.onNodeWithTag("status-icon-connecting").assertExists()
    }

    /**
     * The age keeps counting while the stream is gone: a pill that said
     * "12 seconds ago" for a minute would be the frozen frame all over again.
     */
    @Test
    fun `the age of the last frame counts on while the stream is not live`() {
        var now = 5_000L
        composeRule.setContent {
            DozecamTheme {
                StatusOverlay(
                    state = ConnectionState.Offline,
                    lastFrameAtMs = 0L,
                    clock = { now },
                )
            }
        }
        composeRule.onNodeWithText("5 seconds ago", substring = true).assertExists()

        now = 61_000L
        composeRule.mainClock.advanceTimeBy(1_000L)

        composeRule.onNodeWithText("1 minute ago", substring = true).assertExists()
    }

    @Test
    fun `an age is said in the largest unit that fits, rounded down`() {
        assertEquals(FrameAge(0, AgeUnit.SECONDS), frameAge(0L))
        assertEquals(FrameAge(0, AgeUnit.SECONDS), frameAge(999L))
        assertEquals(FrameAge(12, AgeUnit.SECONDS), frameAge(12_400L))
        assertEquals(FrameAge(59, AgeUnit.SECONDS), frameAge(59_999L))
        assertEquals(FrameAge(1, AgeUnit.MINUTES), frameAge(60_000L))
        assertEquals(FrameAge(59, AgeUnit.MINUTES), frameAge(3_599_000L))
        assertEquals(FrameAge(1, AgeUnit.HOURS), frameAge(3_600_000L))
        assertEquals(FrameAge(23, AgeUnit.HOURS), frameAge(86_399_000L))
        assertEquals(FrameAge(1, AgeUnit.DAYS), frameAge(86_400_000L))
        assertEquals(FrameAge(3, AgeUnit.DAYS), frameAge(3 * 86_400_000L + 5_000L))
    }

    @Test
    fun `a clock set back reads as no age rather than a frame from the future`() {
        assertEquals(FrameAge(0, AgeUnit.SECONDS), frameAge(-30_000L))
    }

    @Test
    fun `offline state shows OFFLINE`() {
        composeRule.setContent {
            DozecamTheme {
                StatusOverlay(state = ConnectionState.Offline, lastFrameAtMs = null)
            }
        }

        composeRule.onNodeWithText("OFFLINE").assertExists()
        composeRule.onNodeWithTag("status-icon-offline").assertExists()
    }

    /**
     * The pill has a floor, not a fixed height: at a large system font size
     * the word grows, and a pill that could not grow with it would cut the
     * connection state in half for exactly the reader who most needs it.
     */
    @Test
    fun `the pill grows with the system font size rather than clipping the word`() {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = 2f),
            ) {
                DozecamTheme {
                    StatusOverlay(
                        state = ConnectionState.Reconnecting(3),
                        lastFrameAtMs = 0L,
                        modifier = Modifier.testTag("pill"),
                    )
                }
            }
        }

        // A clipped word still reports the bounds it was squeezed into, so
        // the pill is measured instead: at twice the font size a line of
        // label text is taller than the pill's floor, and the pill must have
        // made room for it.
        val pill = composeRule.onNodeWithTag("pill").getBoundsInRoot()
        assertTrue(
            "pill (${pill.height}) did not grow past its floor (${OverlayChrome.TileHeight})",
            pill.height > OverlayChrome.TileHeight,
        )
    }

    /**
     * The wallpaper decides the hues, so two states can land on colours that
     * are hard to tell apart. Nothing else on the pill may collapse with them:
     * a silhouette per state is what survives a scheme that makes primary and
     * error cousins, and a reader who cannot separate the two at all.
     */
    @Test
    fun `each state is drawn with its own icon`() {
        val icons = listOf(
            ConnectionState.Live,
            ConnectionState.Connecting,
            ConnectionState.Reconnecting(1),
            ConnectionState.Offline,
        ).map { it.appearance(NightRedColorScheme).icon }

        // Connecting and reconnecting are the same fact to anyone looking, so
        // they share a silhouette; the other states may not share one.
        assertEquals(icons[1], icons[2])
        assertEquals(4, icons.size)
        assertEquals(3, icons.toSet().size)
    }

    /**
     * Colour comes from the scheme rather than from constants of our own, so
     * the night palette dims the pill without the overlay knowing it exists.
     */
    @Test
    fun `state colours are colour scheme roles`() {
        val colors = NightRedColorScheme

        assertEquals(colors.primary, ConnectionState.Live.appearance(colors).color)
        assertEquals(colors.tertiary, ConnectionState.Connecting.appearance(colors).color)
        assertEquals(colors.tertiary, ConnectionState.Reconnecting(2).appearance(colors).color)
        assertEquals(colors.error, ConnectionState.Offline.appearance(colors).color)
        assertNotEquals(
            ConnectionState.Live.appearance(colors).color,
            ConnectionState.Offline.appearance(colors).color,
        )
    }
}

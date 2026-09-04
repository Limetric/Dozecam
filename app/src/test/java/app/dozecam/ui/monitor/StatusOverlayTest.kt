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
        composeRule.onNodeWithTag("status-icon-connecting").assertExists()
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

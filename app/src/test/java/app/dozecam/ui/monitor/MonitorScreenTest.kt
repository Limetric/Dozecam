package app.dozecam.ui.monitor

import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import app.dozecam.data.Camera
import app.dozecam.player.PlayerEvent
import app.dozecam.player.StreamSource
import app.dozecam.player.VideoPlayerController
import app.dozecam.ui.theme.DozecamTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class MonitorScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val nursery = Camera("a", "Nursery", "rtsp://cam:7447/a")
    private val playroom = Camera("b", "Play room", "rtsp://cam:7447/b")
    private val hall = Camera("c", "Hall", "rtsp://cam:7447/c")

    /** Records what it was asked to do; never touches a real decoder. */
    private class FakeController : VideoPlayerController {
        override var listener: ((PlayerEvent) -> Unit)? = null
        var played: StreamSource? = null
        var released = false
        var muted: Boolean? = null

        /** Whether the tile was already silenced by the time it started playing. */
        var mutedWhenPlayed: Boolean? = null

        override fun attach(container: ViewGroup) = Unit
        override fun detach() = Unit

        override fun play(source: StreamSource) {
            played = source
            mutedWhenPlayed = muted
        }

        override fun setMuted(muted: Boolean) {
            this.muted = muted
        }

        override fun stop() = Unit
        override fun release() {
            released = true
        }
    }

    private val controllers = mutableListOf<FakeController>()

    /** The player that ended up playing [camera], whatever order tiles were built in. */
    private fun controllerFor(camera: Camera): FakeController =
        controllers.last { (it.played as? StreamSource.Rtsp)?.url == camera.url }

    private fun sourcesFor(vararg cameras: Camera) =
        cameras.associate { it.id to StreamSource.Rtsp(it.url) as StreamSource }

    @Composable
    private fun Screen(
        cameras: List<Camera>,
        unmonitorable: List<Camera> = emptyList(),
        hasDisabledOnly: Boolean = false,
        onOpenSettings: () -> Unit = {},
        onOpenOnboarding: () -> Unit = {},
        soundEnabled: Boolean = false,
        onSoundEnabledChange: (Boolean) -> Unit = {},
        soundGranted: Boolean = true,
        soundRotationIntervalMs: Long = ROTATION_MS,
        alertCameraId: String? = null,
        onAlertConsumed: () -> Unit = {},
        onFullscreenChange: (Boolean) -> Unit = {},
        onAlertDismissed: () -> Unit = {},
    ) {
        DozecamTheme {
            MonitorScreen(
                cameras = cameras,
                sources = sourcesFor(*cameras.toTypedArray()),
                controllerFactory = { FakeController().also { controllers += it } },
                networkOnline = true,
                unmonitorable = unmonitorable,
                hasDisabledOnly = hasDisabledOnly,
                onOpenSettings = onOpenSettings,
                onOpenOnboarding = onOpenOnboarding,
                soundEnabled = soundEnabled,
                onSoundEnabledChange = onSoundEnabledChange,
                soundGranted = soundGranted,
                soundRotationIntervalMs = soundRotationIntervalMs,
                alertCameraId = alertCameraId,
                onAlertConsumed = onAlertConsumed,
                onFullscreenChange = onFullscreenChange,
                onAlertDismissed = onAlertDismissed,
            )
        }
    }

    @Test
    fun `a phone stacks every camera in one column`() {
        composeRule.setContent { Screen(cameras = listOf(nursery, playroom, hall)) }

        // Every room reachable by scrolling, rather than one at a time behind a
        // swipe that hides the others.
        composeRule.onNodeWithTag("camera-list-1").assertExists()
    }

    @Test
    @Config(qualifiers = TABLET_SCREEN)
    fun `a tablet puts them side by side`() {
        composeRule.setContent { Screen(cameras = listOf(nursery, playroom, hall)) }

        composeRule.onNodeWithTag("camera-list-3").assertExists()
    }

    @Test
    @Config(qualifiers = MEDIUM_SCREEN)
    fun `a medium window gets two columns`() {
        composeRule.setContent { Screen(cameras = listOf(nursery, playroom, hall)) }

        composeRule.onNodeWithTag("camera-list-2").assertExists()
    }

    @Test
    @Config(qualifiers = TABLET_SCREEN)
    fun `columns never outnumber the cameras`() {
        composeRule.setContent { Screen(cameras = listOf(nursery)) }

        composeRule.onNodeWithTag("camera-list-1").assertExists()
    }

    @Test
    fun `no cameras at all points at the console`() {
        var opened = false
        composeRule.setContent {
            Screen(cameras = emptyList(), onOpenOnboarding = { opened = true })
        }

        composeRule.onNodeWithTag("empty-open-onboarding").performClick()

        assertTrue(opened)
    }

    @Test
    fun `cameras that exist but are all switched off point at settings`() {
        var opened = false
        composeRule.setContent {
            Screen(
                cameras = emptyList(),
                hasDisabledOnly = true,
                onOpenSettings = { opened = true },
            )
        }

        composeRule.onNodeWithTag("empty-open-settings").performClick()

        assertTrue(opened)
    }

    @Test
    fun `a camera that cannot be listened to says so`() {
        composeRule.setContent {
            Screen(cameras = listOf(nursery), unmonitorable = listOf(nursery))
        }

        composeRule.onNodeWithTag("unmonitorable-notice").assertIsDisplayed()
    }

    @Test
    fun `full coverage shows no warning`() {
        composeRule.setContent { Screen(cameras = listOf(nursery)) }

        composeRule.onNodeWithTag("unmonitorable-notice").assertDoesNotExist()
    }

    @Test
    fun `the viewer offers settings and sound and nothing else`() {
        composeRule.setContent { Screen(cameras = listOf(nursery)) }

        composeRule.onNodeWithTag("open-settings").assertExists()
        composeRule.onNodeWithTag("toggle-sound").assertExists()
        // Arming lives in settings now; the viewer is for watching.
        composeRule.onNodeWithTag("monitoring-switch").assertDoesNotExist()
        composeRule.onNodeWithTag("audio-level-meter").assertDoesNotExist()
    }

    @Test
    fun `a wake alert opens that camera alone`() {
        var consumed = false
        var wentFullscreen: Boolean? = null
        composeRule.setContent {
            Screen(
                cameras = listOf(nursery, playroom),
                alertCameraId = "b",
                onAlertConsumed = { consumed = true },
                onFullscreenChange = { wentFullscreen = it },
            )
        }

        composeRule.onNodeWithTag("fullscreen-tile").assertExists()
        composeRule.onNodeWithTag("camera-list-1").assertDoesNotExist()
        assertTrue(consumed)
        assertEquals(true, wentFullscreen)
    }

    @Test
    fun `an alert for a camera that is no longer there leaves the viewer alone`() {
        var dismissed = false
        composeRule.setContent {
            Screen(
                cameras = listOf(nursery),
                alertCameraId = "gone",
                onAlertDismissed = { dismissed = true },
            )
        }

        composeRule.onNodeWithTag("fullscreen-tile").assertDoesNotExist()
        // Nothing to show means the alert is over — otherwise the grid would be
        // left sitting on the lock screen.
        assertTrue(dismissed)
    }

    @Test
    fun `backing out of an alerted camera ends the alert`() {
        var dismissed = false
        composeRule.setContent {
            Screen(
                cameras = listOf(nursery, playroom),
                alertCameraId = "b",
                onAlertDismissed = { dismissed = true },
            )
        }
        composeRule.onNodeWithTag("fullscreen-tile").assertExists()
        assertFalse(dismissed)

        composeRule.onNodeWithTag("fullscreen-tile").performClick()

        // The alert bought a look at one room, not at the whole house.
        composeRule.onNodeWithTag("camera-list-1").assertExists()
        assertTrue(dismissed)
    }

    @Test
    fun `an alert arriving over an open camera still ends with the alert`() {
        var dismissed = false
        var alerted by mutableStateOf<String?>(null)
        composeRule.setContent {
            Screen(
                cameras = listOf(nursery, playroom),
                alertCameraId = alerted,
                onAlertDismissed = { dismissed = true },
            )
        }
        // Already looking at one camera when the alert names another.
        composeRule.onNodeWithTag("camera-tile-Nursery").performClick()
        composeRule.onNodeWithTag("fullscreen-tile").assertExists()
        composeRule.runOnIdle { alerted = "b" }
        composeRule.waitForIdle()
        assertFalse(dismissed)

        composeRule.onNodeWithTag("fullscreen-tile").performClick()
        composeRule.onNodeWithTag("camera-list-1").assertExists()

        // Without this the grid would sit on the lock screen after Back.
        assertTrue(dismissed)
    }

    @Test
    fun `an alert for the camera already on screen still ends with the alert`() {
        var dismissed = false
        var alerted by mutableStateOf<String?>(null)
        composeRule.setContent {
            Screen(
                cameras = listOf(nursery, playroom),
                alertCameraId = alerted,
                onAlertDismissed = { dismissed = true },
            )
        }
        // Locked while watching the very room that then gets loud.
        composeRule.onNodeWithTag("camera-tile-Nursery").performClick()
        composeRule.onNodeWithTag("fullscreen-tile").assertExists()
        composeRule.runOnIdle { alerted = "a" }
        composeRule.waitForIdle()
        assertFalse(dismissed)

        composeRule.onNodeWithTag("fullscreen-tile").performClick()
        composeRule.onNodeWithTag("camera-list-1").assertExists()

        assertTrue(dismissed)
    }

    @Test
    fun `tapping a tile promotes it to fullscreen and tapping again returns`() {
        composeRule.setContent { Screen(cameras = listOf(nursery, playroom)) }

        composeRule.onNodeWithTag("camera-tile-Nursery").performClick()
        composeRule.onNodeWithTag("fullscreen-tile").assertExists()

        composeRule.onNodeWithTag("fullscreen-tile").performClick()
        composeRule.onNodeWithTag("camera-list-1").assertExists()
    }

    @Test
    @Config(qualifiers = TABLET_SCREEN)
    fun `each visible tile gets its own player`() {
        controllers.clear()
        composeRule.setContent { Screen(cameras = listOf(nursery, playroom)) }
        composeRule.waitForIdle()

        // Two cameras on screen must never share one decoder.
        assertEquals(2, controllers.size)
        assertEquals(
            setOf("rtsp://cam:7447/a", "rtsp://cam:7447/b"),
            controllers.mapNotNull { (it.played as? StreamSource.Rtsp)?.url }.toSet(),
        )
    }

    @Test
    @Config(qualifiers = TABLET_SCREEN)
    fun `listed tiles are silent`() {
        controllers.clear()
        composeRule.setContent { Screen(cameras = listOf(nursery, playroom)) }
        composeRule.waitForIdle()

        // Two rooms talking over each other is worse than hearing neither, and
        // muting after play() would still blurt out the first burst.
        assertTrue(controllers.isNotEmpty())
        assertTrue(controllers.all { it.muted == true })
        assertTrue(controllers.all { it.mutedWhenPlayed == true })
    }

    @Test
    fun `a camera opened on its own is audible once sound is on`() {
        controllers.clear()
        composeRule.setContent {
            Screen(
                cameras = listOf(nursery, playroom),
                soundEnabled = true,
                alertCameraId = "b",
            )
        }
        composeRule.waitForIdle()

        // Listening to the room is the whole point of opening one camera.
        assertEquals(false, controllers.last().muted)
    }

    @Test
    fun `a camera opened by an alert stays silent until sound is asked for`() {
        controllers.clear()
        composeRule.setContent {
            Screen(cameras = listOf(nursery, playroom), alertCameraId = "b")
        }
        composeRule.waitForIdle()

        // The screen can come on by itself, over a lock screen, in the middle
        // of the night. It must not start talking by itself as well.
        assertEquals(true, controllers.last().muted)
        assertEquals(true, controllers.last().mutedWhenPlayed)
    }

    @Test
    fun `the sound button hands the choice back rather than deciding`() {
        var asked: Boolean? = null
        composeRule.setContent {
            Screen(cameras = listOf(nursery), onSoundEnabledChange = { asked = it })
        }

        composeRule.onNodeWithTag("toggle-sound").performClick()

        assertEquals(true, asked)
    }

    @Test
    fun `the sound button is reachable on a single camera too`() {
        var asked: Boolean? = null
        composeRule.setContent {
            Screen(
                cameras = listOf(nursery, playroom),
                soundEnabled = true,
                onSoundEnabledChange = { asked = it },
                alertCameraId = "b",
            )
        }
        composeRule.onNodeWithTag("fullscreen-tile").assertExists()

        composeRule.onNodeWithTag("toggle-sound").performClick()

        // Otherwise the camera an alert opened could only be silenced by
        // leaving it, which is the one thing the user does not want to do.
        assertEquals(false, asked)
    }

    @Test
    @Config(qualifiers = TABLET_SCREEN)
    fun `the grid gives the sound to one camera at a time`() {
        controllers.clear()
        composeRule.setContent {
            Screen(cameras = listOf(nursery, playroom, hall), soundEnabled = true)
        }
        composeRule.waitUntil { controllers.size == 3 }

        composeRule.waitUntil { controllerFor(nursery).muted == false }
        assertEquals(true, controllerFor(playroom).muted)
        assertEquals(true, controllerFor(hall).muted)
        // Every tile still starts silent; the turn is granted afterwards.
        assertTrue(controllers.all { it.mutedWhenPlayed == true })
    }

    @Test
    @Config(qualifiers = TABLET_SCREEN)
    fun `the sound moves on to the next camera when the turn is up`() {
        controllers.clear()
        composeRule.setContent {
            Screen(cameras = listOf(nursery, playroom, hall), soundEnabled = true)
        }
        composeRule.waitUntil { controllers.size == 3 }
        composeRule.waitUntil { controllerFor(nursery).muted == false }

        composeRule.mainClock.advanceTimeBy(ROTATION_MS + 1)

        composeRule.waitUntil { controllerFor(playroom).muted == false }
        assertEquals(true, controllerFor(nursery).muted)
        assertEquals(true, controllerFor(hall).muted)
    }

    @Test
    @Config(qualifiers = TABLET_SCREEN)
    fun `the audible camera says so on screen`() {
        composeRule.setContent {
            Screen(cameras = listOf(nursery, playroom), soundEnabled = true)
        }

        // Sound with no visible source is indistinguishable from the wrong
        // camera being open.
        // Unmerged: the tile is clickable, which merges its children's
        // semantics into the tile's own node.
        composeRule.onNodeWithTag("audible-badge-Nursery", useUnmergedTree = true)
            .assertIsDisplayed()
        composeRule.onNodeWithTag("audible-badge-Play room", useUnmergedTree = true)
            .assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = TABLET_SCREEN)
    fun `sound switched off leaves every tile silent and unmarked`() {
        controllers.clear()
        composeRule.setContent {
            Screen(cameras = listOf(nursery, playroom), soundEnabled = false)
        }
        composeRule.waitUntil { controllers.size == 2 }

        assertTrue(controllers.all { it.muted == true })
        composeRule.onNodeWithTag("audible-badge-Nursery", useUnmergedTree = true)
            .assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = TABLET_SCREEN)
    fun `an interruption silences the cameras without touching the switch`() {
        controllers.clear()
        composeRule.setContent {
            Screen(
                cameras = listOf(nursery, playroom),
                soundEnabled = true,
                soundGranted = false,
            )
        }
        composeRule.waitUntil { controllers.size == 2 }

        assertTrue(controllers.all { it.muted == true })
        // The button still shows the user's own choice, so a tap during a call
        // silences the viewer rather than setting it to what it already was.
        composeRule.onNodeWithTag("toggle-sound")
            .assertContentDescriptionEquals("Turn sound off")
    }

    @Test
    @Config(qualifiers = TABLET_SCREEN)
    fun `a camera on its own keeps the sound rather than losing its turn`() {
        controllers.clear()
        composeRule.setContent {
            Screen(
                cameras = listOf(nursery, playroom),
                soundEnabled = true,
                alertCameraId = "a",
            )
        }
        composeRule.onNodeWithTag("fullscreen-tile").assertExists()
        composeRule.waitUntil { controllerFor(nursery).muted == false }

        composeRule.mainClock.advanceTimeBy(ROTATION_MS * 4)

        // Rotation is a grid problem. A camera opened on its own — often by an
        // alert — must not fall silent because a timer said its turn was over.
        assertEquals(false, controllerFor(nursery).muted)
        composeRule.onNodeWithTag("fullscreen-tile").assertExists()
    }

    @Test
    @Config(qualifiers = TABLET_SCREEN)
    fun `switching sound off mid-round silences the camera holding it`() {
        controllers.clear()
        var sound by mutableStateOf(true)
        composeRule.setContent {
            Screen(cameras = listOf(nursery, playroom), soundEnabled = sound)
        }
        composeRule.waitUntil { controllers.size == 2 }
        composeRule.waitUntil { controllerFor(nursery).muted == false }

        composeRule.runOnIdle { sound = false }

        composeRule.waitUntil { controllerFor(nursery).muted == true }
        composeRule.onNodeWithTag("audible-badge-Nursery", useUnmergedTree = true)
            .assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = TABLET_SCREEN)
    fun `a camera that goes away hands its turn on instead of taking it with it`() {
        controllers.clear()
        var shown by mutableStateOf(listOf(nursery, playroom))
        composeRule.setContent { Screen(cameras = shown, soundEnabled = true) }
        composeRule.waitUntil { controllers.size == 2 }
        composeRule.waitUntil { controllerFor(nursery).muted == false }

        // Switched off in settings, or deleted, while it held the sound.
        composeRule.runOnIdle { shown = listOf(playroom) }

        composeRule.waitUntil { controllerFor(playroom).muted == false }
        composeRule.onNodeWithTag("audible-badge-Play room", useUnmergedTree = true)
            .assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = SHORT_SCREEN)
    fun `a camera scrolled off the grid is skipped rather than given a silent turn`() {
        controllers.clear()
        composeRule.setContent {
            Screen(cameras = listOf(nursery, playroom, hall), soundEnabled = true)
        }
        composeRule.waitUntil { controllerFor(nursery).muted == false }
        // Only two tiles fit; the third has no player at all.
        composeRule.onNodeWithTag("camera-tile-Hall").assertDoesNotExist()

        composeRule.mainClock.advanceTimeBy(ROTATION_MS + 1)
        composeRule.waitUntil { controllerFor(playroom).muted == false }
        composeRule.mainClock.advanceTimeBy(ROTATION_MS + 1)

        // Back to the top rather than ten seconds of silence over a badge that
        // is itself off screen.
        composeRule.waitUntil { controllerFor(nursery).muted == false }
        assertEquals(true, controllerFor(playroom).muted)
    }

    @Test
    fun `an empty viewer offers setup rather than a switch for sound`() {
        composeRule.setContent { Screen(cameras = emptyList()) }

        composeRule.onNodeWithTag("toggle-sound").assertDoesNotExist()
        composeRule.onNodeWithTag("open-settings").assertExists()
    }

    private companion object {
        /** Short enough that a turn passing is not a slow test. */
        const val ROTATION_MS = 200L

        /** One column, and only room for two 16:9 tiles: the third must scroll. */
        const val SHORT_SCREEN = "w400dp-h400dp"

        /** Wide enough for three columns. */
        const val TABLET_SCREEN = "w1200dp-h900dp"

        /** A large phone in landscape, or a tablet in split screen. */
        const val MEDIUM_SCREEN = "w700dp-h500dp"
    }
}

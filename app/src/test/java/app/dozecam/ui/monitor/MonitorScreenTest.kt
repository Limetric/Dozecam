package app.dozecam.ui.monitor

import android.view.ViewGroup
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.dozecam.data.Camera
import app.dozecam.player.PlayerEvent
import app.dozecam.player.StreamSource
import app.dozecam.player.VideoPlayerController
import app.dozecam.ui.theme.DozecamTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
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

        /** How many times a stream was negotiated on this player. */
        var plays = 0

        /** Whether a decoder is currently running for this camera. */
        var decoding = true

        /** The view currently holding the picture, if any. */
        var attachedTo: ViewGroup? = null

        override fun attach(container: ViewGroup) {
            attachedTo = container
        }

        override fun detach() {
            attachedTo = null
        }

        override fun play(source: StreamSource) {
            played = source
            plays++
            mutedWhenPlayed = muted
        }

        override fun setMuted(muted: Boolean) {
            this.muted = muted
        }

        override fun setVideoEnabled(enabled: Boolean) {
            decoding = enabled
        }

        override fun stop() = Unit
        override fun release() {
            released = true
        }
    }

    private val controllers = mutableListOf<FakeController>()

    /** A host whose state the test drives, to stand in for backgrounding the app. */
    private class FakeLifecycleOwner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this).apply {
            currentState = Lifecycle.State.RESUMED
        }
        override val lifecycle: Lifecycle get() = registry
    }

    /** Captured from the composition, so Back can be pressed the way the system does. */
    private var backDispatcher: OnBackPressedDispatcher? = null

    private fun pressBack() {
        composeRule.runOnUiThread { backDispatcher!!.onBackPressed() }
        composeRule.waitForIdle()
    }

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
        inactivityTimeoutMs: Long = INACTIVITY_MS,
        alertCameraId: String? = null,
        onAlertConsumed: () -> Unit = {},
        onFullscreenChange: (Boolean) -> Unit = {},
        onAlertDismissed: () -> Unit = {},
    ) {
        backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
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
                inactivityTimeoutMs = inactivityTimeoutMs,
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

        pressBack()

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

        pressBack()
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

        pressBack()
        composeRule.onNodeWithTag("camera-list-1").assertExists()

        assertTrue(dismissed)
    }

    @Test
    fun `tapping a tile promotes it to fullscreen and back returns`() {
        composeRule.setContent { Screen(cameras = listOf(nursery, playroom)) }

        composeRule.onNodeWithTag("camera-tile-Nursery").performClick()
        composeRule.onNodeWithTag("fullscreen-tile").assertExists()

        pressBack()
        composeRule.onNodeWithTag("camera-list-1").assertExists()
    }

    @Test
    fun `a camera left alone hands the screen back to the grid`() {
        var wentFullscreen: Boolean? = null
        composeRule.setContent {
            Screen(
                cameras = listOf(nursery, playroom),
                onFullscreenChange = { wentFullscreen = it },
            )
        }
        composeRule.onNodeWithTag("camera-tile-Nursery").performClick()
        composeRule.onNodeWithTag("fullscreen-tile").assertExists()

        composeRule.mainClock.advanceTimeBy(INACTIVITY_MS + 1)

        // A phone left face up on one room stopped showing the rest of the
        // house without ever saying so.
        composeRule.onNodeWithTag("fullscreen-tile").assertDoesNotExist()
        composeRule.onNodeWithTag("camera-list-1").assertExists()
        // Timing out is leaving, all the way down: the host hears the layout
        // change it takes as its cue to put the system bars back in their place.
        assertEquals(false, wentFullscreen)
    }

    @Test
    fun `a camera list refresh does not give the wait a fresh start`() {
        var shown by mutableStateOf(listOf(nursery, playroom))
        composeRule.setContent { Screen(cameras = shown) }
        composeRule.onNodeWithTag("camera-tile-Nursery").performClick()
        composeRule.mainClock.advanceTimeBy(2_000)

        // A camera switched on in settings, or a re-resolved source.
        composeRule.runOnIdle { shown = listOf(nursery, playroom, hall) }
        composeRule.waitForIdle()

        // The room on screen has not changed, so neither has how long it has
        // been sitting there unattended.
        composeRule.onNodeWithTag("inactivity-countdown").assertTextEquals("All cameras in 2s")
        composeRule.mainClock.advanceTimeBy(2_001)
        composeRule.onNodeWithTag("camera-list-1").assertExists()
    }

    @Test
    fun `the countdown says how long is left`() {
        composeRule.setContent { Screen(cameras = listOf(nursery, playroom)) }
        composeRule.onNodeWithTag("camera-tile-Nursery").performClick()

        composeRule.onNodeWithTag("inactivity-countdown").assertTextEquals("All cameras in 4s")
        composeRule.mainClock.advanceTimeBy(1_000)

        composeRule.onNodeWithTag("inactivity-countdown").assertTextEquals("All cameras in 3s")
        composeRule.onNodeWithTag("inactivity-bar").assertExists()
    }

    @Test
    fun `touching the picture keeps the camera up`() {
        composeRule.setContent { Screen(cameras = listOf(nursery, playroom)) }
        composeRule.onNodeWithTag("camera-tile-Nursery").performClick()
        composeRule.mainClock.advanceTimeBy(INACTIVITY_MS - 1_000)

        composeRule.onNodeWithTag("fullscreen-tile").performClick()
        composeRule.mainClock.advanceTimeBy(INACTIVITY_MS - 1_000)

        // A tap says "I am still here" — the whole wait starts over, and the
        // camera does not fall back to the grid under someone's eyes.
        composeRule.onNodeWithTag("fullscreen-tile").assertExists()
        composeRule.onNodeWithTag("inactivity-countdown").assertTextEquals("All cameras in 1s")
    }

    @Test
    fun `the stay button keeps the camera up`() {
        composeRule.setContent { Screen(cameras = listOf(nursery, playroom)) }
        composeRule.onNodeWithTag("camera-tile-Nursery").performClick()
        composeRule.mainClock.advanceTimeBy(INACTIVITY_MS - 1_000)

        composeRule.onNodeWithTag("inactivity-stay").performClick()
        composeRule.mainClock.advanceTimeBy(INACTIVITY_MS - 1_000)

        // The discoverable answer to the countdown, for anyone who would not
        // guess that tapping the video is one.
        composeRule.onNodeWithTag("fullscreen-tile").assertExists()
    }

    @Test
    fun `reaching for the sound keeps the camera up`() {
        var sound by mutableStateOf(false)
        composeRule.setContent {
            Screen(
                cameras = listOf(nursery, playroom),
                soundEnabled = sound,
                onSoundEnabledChange = { sound = it },
            )
        }
        composeRule.onNodeWithTag("camera-tile-Nursery").performClick()
        composeRule.mainClock.advanceTimeBy(INACTIVITY_MS - 1_000)

        composeRule.onNodeWithTag("toggle-sound").performClick()
        composeRule.mainClock.advanceTimeBy(INACTIVITY_MS - 1_000)

        // Switching sound on is someone being there, and the room they just
        // asked to hear must not vanish a moment later.
        composeRule.onNodeWithTag("fullscreen-tile").assertExists()
    }

    @Test
    fun `listening to a room does not hold the timer off`() {
        composeRule.setContent {
            Screen(cameras = listOf(nursery, playroom), soundEnabled = true)
        }
        composeRule.onNodeWithTag("camera-tile-Nursery").performClick()
        composeRule.onNodeWithTag("fullscreen-tile").assertExists()

        composeRule.mainClock.advanceTimeBy(INACTIVITY_MS + 1)

        // One rule, sound or not: a listening session is kept alive by a tap a
        // minute, not by the speaker being on.
        composeRule.onNodeWithTag("camera-list-1").assertExists()
    }

    @Test
    fun `a camera an alert opened times out like any other and ends the alert`() {
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

        composeRule.mainClock.advanceTimeBy(INACTIVITY_MS + 1)

        // The alert bought a look at one room. Once nobody is looking it ends
        // like any other — including handing the lock screen back, so the grid
        // is never left sitting over the keyguard.
        composeRule.onNodeWithTag("camera-list-1").assertExists()
        assertTrue(dismissed)
    }

    @Test
    fun `an alert swapping the camera gives the new room its own wait`() {
        var alerted by mutableStateOf<String?>(null)
        composeRule.setContent {
            Screen(cameras = listOf(nursery, playroom), alertCameraId = alerted)
        }
        composeRule.onNodeWithTag("camera-tile-Nursery").performClick()
        composeRule.mainClock.advanceTimeBy(INACTIVITY_MS - 1_000)

        composeRule.runOnIdle { alerted = "b" }
        composeRule.waitForIdle()

        // A new room on screen is a new reason to be looking; it must not
        // inherit the last second of the camera it replaced.
        composeRule.onNodeWithTag("inactivity-countdown").assertTextEquals("All cameras in 4s")
        composeRule.mainClock.advanceTimeBy(INACTIVITY_MS - 1_000)
        composeRule.onNodeWithTag("fullscreen-tile").assertExists()
    }

    @Test
    fun `time spent away from the viewer does not count against it`() {
        val host = FakeLifecycleOwner()
        composeRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides host) {
                Screen(cameras = listOf(nursery, playroom))
            }
        }
        composeRule.onNodeWithTag("camera-tile-Nursery").performClick()
        composeRule.mainClock.advanceTimeBy(INACTIVITY_MS - 1_000)

        composeRule.runOnUiThread { host.registry.currentState = Lifecycle.State.CREATED }
        composeRule.mainClock.advanceTimeBy(INACTIVITY_MS * 4)
        composeRule.runOnUiThread { host.registry.currentState = Lifecycle.State.RESUMED }
        composeRule.waitForIdle()

        // A camera nobody can see is not a camera being ignored. Coming back to
        // a phone that was in a pocket must show the room, not the tail of a
        // countdown that ran out in the dark.
        composeRule.onNodeWithTag("fullscreen-tile").assertExists()
        composeRule.onNodeWithTag("inactivity-countdown").assertTextEquals("All cameras in 4s")
    }

    @Test
    fun `a room getting loud again restarts the wait on the camera already up`() {
        var alerted by mutableStateOf<String?>(null)
        var dismissed = false
        composeRule.setContent {
            Screen(
                cameras = listOf(nursery, playroom),
                alertCameraId = alerted,
                onAlertConsumed = { alerted = null },
                onAlertDismissed = { dismissed = true },
            )
        }
        composeRule.onNodeWithTag("camera-tile-Nursery").performClick()
        composeRule.mainClock.advanceTimeBy(INACTIVITY_MS - 1_000)

        // The detector re-arms and fires for the very room already on screen.
        composeRule.runOnIdle { alerted = "a" }
        composeRule.waitForIdle()

        // Nothing on screen changed, so nothing would otherwise tell the wait
        // that the newest reason to be looking arrived a second ago.
        composeRule.onNodeWithTag("inactivity-countdown").assertTextEquals("All cameras in 4s")
        composeRule.mainClock.advanceTimeBy(INACTIVITY_MS - 1_000)
        composeRule.onNodeWithTag("fullscreen-tile").assertExists()
        // An alert that woke the phone must not hand the lock screen straight
        // back because the last one was nearly out of time.
        assertFalse(dismissed)
    }

    @Test
    fun `the grid counts down to nothing`() {
        composeRule.setContent { Screen(cameras = listOf(nursery, playroom)) }

        // Every camera at once is where the viewer belongs; there is nothing
        // for it to return to.
        composeRule.onNodeWithTag("inactivity-notice").assertDoesNotExist()
        composeRule.onNodeWithTag("inactivity-bar").assertDoesNotExist()
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
        composeRule.onNodeWithTag("fullscreen-tile").assertExists()

        // Listening to the room is the whole point of opening one camera.
        // Named rather than taken by position: which player was built last says
        // nothing about which camera is on screen.
        composeRule.waitUntil { controllerFor(playroom).muted == false }
    }

    @Test
    fun `a camera opened by an alert stays silent until sound is asked for`() {
        controllers.clear()
        composeRule.setContent {
            Screen(cameras = listOf(nursery, playroom), alertCameraId = "b")
        }
        composeRule.onNodeWithTag("fullscreen-tile").assertExists()
        composeRule.waitForIdle()

        // The screen can come on by itself, over a lock screen, in the middle
        // of the night. It must not start talking by itself as well.
        assertEquals(true, controllerFor(playroom).muted)
        assertEquals(true, controllerFor(playroom).mutedWhenPlayed)
        assertTrue(controllers.all { it.muted == true })
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
    @Config(qualifiers = TABLET_SCREEN)
    fun `opening a camera from the grid keeps the stream it was already showing`() {
        controllers.clear()
        composeRule.setContent { Screen(cameras = listOf(nursery, playroom)) }
        composeRule.waitUntil { controllers.size == 2 }
        val player = controllerFor(nursery)
        val inGrid = player.attachedTo

        composeRule.onNodeWithTag("camera-tile-Nursery").performClick()
        composeRule.onNodeWithTag("fullscreen-tile").assertExists()
        // Waits for the handover to have actually been reconciled. Asserting
        // straight after the click reads the state before any of this has
        // happened, which passes whether the stream was reused or rebuilt.
        composeRule.waitUntil { player.attachedTo !== inGrid }

        // The whole point: a camera the grid was already playing must not be
        // torn down and negotiated again just because it filled the screen.
        assertEquals(1, player.plays)
        assertFalse("the open camera was torn down and rebuilt", player.released)
        assertNotNull(player.attachedTo)
        assertEquals(2, controllers.size)
    }

    @Test
    @Config(qualifiers = TABLET_SCREEN)
    fun `the camera opened moves its picture to the screen filling the view`() {
        controllers.clear()
        composeRule.setContent { Screen(cameras = listOf(nursery, playroom)) }
        composeRule.waitUntil { controllers.size == 2 }
        val player = controllerFor(nursery)
        val inGrid = player.attachedTo

        composeRule.onNodeWithTag("camera-tile-Nursery").performClick()
        composeRule.onNodeWithTag("fullscreen-tile").assertExists()
        composeRule.waitUntil { player.attachedTo !== inGrid }

        // Same session, different view: the running player is handed to the
        // tile that now fills the screen rather than left behind with the grid.
        assertFalse(player.released)
        assertNotNull(player.attachedTo)
        assertNotEquals(inGrid, player.attachedTo)
    }

    @Test
    @Config(qualifiers = TABLET_SCREEN)
    fun `the rest of the grid stays connected without decoding behind it`() {
        controllers.clear()
        composeRule.setContent { Screen(cameras = listOf(nursery, playroom)) }
        composeRule.waitUntil { controllers.size == 2 }
        val other = controllerFor(playroom)

        composeRule.onNodeWithTag("camera-tile-Nursery").performClick()
        composeRule.onNodeWithTag("fullscreen-tile").assertExists()
        composeRule.waitUntil { !other.decoding }

        // Its session is worth keeping — that is what makes coming back cheap —
        // but a decoder for a picture nobody can see is not.
        assertFalse("a camera left behind was torn down", other.released)
        assertEquals(1, other.plays)
        // Nothing on screen can say where a sound is coming from while the
        // camera making it is not on it.
        assertEquals(true, other.muted)
    }

    @Test
    @Config(qualifiers = TABLET_SCREEN)
    fun `coming back to the grid costs no camera a reconnection`() {
        controllers.clear()
        composeRule.setContent { Screen(cameras = listOf(nursery, playroom)) }
        composeRule.waitUntil { controllers.size == 2 }
        val opened = controllerFor(nursery)
        val other = controllerFor(playroom)

        composeRule.onNodeWithTag("camera-tile-Nursery").performClick()
        composeRule.onNodeWithTag("fullscreen-tile").assertExists()
        composeRule.waitUntil { !other.decoding }

        pressBack()
        composeRule.onNodeWithTag("camera-list-2").assertExists()
        composeRule.waitUntil { other.decoding }

        // Both directions, and no new players either: the grid comes back to
        // the sessions it left rather than rebuilding the house.
        assertEquals(1, opened.plays)
        assertEquals(1, other.plays)
        assertFalse(opened.released)
        assertFalse(other.released)
        assertEquals(2, controllers.size)
    }

    @Test
    @Config(qualifiers = SHORT_SCREEN)
    fun `a camera scrolled out of the grid is not kept warm for nothing`() {
        controllers.clear()
        composeRule.setContent {
            Screen(cameras = listOf(nursery, playroom, hall))
        }
        composeRule.waitUntil { controllers.size == 2 }

        // Only two tiles fit, so the third never had a session to keep. Warmth
        // spares a camera the grid was showing; it does not go looking for one.
        composeRule.onNodeWithTag("camera-tile-Hall").assertDoesNotExist()
        assertEquals(2, controllers.size)
    }

    @Test
    @Config(qualifiers = TABLET_SCREEN)
    fun `an alert swapping the open camera keeps the one it replaced connected`() {
        controllers.clear()
        var alerted by mutableStateOf<String?>(null)
        composeRule.setContent {
            Screen(cameras = listOf(nursery, playroom, hall), alertCameraId = alerted)
        }
        composeRule.waitUntil { controllers.size == 3 }
        val first = controllerFor(nursery)

        composeRule.onNodeWithTag("camera-tile-Nursery").performClick()
        composeRule.onNodeWithTag("fullscreen-tile").assertExists()
        // A room gets loud while another is already open.
        composeRule.runOnIdle { alerted = "b" }
        composeRule.waitForIdle()
        composeRule.waitUntil { !first.decoding }

        // The camera being left is one of the grid's own. Dropping it here
        // would make it the single room that had to reconnect on the way back,
        // for no reason other than having been the one on screen.
        assertFalse("the camera left behind was torn down", first.released)
        assertEquals(1, first.plays)

        pressBack()
        composeRule.onNodeWithTag("camera-list-3").assertExists()
        composeRule.waitUntil { first.decoding }
        assertEquals(1, first.plays)
    }

    @Test
    @Config(qualifiers = TABLET_SCREEN)
    fun `a camera switched off while another is open is let go rather than kept warm`() {
        controllers.clear()
        var shown by mutableStateOf(listOf(nursery, playroom))
        composeRule.setContent { Screen(cameras = shown) }
        composeRule.waitUntil { controllers.size == 2 }
        val gone = controllerFor(playroom)

        composeRule.onNodeWithTag("camera-tile-Nursery").performClick()
        composeRule.onNodeWithTag("fullscreen-tile").assertExists()
        composeRule.runOnIdle { shown = listOf(nursery) }
        pressBack()

        // Warmth is for cameras the grid will want back. One switched off in
        // settings is not coming back, and holding its socket open would be a
        // leak with a friendly name.
        composeRule.waitUntil { gone.released }
    }

    @Test
    @Config(qualifiers = TABLET_SCREEN)
    fun `going to the background still tears every camera down`() {
        controllers.clear()
        val host = FakeLifecycleOwner()
        composeRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides host) {
                Screen(cameras = listOf(nursery, playroom))
            }
        }
        composeRule.waitUntil { controllers.size == 2 }

        composeRule.onNodeWithTag("camera-tile-Nursery").performClick()
        composeRule.onNodeWithTag("fullscreen-tile").assertExists()
        composeRule.runOnUiThread { host.registry.currentState = Lifecycle.State.CREATED }
        composeRule.waitForIdle()

        // Keeping a camera warm is for a grid that is still on screen. A phone
        // in a pocket must not be holding a house's worth of sockets open.
        composeRule.waitUntil { controllers.all { it.released } }
    }

    @Test
    @Config(qualifiers = TABLET_SCREEN)
    fun `coming back to the foreground builds the cameras again`() {
        controllers.clear()
        val host = FakeLifecycleOwner()
        composeRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides host) {
                Screen(cameras = listOf(nursery, playroom))
            }
        }
        composeRule.waitUntil { controllers.size == 2 }

        composeRule.runOnUiThread { host.registry.currentState = Lifecycle.State.CREATED }
        composeRule.waitUntil { controllers.all { it.released } }
        composeRule.runOnUiThread { host.registry.currentState = Lifecycle.State.RESUMED }

        // Released on the way out, so there is nothing left to reuse: the
        // viewer has to be able to build them again on the way back in.
        composeRule.waitUntil { controllers.size == 4 }
        composeRule.waitUntil { controllers.takeLast(2).all { it.plays == 1 } }
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

        /** Whole seconds, so the readout can be checked, but only a few of them. */
        const val INACTIVITY_MS = 4_000L

        /** One column, and only room for two 16:9 tiles: the third must scroll. */
        const val SHORT_SCREEN = "w400dp-h400dp"

        /** Wide enough for three columns. */
        const val TABLET_SCREEN = "w1200dp-h900dp"

        /** A large phone in landscape, or a tablet in split screen. */
        const val MEDIUM_SCREEN = "w700dp-h500dp"
    }
}

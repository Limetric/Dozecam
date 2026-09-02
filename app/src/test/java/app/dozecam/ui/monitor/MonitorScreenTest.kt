package app.dozecam.ui.monitor

import android.view.ViewGroup
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.dozecam.audio.talkback.Talkback
import app.dozecam.audio.talkback.TalkbackAvailability
import app.dozecam.data.Camera
import app.dozecam.network.NetworkReach
import app.dozecam.player.PlayerEvent
import app.dozecam.player.StreamSource
import app.dozecam.player.VideoPlayerController
import app.dozecam.ui.theme.DozecamTheme
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
        networkReach: NetworkReach = NetworkReach.LOCAL,
        unmonitorable: List<Camera> = emptyList(),
        hasDisabledOnly: Boolean = false,
        onOpenSettings: () -> Unit = {},
        onOpenOnboarding: () -> Unit = {},
        monitoringRunning: Boolean = false,
        canMonitor: Boolean = false,
        stoppedByUser: Boolean = false,
        onStopMonitoring: () -> Unit = {},
        onStartMonitoring: () -> Unit = {},
        armingGraceMs: Long = ARMING_GRACE_MS,
        soundEnabled: Boolean = false,
        onSoundEnabledChange: (Boolean) -> Unit = {},
        keepScreenOn: Boolean = true,
        onKeepScreenOnChange: (Boolean) -> Unit = {},
        soundGranted: Boolean = true,
        listening: Boolean = false,
        onListeningChange: (Boolean) -> Unit = {},
        listeningCameraId: String? = null,
        listenCameraId: String? = null,
        onListenCameraChange: (String) -> Unit = {},
        audioLevels: Map<String, Float> = emptyMap(),
        audioThreshold: Float = 0.10f,
        soundRotationIntervalMs: Long = ROTATION_MS,
        listenRefusalGraceMs: Long = LISTEN_GRACE_MS,
        inactivityTimeoutMs: Long = INACTIVITY_MS,
        alertCameraId: String? = null,
        onAlertConsumed: () -> Unit = {},
        onFullscreenChange: (Boolean) -> Unit = {},
        onAlertDismissed: () -> Unit = {},
        talkback: Talkback? = null,
        talkbackMinPressMs: Long = TALKBACK_MIN_PRESS,
    ) {
        backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
        DozecamTheme {
            MonitorScreen(
                cameras = cameras,
                sources = sourcesFor(*cameras.toTypedArray()),
                controllerFactory = { FakeController().also { controllers += it } },
                networkReach = networkReach,
                unmonitorable = unmonitorable,
                hasDisabledOnly = hasDisabledOnly,
                onOpenSettings = onOpenSettings,
                onOpenOnboarding = onOpenOnboarding,
                monitoringRunning = monitoringRunning,
                canMonitor = canMonitor,
                stoppedByUser = stoppedByUser,
                onStopMonitoring = onStopMonitoring,
                onStartMonitoring = onStartMonitoring,
                armingGraceMs = armingGraceMs,
                soundEnabled = soundEnabled,
                onSoundEnabledChange = onSoundEnabledChange,
                keepScreenOn = keepScreenOn,
                onKeepScreenOnChange = onKeepScreenOnChange,
                soundGranted = soundGranted,
                listening = listening,
                onListeningChange = onListeningChange,
                listeningCameraId = listeningCameraId,
                listenCameraId = listenCameraId,
                onListenCameraChange = onListenCameraChange,
                audioLevels = audioLevels,
                audioThreshold = audioThreshold,
                soundRotationIntervalMs = soundRotationIntervalMs,
                listenRefusalGraceMs = listenRefusalGraceMs,
                inactivityTimeoutMs = inactivityTimeoutMs,
                alertCameraId = alertCameraId,
                onAlertConsumed = onAlertConsumed,
                onFullscreenChange = onFullscreenChange,
                onAlertDismissed = onAlertDismissed,
                talkback = talkback,
                talkbackMinPressMs = talkbackMinPressMs,
            )
        }
    }


    // --- listen mode -------------------------------------------------------

    @Test
    fun `there is no speaker switch while nothing is listening`() {
        composeRule.setContent {
            Screen(cameras = listOf(nursery), monitoringRunning = false)
        }

        // Listen mode is the monitor's decoding turned up; with the monitor
        // stopped this would be a switch for a speaker with nothing behind it.
        composeRule.onNodeWithTag("toggle-listen").assertDoesNotExist()
    }

    @Test
    fun `one camera is not a question worth asking`() {
        var listening: Boolean? = null
        var chosen: String? = null
        composeRule.setContent {
            Screen(
                cameras = listOf(nursery),
                monitoringRunning = true,
                onListeningChange = { listening = it },
                onListenCameraChange = { chosen = it },
            )
        }

        composeRule.onNodeWithTag("toggle-listen").performClick()
        composeRule.waitForIdle()

        assertEquals(true, listening)
        assertEquals(nursery.id, chosen)
    }

    @Test
    fun `several cameras get asked which room should be heard`() {
        var listening: Boolean? = null
        composeRule.setContent {
            Screen(
                cameras = listOf(nursery, playroom),
                monitoringRunning = true,
                onListeningChange = { listening = it },
            )
        }

        composeRule.onNodeWithTag("toggle-listen").performClick()
        composeRule.waitForIdle()

        // Nothing starts until the room is named: with the display off there is
        // no tile to say which one is talking.
        assertNull(listening)
        composeRule.onNodeWithTag("listen-camera-${nursery.id}").assertExists()
        composeRule.onNodeWithTag("listen-camera-${playroom.id}").assertExists()
    }

    @Test
    fun `picking a room starts it and remembers it`() {
        var listening: Boolean? = null
        var chosen: String? = null
        composeRule.setContent {
            Screen(
                cameras = listOf(nursery, playroom),
                monitoringRunning = true,
                onListeningChange = { listening = it },
                onListenCameraChange = { chosen = it },
            )
        }

        composeRule.onNodeWithTag("toggle-listen").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("listen-camera-${playroom.id}").performClick()
        composeRule.waitForIdle()

        assertEquals(playroom.id, chosen)
        assertEquals(true, listening)
    }

    @Test
    fun `a camera the monitor cannot hear is not offered as one to play aloud`() {
        composeRule.setContent {
            Screen(
                cameras = listOf(nursery, playroom),
                unmonitorable = listOf(playroom),
                monitoringRunning = true,
            )
        }

        composeRule.onNodeWithTag("toggle-listen").performClick()
        composeRule.waitForIdle()

        // Only one room left that has any audio at all, so there is nothing to
        // ask — and the room with none is never offered.
        composeRule.onNodeWithTag("listen-camera-${playroom.id}").assertDoesNotExist()
    }

    @Test
    fun `starting the speaker takes the viewer's own sound with it`() {
        var viewerSound: Boolean? = null
        composeRule.setContent {
            Screen(
                cameras = listOf(nursery),
                monitoringRunning = true,
                soundEnabled = true,
                onSoundEnabledChange = { viewerSound = it },
            )
        }

        composeRule.onNodeWithTag("toggle-listen").performClick()
        composeRule.waitForIdle()

        // Two switches for one speaker, asking for different rooms. Listen mode
        // stands down while the viewer is audible, so a switch left up here
        // would arm a nursery that never arrives.
        assertEquals(false, viewerSound)
    }

    @Test
    fun `nothing is confirmed aloud until a room actually is`() {
        var listening by mutableStateOf(false)
        composeRule.setContent {
            Screen(
                cameras = listOf(nursery),
                monitoringRunning = true,
                listening = listening,
                onListeningChange = { listening = it },
                // The speaker has not been won yet.
                listeningCameraId = null,
            )
        }

        composeRule.onNodeWithTag("toggle-listen").performClick()
        composeRule.waitForIdle()

        // The switch took; the speaker has not been won yet. Nothing may claim
        // a room is audible before it is.
        composeRule.onNodeWithText(LISTEN_ON_CONFIRMED).assertDoesNotExist()
    }

    @Test
    fun `a room that starts playing says which one it is`() {
        var listening by mutableStateOf(false)
        var playing by mutableStateOf<String?>(null)
        composeRule.setContent {
            Screen(
                cameras = listOf(nursery),
                monitoringRunning = true,
                listening = listening,
                onListeningChange = {
                    listening = it
                    playing = if (it) nursery.id else null
                },
                listeningCameraId = playing,
            )
        }

        composeRule.onNodeWithTag("toggle-listen").performClick()
        composeRule.waitForIdle()

        // Naming the room is the point: with the screen about to go off, this
        // is the last chance to say which one the phone will be broadcasting —
        // and what it will do to an alert once it is.
        composeRule.onNodeWithText(LISTEN_ON_CONFIRMED).assertExists()
    }

    @Test
    fun `a switch that came back off says the speaker was refused`() {
        var listening by mutableStateOf(false)
        composeRule.setContent {
            Screen(
                cameras = listOf(nursery),
                monitoringRunning = true,
                listening = listening,
                // The service's honesty rule, from this side: a refused request
                // switches itself back off rather than leaving a control that
                // says "on" next to a silent phone.
                onListeningChange = { listening = false },
            )
        }

        composeRule.onNodeWithTag("toggle-listen").performClick()
        composeRule.waitForIdle()

        // Not said at once: the answer has a flow to travel down, and "not yet"
        // must not be reported as "no".
        composeRule.onNodeWithText(LISTEN_REFUSED).assertDoesNotExist()
        composeRule.mainClock.advanceTimeBy(LISTEN_GRACE_MS + 1)
        composeRule.onNodeWithText(LISTEN_REFUSED).assertExists()
    }

    @Test
    fun `switching the speaker off says so at once`() {
        var listening: Boolean? = null
        composeRule.setContent {
            Screen(
                cameras = listOf(nursery),
                monitoringRunning = true,
                listening = true,
                listeningCameraId = nursery.id,
                onListeningChange = { listening = it },
            )
        }

        composeRule.onNodeWithTag("toggle-listen").performClick()
        composeRule.waitForIdle()

        assertEquals(false, listening)
        // Letting go of the speaker cannot fail, so this needs no waiting on.
        composeRule.onNodeWithText(LISTEN_OFF_CONFIRMED).assertExists()
    }

    /** A camera that can always be talked to, so the gesture is what is tested. */
    private class ReadyTalkback : Talkback {
        override val availability = MutableStateFlow<TalkbackAvailability>(TalkbackAvailability.Ready)
        override val talking = MutableStateFlow(false)
        val failed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        override val failures = failed
        var presses = 0
            private set
        var releases = 0
            private set

        override fun watch(camera: Camera?) = Unit
        override fun refresh() = Unit
        override fun press() { presses++ }
        override fun release() { releases++ }
    }

    /**
     * A tap on the talk-back button sends the lead-in silence, its tail, and no
     * words at all. The camera makes a small noise and the room hears nothing
     * said, which reads as a broken feature rather than a button used wrongly —
     * so it is named out loud.
     */
    @Test
    fun `letting go of talk-back too quickly says so`() {
        val talkback = ReadyTalkback()
        composeRule.setContent { Screen(cameras = listOf(nursery), talkback = talkback) }
        composeRule.onNodeWithTag("camera-tile-${nursery.name}").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("talkback-button").performTouchInput {
            down(center)
            up()
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText(TOO_SHORT).assertExists()
        // The press still ran: a tap is worth naming, not worth blocking.
        assertEquals(1, talkback.presses)
        assertEquals(1, talkback.releases)
    }

    /** A press long enough to have carried speech is left alone. */
    @Test
    fun `a held button says nothing about how long it was held`() {
        val talkback = ReadyTalkback()
        composeRule.setContent {
            // Nothing can be too short, so any release is a proper one.
            Screen(cameras = listOf(nursery), talkback = talkback, talkbackMinPressMs = 0L)
        }
        composeRule.onNodeWithTag("camera-tile-${nursery.name}").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("talkback-button").performTouchInput {
            down(center)
            up()
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText(TOO_SHORT).assertDoesNotExist()
        assertEquals(1, talkback.releases)
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
    fun `the viewer offers settings, sound, the screen switch and nothing else`() {
        composeRule.setContent { Screen(cameras = listOf(nursery)) }

        composeRule.onNodeWithTag("open-settings").assertExists()
        composeRule.onNodeWithTag("toggle-sound").assertExists()
        composeRule.onNodeWithTag("toggle-keep-screen").assertExists()
        // Tuning the monitor still lives in settings; the viewer says what it
        // is doing and offers to stop it, and nothing more than that.
        composeRule.onNodeWithTag("monitoring-switch").assertDoesNotExist()
        composeRule.onNodeWithTag("audio-level-meter").assertDoesNotExist()
    }

    /**
     * Live cameras are not evidence of anything: the grid looks the same
     * whether or not a service is listening behind it, so the one screen that
     * is always up has to say which it is.
     */
    @Test
    fun `the viewer says when it is listening`() {
        composeRule.setContent {
            Screen(cameras = listOf(nursery), monitoringRunning = true, canMonitor = true)
        }

        composeRule.onNodeWithTag("monitoring-badge").assertTextEquals("Listening")
    }

    /**
     * The badge earns its place by being read, so it has to fit next to the
     * other two controls on the narrowest phone this app supports rather than
     * pushing itself off the edge of the row.
     */
    @Test
    @Config(qualifiers = NARROW_PHONE)
    fun `the chrome still fits on a narrow phone`() {
        composeRule.setContent {
            Screen(cameras = listOf(nursery), monitoringRunning = true, canMonitor = true)
        }

        composeRule.onNodeWithTag("monitoring-badge").assertIsDisplayed()
        composeRule.onNodeWithTag("toggle-sound").assertIsDisplayed()
        composeRule.onNodeWithTag("toggle-keep-screen").assertIsDisplayed()
        composeRule.onNodeWithTag("open-settings").assertIsDisplayed()
    }

    /**
     * The same fit, with the badge at its widest: "Not monitoring". Checked by
     * bounds rather than assertIsDisplayed, which is content to see a sliver
     * of a control the row has pushed halfway off the edge.
     */
    @Test
    @Config(qualifiers = NARROW_PHONE)
    fun `the chrome still fits on a narrow phone when stopped`() {
        composeRule.setContent {
            Screen(
                cameras = listOf(nursery),
                monitoringRunning = false,
                canMonitor = true,
                stoppedByUser = true,
            )
        }

        val root = composeRule.onRoot().getBoundsInRoot()
        for (tag in listOf("monitoring-badge", "toggle-sound", "toggle-keep-screen")) {
            val bounds = composeRule.onNodeWithTag(tag).getBoundsInRoot()
            assertTrue("$tag runs past the edge", bounds.right <= root.right)
            assertTrue("$tag starts before the screen", bounds.left >= root.left)
        }
        val settings = composeRule.onNodeWithTag("open-settings").getBoundsInRoot()
        assertTrue("settings runs past the edge", settings.right <= root.right)
        assertTrue("settings starts before the screen", settings.left >= root.left)
    }

    /**
     * Real fonts are wider than the test renderer's, so on a real narrow phone
     * the widest badge and three buttons can outgrow the line even though the
     * fit test above passes. What matters is what happens then: the control
     * that no longer fits steps down a line rather than being squeezed off the
     * edge of the screen.
     */
    @Test
    @Config(qualifiers = TOO_NARROW_FOR_ONE_LINE)
    fun `chrome that outgrows its line steps down instead of clipping`() {
        composeRule.setContent {
            Screen(
                cameras = listOf(nursery),
                monitoringRunning = false,
                canMonitor = true,
                stoppedByUser = true,
            )
        }

        val root = composeRule.onRoot().getBoundsInRoot()
        val keepScreen = composeRule.onNodeWithTag("toggle-keep-screen").getBoundsInRoot()
        val settings = composeRule.onNodeWithTag("open-settings").getBoundsInRoot()
        assertTrue("settings runs past the edge", settings.right <= root.right)
        assertTrue("settings starts before the screen", settings.left >= root.left)
        assertTrue("settings should have wrapped below", settings.top >= keepScreen.bottom)
    }

    @Test
    fun `a monitor that has been stopped says that too`() {
        composeRule.setContent {
            Screen(
                cameras = listOf(nursery),
                monitoringRunning = false,
                canMonitor = true,
                stoppedByUser = true,
            )
        }

        composeRule.onNodeWithTag("monitoring-badge").assertTextEquals("Not monitoring")
    }

    /**
     * Arming happens behind the first frame — a permission check, a settings
     * read and a service launch — so the viewer always opens on a monitor that
     * is not running yet. A badge that cried "not monitoring" every launch
     * would be ignored by the night it was telling the truth.
     */
    @Test
    fun `a monitor still starting is not accused of being off`() {
        composeRule.setContent {
            Screen(
                cameras = listOf(nursery),
                monitoringRunning = false,
                canMonitor = true,
                stoppedByUser = false,
            )
        }

        composeRule.onNodeWithTag("monitoring-badge").assertDoesNotExist()
    }

    /** But a start that never lands must not stay a secret either. */
    @Test
    fun `a start that never arrives is owned up to`() {
        composeRule.setContent {
            Screen(
                cameras = listOf(nursery),
                monitoringRunning = false,
                canMonitor = true,
                stoppedByUser = false,
            )
        }

        composeRule.mainClock.advanceTimeBy(ARMING_GRACE_MS + 1)

        composeRule.onNodeWithTag("monitoring-badge").assertTextEquals("Not monitoring")
    }

    /** No such patience for a stop the user asked for: it is true on the spot. */
    @Test
    fun `a deliberate stop shows without waiting`() {
        var running by mutableStateOf(true)
        composeRule.setContent {
            Screen(
                cameras = listOf(nursery),
                monitoringRunning = running,
                canMonitor = true,
                stoppedByUser = !running,
            )
        }
        composeRule.onNodeWithTag("monitoring-badge").assertTextEquals("Listening")

        running = false

        composeRule.onNodeWithTag("monitoring-badge").assertTextEquals("Not monitoring")
    }

    /**
     * The unmonitorable notice already explains this case; a badge offering to
     * start what cannot start would only be a second thing to read past.
     */
    @Test
    fun `nothing listenable is nothing to badge`() {
        composeRule.setContent {
            Screen(
                cameras = listOf(nursery),
                unmonitorable = listOf(nursery),
                monitoringRunning = false,
                canMonitor = false,
            )
        }

        composeRule.onNodeWithTag("monitoring-badge").assertDoesNotExist()
    }

    /**
     * A stray tap over live video must not disarm the monitor: the cameras
     * would go on playing exactly as before, and nothing on screen would say
     * that the night had stopped being watched.
     */
    @Test
    fun `stopping asks before it stops`() {
        var stopped = false
        composeRule.setContent {
            Screen(
                cameras = listOf(nursery),
                monitoringRunning = true,
                canMonitor = true,
                onStopMonitoring = { stopped = true },
            )
        }

        composeRule.onNodeWithTag("monitoring-badge").performClick()

        composeRule.onNodeWithTag("confirm-stop-monitoring").assertIsDisplayed()
        assertFalse("the badge alone must not stop anything", stopped)
    }

    @Test
    fun `confirming stops the monitor`() {
        var stopped = false
        composeRule.setContent {
            Screen(
                cameras = listOf(nursery),
                monitoringRunning = true,
                canMonitor = true,
                onStopMonitoring = { stopped = true },
            )
        }

        composeRule.onNodeWithTag("monitoring-badge").performClick()
        composeRule.onNodeWithTag("confirm-stop-monitoring").performClick()

        assertTrue(stopped)
        composeRule.onNodeWithTag("confirm-stop-monitoring").assertDoesNotExist()
    }

    @Test
    fun `backing out of the question leaves the monitor listening`() {
        var stopped = false
        composeRule.setContent {
            Screen(
                cameras = listOf(nursery),
                monitoringRunning = true,
                canMonitor = true,
                onStopMonitoring = { stopped = true },
            )
        }

        composeRule.onNodeWithTag("monitoring-badge").performClick()
        composeRule.onNodeWithTag("keep-monitoring").performClick()

        assertFalse(stopped)
        composeRule.onNodeWithTag("confirm-stop-monitoring").assertDoesNotExist()
    }

    /** Starting again needs no ceremony: the risk is all in the other direction. */
    @Test
    fun `the same badge starts a stopped monitor again`() {
        var started = false
        composeRule.setContent {
            Screen(
                cameras = listOf(nursery),
                monitoringRunning = false,
                canMonitor = true,
                stoppedByUser = true,
                onStartMonitoring = { started = true },
            )
        }

        composeRule.onNodeWithTag("monitoring-badge").performClick()

        assertTrue(started)
        composeRule.onNodeWithTag("confirm-stop-monitoring").assertDoesNotExist()
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
    fun `the back button returns to the grid`() {
        composeRule.setContent { Screen(cameras = listOf(nursery, playroom)) }

        // Nothing to go back to from the grid, so no button there.
        composeRule.onNodeWithTag("back-to-grid").assertDoesNotExist()

        composeRule.onNodeWithTag("camera-tile-Nursery").performClick()
        composeRule.onNodeWithTag("back-to-grid").performClick()

        composeRule.onNodeWithTag("fullscreen-tile").assertDoesNotExist()
        composeRule.onNodeWithTag("camera-list-1").assertExists()
    }

    @Test
    fun `the back button leaves the connection state visible`() {
        composeRule.setContent { Screen(cameras = listOf(nursery, playroom)) }
        composeRule.onNodeWithTag("camera-tile-Nursery").performClick()

        // Both live in the top-start corner; the pill steps aside rather than
        // sitting under the button, because a covered "Reconnecting" would let
        // a frozen frame pass for a live one.
        val button = composeRule.onNodeWithTag("back-to-grid").getBoundsInRoot()
        val status = composeRule
            .onNodeWithText("CONNECTING", useUnmergedTree = true)
            .getBoundsInRoot()
        assertTrue("status=$status button=$button", status.left >= button.right)
    }

    @Test
    fun `the back button on an alerted camera ends the alert`() {
        var dismissed = false
        composeRule.setContent {
            Screen(
                cameras = listOf(nursery, playroom),
                alertCameraId = "b",
                onAlertDismissed = { dismissed = true },
            )
        }
        composeRule.onNodeWithTag("fullscreen-tile").assertExists()

        composeRule.onNodeWithTag("back-to-grid").performClick()

        // The same exit as Back: the lock-screen privilege goes with it.
        composeRule.onNodeWithTag("camera-list-1").assertExists()
        assertTrue(dismissed)
    }

    @Test
    fun `one swipe in from the left edge returns to the grid`() {
        composeRule.setContent { Screen(cameras = listOf(nursery, playroom)) }
        composeRule.onNodeWithTag("camera-tile-Nursery").performClick()

        // The system's first back swipe never reaches the app's back handler
        // while the bars are hidden, so the screen must answer the touch
        // itself — one swipe, not two.
        composeRule.onRoot().performTouchInput {
            swipeRight(startX = left + 1f, endX = centerX)
        }

        composeRule.onNodeWithTag("fullscreen-tile").assertDoesNotExist()
        composeRule.onNodeWithTag("camera-list-1").assertExists()
    }

    @Test
    fun `one swipe in from the right edge returns to the grid`() {
        composeRule.setContent { Screen(cameras = listOf(nursery, playroom)) }
        composeRule.onNodeWithTag("camera-tile-Nursery").performClick()

        composeRule.onRoot().performTouchInput {
            swipeLeft(startX = right - 1f, endX = centerX)
        }

        composeRule.onNodeWithTag("fullscreen-tile").assertDoesNotExist()
        composeRule.onNodeWithTag("camera-list-1").assertExists()
    }

    @Test
    fun `a swipe that starts away from the edge stays on the camera`() {
        composeRule.setContent { Screen(cameras = listOf(nursery, playroom)) }
        composeRule.onNodeWithTag("camera-tile-Nursery").performClick()

        // A drag across the middle of the picture is not the leave gesture.
        composeRule.onRoot().performTouchInput {
            swipeRight(startX = centerX, endX = right - 1f)
        }

        composeRule.onNodeWithTag("fullscreen-tile").assertExists()
    }

    @Test
    fun `a zoomed picture keeps its edge pans`() {
        composeRule.setContent { Screen(cameras = listOf(nursery, playroom)) }
        composeRule.onNodeWithTag("camera-tile-Nursery").performClick()
        composeRule.onNodeWithTag("fullscreen-tile").performTouchInput {
            pinch(
                start0 = center - Offset(width / 8f, 0f),
                end0 = center - Offset(width / 3f, 0f),
                start1 = center + Offset(width / 8f, 0f),
                end1 = center + Offset(width / 3f, 0f),
            )
        }

        // One finger on a zoomed picture pans it, and a pan may begin at the
        // edge; the swipe-to-leave stands down until the zoom is let go.
        composeRule.onRoot().performTouchInput {
            swipeRight(startX = left + 1f, endX = centerX)
        }

        composeRule.onNodeWithTag("fullscreen-tile").assertExists()
    }

    @Test
    fun `a pinch that starts at the edge stays on the camera`() {
        composeRule.setContent { Screen(cameras = listOf(nursery, playroom)) }
        composeRule.onNodeWithTag("camera-tile-Nursery").performClick()

        // Two fingers are a zoom, wherever they land: the first touching down
        // in the edge strip and travelling inward must not read as "leave".
        composeRule.onNodeWithTag("fullscreen-tile").performTouchInput {
            pinch(
                start0 = Offset(1f, centerY),
                end0 = Offset(centerX - 20f, centerY),
                start1 = Offset(centerX + 20f, centerY),
                end1 = Offset(right - 1f, centerY),
            )
        }

        composeRule.onNodeWithTag("fullscreen-tile").assertExists()
    }

    @Test
    fun `each monitored camera wears its own level meter`() {
        composeRule.setContent {
            Screen(
                cameras = listOf(nursery, playroom),
                audioLevels = mapOf("a" to 0.3f, "b" to 0.05f),
            )
        }

        composeRule.onNodeWithTag("camera-meter-Nursery", useUnmergedTree = true)
            .assertExists()
        composeRule.onNodeWithTag("camera-meter-Play room", useUnmergedTree = true)
            .assertExists()
        // Next to the name, not instead of it: in a grid the meter only means
        // something if the room it reports on is still named.
        composeRule.onNodeWithTag("camera-label-Nursery", useUnmergedTree = true)
            .assertExists()
    }

    /**
     * The overlays are Material surfaces now, and a surface takes pointer
     * input for itself so taps cannot fall through to whatever is behind it.
     * The tile is not behind them, though — it is around them — and a tap on
     * the name of a room is a tap on that room.
     */
    @Test
    fun `tapping a camera's name opens that camera`() {
        composeRule.setContent {
            Screen(cameras = listOf(nursery, playroom), audioLevels = mapOf("a" to 0.3f))
        }

        composeRule.onNodeWithTag("camera-label-Nursery", useUnmergedTree = true)
            .performClick()

        composeRule.onNodeWithTag("fullscreen-tile").assertExists()
    }

    @Test
    fun `a camera the monitor is not hearing shows no meter`() {
        // The monitor reports on the nursery only — the play room might be
        // unmonitorable, or monitoring might be down entirely.
        composeRule.setContent {
            Screen(
                cameras = listOf(nursery, playroom),
                audioLevels = mapOf("a" to 0.3f),
            )
        }

        composeRule.onNodeWithTag("camera-meter-Nursery", useUnmergedTree = true)
            .assertExists()
        // No meter at all, rather than a bar frozen at zero claiming the room
        // is quiet while nobody is checking.
        composeRule.onNodeWithTag("camera-meter-Play room", useUnmergedTree = true)
            .assertDoesNotExist()
    }

    @Test
    fun `a long name never pushes the meter under the audible badge`() {
        val verbose = Camera(
            "a",
            "The nursery at the far end of the upstairs hallway by the window",
            "rtsp://cam:7447/a",
        )
        composeRule.setContent {
            Screen(
                cameras = listOf(verbose),
                soundEnabled = true,
                audioLevels = mapOf("a" to 0.3f),
            )
        }

        val meter = composeRule
            .onNodeWithTag("camera-meter-${verbose.name}", useUnmergedTree = true)
            .getBoundsInRoot()
        val badge = composeRule
            .onNodeWithTag("audible-badge-${verbose.name}", useUnmergedTree = true)
            .getBoundsInRoot()

        // Two overlays that mean different things must both stay readable.
        assertTrue(
            "meter (ends at ${meter.right}) overlaps badge (starts at ${badge.left})",
            meter.right <= badge.left,
        )
    }

    @Test
    fun `fullscreen keeps the meter even though the name is hidden`() {
        composeRule.setContent {
            Screen(
                cameras = listOf(nursery),
                audioLevels = mapOf("a" to 0.3f),
            )
        }

        composeRule.onNodeWithTag("camera-tile-Nursery").performClick()
        composeRule.onNodeWithTag("fullscreen-tile").assertExists()

        composeRule.onNodeWithTag("camera-meter-Nursery", useUnmergedTree = true)
            .assertExists()
        composeRule.onNodeWithTag("camera-label-Nursery", useUnmergedTree = true)
            .assertDoesNotExist()

        // Above the countdown notice, not under it: both sit at the bottom for
        // the whole minute the notice counts, so they have to share it.
        val meter = composeRule
            .onNodeWithTag("camera-meter-Nursery", useUnmergedTree = true)
            .getBoundsInRoot()
        val notice = composeRule
            .onNodeWithTag("inactivity-notice", useUnmergedTree = true)
            .getBoundsInRoot()
        assertTrue(
            "meter (ends at ${meter.bottom}) overlaps notice (starts at ${notice.top})",
            meter.bottom <= notice.top,
        )
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
        // (A tick later than the wait itself: the screen watches for a hand
        // landing rather than lifting, so the minute restarts on the press.)
        composeRule.onNodeWithTag("fullscreen-tile").assertExists()
        composeRule.onNodeWithTag("inactivity-countdown").assertTextEquals("All cameras in 2s")
    }

    @Test
    fun `pinching the picture keeps the camera up`() {
        composeRule.setContent { Screen(cameras = listOf(nursery, playroom)) }
        composeRule.onNodeWithTag("camera-tile-Nursery").performClick()
        composeRule.mainClock.advanceTimeBy(INACTIVITY_MS - 1_000)

        composeRule.onNodeWithTag("fullscreen-tile").performTouchInput {
            pinch(
                start0 = center - Offset(width / 8f, 0f),
                end0 = center - Offset(width / 3f, 0f),
                start1 = center + Offset(width / 8f, 0f),
                end1 = center + Offset(width / 3f, 0f),
            )
        }
        composeRule.mainClock.advanceTimeBy(INACTIVITY_MS - 1_000)

        // Leaning into the picture is being there every bit as much as tapping
        // it: the two advances together are past the original deadline, so the
        // camera still being up means the pinch gave the wait a fresh start.
        // (No exact readout here — injecting the pinch spends a few frames of
        // the clock itself, unlike a click, so the tick alignment shifts.)
        composeRule.onNodeWithTag("fullscreen-tile").assertExists()
    }

    @Test
    fun `pinching magnifies the picture and only the one on screen alone`() {
        composeRule.setContent { Screen(cameras = listOf(nursery, playroom)) }
        composeRule.onNodeWithTag("camera-tile-Nursery").performClick()
        composeRule.waitForIdle()

        val host = controllerFor(nursery).attachedTo!!
        assertEquals(1f, host.scaleX)

        composeRule.onNodeWithTag("fullscreen-tile").performTouchInput {
            pinch(
                start0 = center - Offset(width / 8f, 0f),
                end0 = center - Offset(width / 3f, 0f),
                start1 = center + Offset(width / 8f, 0f),
                end1 = center + Offset(width / 3f, 0f),
            )
        }

        // The fingers spread to well over double their distance, and the view
        // holding the picture — not the tile's chrome — carries the scale.
        composeRule.runOnIdle {
            assertTrue("expected zoomed in, got scale ${host.scaleX}", host.scaleX > 1.5f)
            assertEquals(host.scaleX, host.scaleY)
        }

        // Back on the grid the same camera is a thumbnail again: whole rooms
        // only, whatever the last look leaned into.
        pressBack()
        val gridHost = controllerFor(nursery).attachedTo!!
        assertEquals(1f, gridHost.scaleX)
        assertEquals(0f, gridHost.translationX)
    }

    @Test
    fun `a pinch after a repeat alert reaches the countdown now on duty`() {
        var alerted by mutableStateOf<String?>(null)
        composeRule.setContent {
            Screen(
                cameras = listOf(nursery, playroom),
                alertCameraId = alerted,
                onAlertConsumed = { alerted = null },
            )
        }
        composeRule.onNodeWithTag("camera-tile-Nursery").performClick()
        // A first pinch starts the long-lived gesture coroutine while the
        // original countdown is the one on duty.
        composeRule.onNodeWithTag("fullscreen-tile").performTouchInput {
            pinch(
                start0 = center - Offset(width / 8f, 0f),
                end0 = center - Offset(width / 3f, 0f),
                start1 = center + Offset(width / 8f, 0f),
                end1 = center + Offset(width / 3f, 0f),
            )
        }

        // The same room gets loud again: nothing on screen changes, but the
        // wait is rebuilt so the alert gets its full minute.
        composeRule.runOnIdle { alerted = "a" }
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(INACTIVITY_MS - 1_000)

        // A pinch now must reach the rebuilt countdown — not reset the orphaned
        // one the gesture coroutine was born with while the live one runs out.
        composeRule.onNodeWithTag("fullscreen-tile").performTouchInput {
            pinch(
                start0 = center - Offset(width / 3f, 0f),
                end0 = center - Offset(width / 8f, 0f),
                start1 = center + Offset(width / 3f, 0f),
                end1 = center + Offset(width / 8f, 0f),
            )
        }
        composeRule.mainClock.advanceTimeBy(INACTIVITY_MS - 1_000)

        composeRule.onNodeWithTag("fullscreen-tile").assertExists()
    }

    /**
     * The chrome over a fullscreen camera sits beside the picture rather than
     * inside it, and a Material surface takes touches for itself instead of
     * letting them fall through. Touching any of it is still somebody being
     * there — the countdown's own sentence most of all, which is exactly what
     * a hand reaches for while reading how long is left.
     */
    @Test
    fun `touching the countdown itself keeps the camera up`() {
        composeRule.setContent {
            Screen(cameras = listOf(nursery, playroom), audioLevels = mapOf("a" to 0.3f))
        }
        composeRule.onNodeWithTag("camera-tile-Nursery").performClick()
        composeRule.mainClock.advanceTimeBy(INACTIVITY_MS - 1_000)

        composeRule.onNodeWithTag("inactivity-countdown").performClick()
        composeRule.mainClock.advanceTimeBy(INACTIVITY_MS - 1_000)

        composeRule.onNodeWithTag("fullscreen-tile").assertExists()
    }

    @Test
    fun `touching the level meter keeps the camera up`() {
        composeRule.setContent {
            Screen(cameras = listOf(nursery, playroom), audioLevels = mapOf("a" to 0.3f))
        }
        composeRule.onNodeWithTag("camera-tile-Nursery").performClick()
        composeRule.mainClock.advanceTimeBy(INACTIVITY_MS - 1_000)

        composeRule.onNodeWithTag("camera-meter-Nursery", useUnmergedTree = true)
            .performClick()
        composeRule.mainClock.advanceTimeBy(INACTIVITY_MS - 1_000)

        composeRule.onNodeWithTag("fullscreen-tile").assertExists()
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
    fun `the keep-screen button hands the choice back rather than deciding`() {
        var asked: Boolean? = null
        composeRule.setContent {
            Screen(
                cameras = listOf(nursery),
                keepScreenOn = true,
                onKeepScreenOnChange = { asked = it },
            )
        }

        composeRule.onNodeWithTag("toggle-keep-screen").performClick()

        assertEquals(false, asked)
    }

    @Test
    fun `the keep-screen button offers the way back from a sleeping screen`() {
        composeRule.setContent {
            Screen(cameras = listOf(nursery), keepScreenOn = false)
        }

        composeRule.onNodeWithTag("toggle-keep-screen")
            .assertContentDescriptionEquals("Keep the screen awake")
    }

    /**
     * The two icons flip between states that look alike at a glance — the
     * screen one especially — so a press has to say in words what it did.
     */
    @Test
    fun `the keep-screen button says what it just did`() {
        var keepScreenOn by mutableStateOf(true)
        composeRule.setContent {
            Screen(
                cameras = listOf(nursery),
                keepScreenOn = keepScreenOn,
                onKeepScreenOnChange = { keepScreenOn = it },
            )
        }

        composeRule.onNodeWithTag("toggle-keep-screen").performClick()
        composeRule.onNodeWithText("The screen will sleep as usual").assertIsDisplayed()

        // A second press replaces the first message rather than queueing
        // behind it: what is on screen is always the current state.
        composeRule.onNodeWithTag("toggle-keep-screen").performClick()
        composeRule.onNodeWithText("The screen will sleep as usual").assertDoesNotExist()
        composeRule.onNodeWithText("The screen will stay awake while cameras are showing")
            .assertIsDisplayed()
    }

    @Test
    fun `the sound button says what it just did`() {
        var soundEnabled by mutableStateOf(false)
        composeRule.setContent {
            Screen(
                cameras = listOf(nursery),
                soundEnabled = soundEnabled,
                onSoundEnabledChange = { soundEnabled = it },
            )
        }

        composeRule.onNodeWithTag("toggle-sound").performClick()
        composeRule.onNodeWithText("Sound on").assertIsDisplayed()

        composeRule.onNodeWithTag("toggle-sound").performClick()
        composeRule.onNodeWithText("Sound on").assertDoesNotExist()
        composeRule.onNodeWithText("Sound off").assertIsDisplayed()
    }

    /**
     * "Sound on" is a promise about the speaker, and the request for it can
     * be refused — in which case MainActivity flips the switch straight back
     * off. Confirming at the press would leave that user reassured and
     * unhearing, so the confirmation waits for sound to actually be granted,
     * and a refusal is reported as what it is.
     */
    @Test
    fun `a refused sound request is not announced as sound on`() {
        var soundEnabled by mutableStateOf(false)
        composeRule.setContent {
            Screen(
                cameras = listOf(nursery),
                soundEnabled = soundEnabled,
                soundGranted = false,
                onSoundEnabledChange = { soundEnabled = it },
            )
        }

        composeRule.onNodeWithTag("toggle-sound").performClick()
        composeRule.onNodeWithText("Sound on").assertDoesNotExist()

        // Something else owned the speaker, so the owner of the setting put
        // the switch back — the same move MainActivity makes.
        composeRule.runOnIdle { soundEnabled = false }

        composeRule.onNodeWithText("Sound on").assertDoesNotExist()
        composeRule.onNodeWithText("Something else is using the speaker — sound stays off")
            .assertIsDisplayed()
    }

    @Test
    fun `the sound button confirms itself on a single camera too`() {
        var soundEnabled by mutableStateOf(false)
        composeRule.setContent {
            Screen(
                cameras = listOf(nursery),
                soundEnabled = soundEnabled,
                onSoundEnabledChange = { soundEnabled = it },
            )
        }
        composeRule.onNodeWithTag("camera-tile-${nursery.name}").performClick()

        composeRule.onNodeWithTag("toggle-sound").performClick()

        composeRule.onNodeWithText("Sound on").assertIsDisplayed()
    }

    @Test
    fun `an empty viewer offers no screen switch`() {
        composeRule.setContent { Screen(cameras = emptyList()) }

        // The empty viewer never holds the display awake, so a switch for it
        // would be a control that does nothing.
        composeRule.onNodeWithTag("toggle-keep-screen").assertDoesNotExist()
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
        composeRule.waitUntil { !gone.decoding }

        composeRule.runOnIdle { shown = listOf(nursery) }

        // Warmth is for cameras the grid will want back. One switched off in
        // settings is not coming back, and holding its socket open would be a
        // leak with a friendly name — released while the other camera is still
        // up, rather than whenever someone next happens to leave it.
        composeRule.waitUntil { gone.released }
        composeRule.onNodeWithTag("fullscreen-tile").assertExists()
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
    fun `mobile data says the cameras are out of reach`() {
        composeRule.setContent {
            Screen(cameras = listOf(nursery), networkReach = NetworkReach.MOBILE_DATA)
        }

        // The tiles would say OFFLINE either way; only the screen knows the
        // reason is the device rather than the console.
        composeRule.onNodeWithTag("network-notice").assertIsDisplayed()
    }

    @Test
    fun `no network at all says so too`() {
        composeRule.setContent {
            Screen(cameras = listOf(nursery), networkReach = NetworkReach.OFFLINE)
        }

        composeRule.onNodeWithTag("network-notice").assertIsDisplayed()
    }

    @Test
    fun `a viewer on the home network says nothing about it`() {
        composeRule.setContent {
            Screen(cameras = listOf(nursery), networkReach = NetworkReach.LOCAL)
        }

        composeRule.onNodeWithTag("network-notice").assertDoesNotExist()
    }

    @Test
    fun `an empty viewer still says the device is off the network`() {
        composeRule.setContent {
            Screen(cameras = emptyList(), networkReach = NetworkReach.MOBILE_DATA)
        }

        // Setup needs the console as much as watching does, so "add a camera"
        // on its own would send the user round a loop that cannot close.
        composeRule.onNodeWithTag("network-notice").assertIsDisplayed()
    }

    @Test
    fun `wandering off the home network raises the notice without a reload`() {
        var reach by mutableStateOf(NetworkReach.LOCAL)
        composeRule.setContent { Screen(cameras = listOf(nursery), networkReach = reach) }
        composeRule.onNodeWithTag("network-notice").assertDoesNotExist()

        composeRule.runOnUiThread { reach = NetworkReach.MOBILE_DATA }

        composeRule.onNodeWithTag("network-notice").assertIsDisplayed()

        // And goes again on the way back, rather than lingering over a viewer
        // that has been streaming happily for an hour.
        composeRule.runOnUiThread { reach = NetworkReach.LOCAL }
        composeRule.onNodeWithTag("network-notice").assertDoesNotExist()
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

        /**
         * Every press in a test lasts microseconds, so with any real threshold
         * they all count as taps; a test wanting a proper hold passes zero.
         */
        const val TALKBACK_MIN_PRESS = 400L

        /** Must match R.string.talkback_too_short. */
        const val TOO_SHORT = "Hold the button down while you speak"

        /** Must match R.string.viewer_listen_on_confirmed, formatted for the nursery. */
        const val LISTEN_ON_CONFIRMED = "Nursery is playing aloud, and keeps playing with " +
            "the screen off. Alerts chime without lighting the screen."

        /** Must match R.string.viewer_listen_off_confirmed. */
        const val LISTEN_OFF_CONFIRMED = "Nothing is playing aloud"

        /** Must match R.string.viewer_listen_refused. */
        const val LISTEN_REFUSED =
            "Something else is using the speaker — nothing is playing aloud"

        /** Whole seconds, so the readout can be checked, but only a few of them. */
        const val INACTIVITY_MS = 4_000L

        /** One column, and only room for two 16:9 tiles: the third must scroll. */
        const val SHORT_SCREEN = "w400dp-h400dp"

        /** About as little width as a phone in portrait ever offers. */
        const val NARROW_PHONE = "w320dp-h640dp"

        /**
         * Narrower than any real phone. Robolectric measures text well short of
         * what real fonts take, so forcing the chrome to actually outgrow its
         * line — as a real narrow phone can — needs a width no device has.
         */
        const val TOO_NARROW_FOR_ONE_LINE = "w240dp-h640dp"

        /** Long enough to be waited past on purpose, short enough not to be slow. */
        const val ARMING_GRACE_MS = 500L

        /** The same, for the speaker listen mode has asked for. */
        const val LISTEN_GRACE_MS = 500L

        /** Wide enough for three columns. */
        const val TABLET_SCREEN = "w1200dp-h900dp"

        /** A large phone in landscape, or a tablet in split screen. */
        const val MEDIUM_SCREEN = "w700dp-h500dp"
    }
}

package app.dozecam.monitoring

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import app.dozecam.DozecamApp
import app.dozecam.data.Camera
import app.dozecam.player.ConnectionState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowPowerManager

/**
 * Covers the paths that do not build a decoder. Starting an actual
 * [CameraAudioMonitor] pulls in ExoPlayer and a live RTSP socket, so what a
 * running monitor does is covered by [MonitorPlanTest] and the player tests
 * instead.
 */
@RunWith(RobolectricTestRunner::class)
class MonitoringServiceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val container get() = (context.applicationContext as DozecamApp).container

    /**
     * The shadow keeps the newest wake lock in process-wide state, so "the
     * latest one" is only this service's if nothing else in the fork made one
     * first. Without this the assertions below read whichever test happened to
     * run before them, which makes them pass or fail on test ordering rather
     * than on anything the service did.
     */
    @Before
    fun forgetOtherTestsWakeLocks() {
        ShadowPowerManager.clearWakeLocks()
    }

    @Test
    fun `with nothing to listen to the service idles without a wake lock`() = runTest {
        val service = Robolectric.buildService(MonitoringService::class.java).create().get()

        // Nothing to decode, so nothing to keep the CPU awake for.
        assertFalse(ShadowPowerManager.getLatestWakeLock().isHeld)
        // But still alive: stopping here would race a camera switched straight
        // back on, and monitoring would silently stay off.
        assertFalse(shadowOf(service).isStoppedBySelf)
    }

    @Test
    fun `an unmonitorable camera is not something to stay awake for`() = runTest {
        // rtsps cannot be monitored: Media3's RTSP stack has no TLS. Such a
        // camera is watchable, so it stays enabled — but there is no audio to
        // decode for it.
        container.cameras.upsert(
            Camera("a", "Nursery", "rtsps://cam:7441/a", enabled = true),
        )

        Robolectric.buildService(MonitoringService::class.java).create().get()

        assertFalse(ShadowPowerManager.getLatestWakeLock().isHeld)
    }

    @Test
    fun `stopping clears the state the viewer reads`() = runTest {
        val controller = Robolectric.buildService(MonitoringService::class.java).create()
        container.monitoringState.put(CameraMonitorState("a", "Nursery", level = 0.4f))

        controller.destroy()

        assertFalse(container.monitoringState.serviceRunning.value)
        assertTrue(container.monitoringState.cameras.value.isEmpty())
    }

    /**
     * Stopping monitoring from the notification's own action leaves the shade
     * showing whatever the last alert posted; a card still offering a live view
     * of a camera nobody is listening to any more is a lie by the time it is
     * tapped.
     */
    @Test
    fun `stopping takes any alert notification with it`() = runTest {
        val controller = Robolectric.buildService(MonitoringService::class.java).create()
        MonitoringNotifications.postAlert(context, "a", "Nursery")

        controller.destroy()

        assertNull(
            shadowOf(context.getSystemService(NotificationManager::class.java))
                .getNotification(MonitoringNotifications.ALERT_NOTIFICATION_ID),
        )
    }

    private fun live(id: String, name: String) =
        CameraMonitorState(id, name, level = 0f, connection = ConnectionState.Live)

    @Test
    fun `every room the monitor can hear plays aloud together`() = runTest {
        Robolectric.buildService(MonitoringService::class.java).create().get()
        val state = container.monitoringState
        state.put(live("a", "Nursery"))
        state.put(live("b", "Play room"))
        shadowOf(Looper.getMainLooper()).idle()

        state.listenRequest.value = true
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(setOf("a", "b"), state.listeningCameraIds.value)
    }

    @Test
    fun `a room the monitor starts hearing later joins the mix`() = runTest {
        Robolectric.buildService(MonitoringService::class.java).create().get()
        val state = container.monitoringState
        state.put(live("a", "Nursery"))
        state.listenRequest.value = true
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(setOf("a"), state.listeningCameraIds.value)

        state.put(live("b", "Play room"))
        shadowOf(Looper.getMainLooper()).idle()

        // The switch means "the house", not the rooms that happened to exist
        // when it was flipped.
        assertEquals(setOf("a", "b"), state.listeningCameraIds.value)
    }

    /**
     * A room whose stream is down has no audio to turn up. Claiming it would
     * make the notification overstate what is playing — and an alert from the
     * one room that *is* playing would light the screen to name it, as though
     * there were another it could be mistaken for.
     */
    @Test
    fun `a room whose stream is down is not claimed aloud`() = runTest {
        Robolectric.buildService(MonitoringService::class.java).create().get()
        val state = container.monitoringState
        state.put(live("a", "Nursery"))
        state.put(live("b", "Play room"))
        state.listenRequest.value = true
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(setOf("a", "b"), state.listeningCameraIds.value)

        state.update("b") { it.withConnection(ConnectionState.Reconnecting(1)) }
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(setOf("a"), state.listeningCameraIds.value)
        // The switch stays on: the outage is the network's, not the user's.
        assertTrue(state.listenRequest.value)

        state.update("b") { it.withConnection(ConnectionState.Live) }
        shadowOf(Looper.getMainLooper()).idle()

        // Live off the player's clock alone, nothing decoded yet on the new
        // connection: still not something the speaker can be claimed to play.
        assertEquals(setOf("a"), state.listeningCameraIds.value)

        state.update("b") { it.copy(level = 0.1f) }
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(setOf("a", "b"), state.listeningCameraIds.value)
    }

    /**
     * Losing one room does not stop the others, and does not touch the
     * switch: there is still something behind it.
     */
    @Test
    fun `switching off one room leaves the rest playing`() = runTest {
        container.cameras.upsert(Camera("a", "Nursery", "rtsp://127.0.0.1:1/a"))
        container.cameras.upsert(Camera("b", "Hall", "rtsp://127.0.0.1:1/b"))
        Robolectric.buildService(MonitoringService::class.java).create().get()
        shadowOf(Looper.getMainLooper()).idle()
        val state = container.monitoringState
        state.listenRequest.value = true
        shadowOf(Looper.getMainLooper()).idle()

        container.cameras.setEnabled("a", false)
        shadowOf(Looper.getMainLooper()).idle()

        // Which rooms play follows the monitors on its own (see the tests
        // above); what matters here is that the switch is left alone.
        assertTrue(state.listenRequest.value)
        assertFalse("a" in state.listeningCameraIds.value)
    }

    /**
     * The target flow plays nothing once nothing is monitored, but going quiet
     * is not enough: the viewer's switch would read "on" beside a silent phone
     * — and somebody putting that phone down believing the nursery was still
     * audible is the one mistake this app cannot make.
     */
    @Test
    fun `switching off the last room switches listen mode off too`() = runTest {
        container.cameras.upsert(Camera("a", "Nursery", "rtsp://127.0.0.1:1/a"))
        Robolectric.buildService(MonitoringService::class.java).create().get()
        shadowOf(Looper.getMainLooper()).idle()
        val state = container.monitoringState
        state.listenRequest.value = true
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(state.listenRequest.value)

        container.cameras.setEnabled("a", false)
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse(state.listenRequest.value)
        assertEquals(emptySet<String>(), state.listeningCameraIds.value)
    }

    @Test
    fun `a speaker asked for before the monitors exist is not snatched away`() = runTest {
        container.cameras.upsert(Camera("a", "Nursery", "rtsp://127.0.0.1:1/a"))
        val state = container.monitoringState

        // The viewer offers the switch as soon as the service is running, which
        // is before the first reconcile has resolved anything. "Nothing
        // monitored" means "not yet" here, and must not be read as "gone".
        Robolectric.buildService(MonitoringService::class.java).create().get()
        state.listenRequest.value = true
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(state.listenRequest.value)
    }

    @Test
    fun `stopping takes the speaker with it`() = runTest {
        val controller = Robolectric.buildService(MonitoringService::class.java).create()
        container.monitoringState.listenRequest.value = true
        container.monitoringState.listeningCameraIds.value = setOf("a")

        controller.destroy()

        // A switch left on with no service behind it would offer to stop
        // something already stopped — and would start talking again the moment
        // the monitor came back, which is the one thing it must never do
        // unasked.
        assertFalse(container.monitoringState.listenRequest.value)
        assertEquals(emptySet<String>(), container.monitoringState.listeningCameraIds.value)
    }

    @Test
    fun `a speaker nothing will grant switches itself back off`() = runTest {
        // Something else owns the speaker for the whole of this test.
        val audioManager = context.getSystemService(AudioManager::class.java)
        shadowOf(audioManager).setNextFocusRequestResponse(
            AudioManager.AUDIOFOCUS_REQUEST_FAILED,
        )
        Robolectric.buildService(MonitoringService::class.java).create().get()

        container.monitoringState.listenRequest.value = true
        shadowOf(Looper.getMainLooper()).idle()

        // A control that says "on" next to a phone that is silent is worse
        // than one that visibly did not take.
        assertFalse(container.monitoringState.listenRequest.value)
        assertEquals(emptySet<String>(), container.monitoringState.listeningCameraIds.value)
    }

    @Test
    fun `stopping is left to whoever emptied the camera set`() = runTest {
        container.cameras.upsert(Camera("a", "Nursery", "rtsps://cam:7441/a"))
        container.monitoringState.serviceRunning.value = true

        // The mirror of arming: settings switching off the last listenable
        // camera is what ends monitoring, not the service deciding for itself.
        assertTrue(container.shouldStopMonitoring())
    }

    @Test
    fun `a listenable camera is not a reason to stop`() = runTest {
        container.cameras.upsert(Camera("a", "Nursery", "rtsp://cam:7447/a"))
        container.monitoringState.serviceRunning.value = true

        assertFalse(container.shouldStopMonitoring())
    }

    @Test
    fun `an already-stopped monitor is not stopped again`() = runTest {
        container.monitoringState.serviceRunning.value = false

        assertFalse(container.shouldStopMonitoring())
    }
}

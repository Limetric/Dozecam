package app.dozecam.monitoring

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.dozecam.DozecamApp
import app.dozecam.data.Camera
import kotlinx.coroutines.test.runTest
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

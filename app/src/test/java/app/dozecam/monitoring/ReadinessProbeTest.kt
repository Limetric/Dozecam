package app.dozecam.monitoring

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import android.os.PowerManager
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import app.dozecam.DozecamApp
import app.dozecam.data.Camera
import app.dozecam.player.ConnectionState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The probe only looks; [ReadinessTest] covers what the answers mean. What is
 * worth proving here is that it looks in the right place — a check reading the
 * wrong system state would report a healthy night on a phone that cannot make
 * a sound.
 */
@RunWith(RobolectricTestRunner::class)
class ReadinessProbeTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val container get() = (context.applicationContext as DozecamApp).container

    private val notifications = context.getSystemService(NotificationManager::class.java)

    private fun probe() = ReadinessProbe(
        context = context,
        monitoringState = container.monitoringState,
        appSettings = container.appSettings,
        cameras = container.cameras,
        credentials = container.protectCredentials,
    )

    private suspend fun state(check: ReadinessCheck): ReadinessState =
        probe().findings.first().single { it.check == check }.state

    private suspend fun finding(check: ReadinessCheck): ReadinessFinding =
        probe().findings.first().single { it.check == check }

    @Test
    fun `notifications switched off in Android are noticed`() = runTest {
        shadowOf(notifications).setNotificationsEnabled(false)

        assertEquals(ReadinessState.FAIL, state(ReadinessCheck.NOTIFICATIONS))
    }

    @Test
    fun `an alert channel muted in Android is noticed`() = runTest {
        MonitoringNotifications.ensureChannels(context)
        shadowOf(notifications).setNotificationsEnabled(true)
        notifications.createNotificationChannel(
            NotificationChannel(
                MonitoringNotifications.ALERT_CHANNEL_ID,
                "Sound alerts",
                NotificationManager.IMPORTANCE_NONE,
            ),
        )

        assertEquals(ReadinessState.FAIL, state(ReadinessCheck.ALERT_CHANNEL))
    }

    @Test
    fun `an install where the channel does not exist yet is not blamed for it`() = runTest {
        // The service has never run; the channel is about to be created with
        // the importance we ask for. Nothing to report.
        shadowOf(notifications).setNotificationsEnabled(true)
        notifications.deleteNotificationChannel(MonitoringNotifications.ALERT_CHANNEL_ID)

        assertEquals(ReadinessState.PASS, state(ReadinessCheck.ALERT_CHANNEL))
    }

    @Test
    fun `the alarm stream is what is read, not the ringer`() = runTest {
        // The stored settings are one per process; the check only asks about the
        // alarm stream for a phone whose alert is going to play a sound.
        container.appSettings.update { it.copy(alertChime = true) }
        val audio = context.getSystemService(AudioManager::class.java)
        // A silent ringer is exactly the phone this app is built for; the alert
        // rides alarm usage and is unaffected by it.
        audio.setStreamVolume(AudioManager.STREAM_RING, 0, 0)
        audio.setStreamVolume(AudioManager.STREAM_ALARM, 6, 0)

        assertEquals(ReadinessState.PASS, state(ReadinessCheck.ALARM_VOLUME))

        audio.setStreamVolume(AudioManager.STREAM_ALARM, 0, 0)

        assertEquals(ReadinessState.FAIL, state(ReadinessCheck.ALARM_VOLUME))
    }

    @Test
    fun `total silence is the Do Not Disturb setting that matters`() = runTest {
        notifications.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALARMS)

        assertEquals(ReadinessState.PASS, state(ReadinessCheck.DO_NOT_DISTURB))

        notifications.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)

        assertEquals(ReadinessState.FAIL, state(ReadinessCheck.DO_NOT_DISTURB))
    }

    @Test
    fun `priority mode is not reported as a failure we cannot prove`() = runTest {
        // The policy that would say whether alarms get through is readable only
        // with notification-policy access, which Dozecam has no business
        // holding. A check that cried wolf at every DND schedule is the check
        // people learn to ignore.
        notifications.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)

        assertEquals(ReadinessState.PASS, state(ReadinessCheck.DO_NOT_DISTURB))
    }

    @Test
    fun `an exempt app is not warned about battery optimisation`() = runTest {
        shadowOf(context.getSystemService(PowerManager::class.java))
            .setIgnoringBatteryOptimizations(context.packageName, true)

        assertEquals(ReadinessState.PASS, state(ReadinessCheck.BATTERY_OPTIMISATION))
    }

    @Test
    fun `an optimised app is warned about it`() = runTest {
        shadowOf(context.getSystemService(PowerManager::class.java))
            .setIgnoringBatteryOptimizations(context.packageName, false)

        assertEquals(ReadinessState.WARN, state(ReadinessCheck.BATTERY_OPTIMISATION))
    }

    @Test
    fun `an enabled camera the monitor has no entry for is not being heard`() = runTest {
        // Not monitorable at all, or not built yet — neither is a room anyone
        // is listening to, and neither must be quietly left off the list.
        container.cameras.upsert(Camera("a", "Nursery", "rtsp://cam:7447/a"))
        container.monitoringState.serviceRunning.value = true
        container.monitoringState.clear()

        assertEquals(
            listOf("Nursery"),
            finding(ReadinessCheck.CAMERAS_HEARD).cameras.map { it.name },
        )
    }

    @Test
    fun `a camera the monitor is decoding right now is being heard`() = runTest {
        container.cameras.upsert(Camera("a", "Nursery", "rtsp://cam:7447/a"))
        container.monitoringState.serviceRunning.value = true
        container.monitoringState.put(
            CameraMonitorState(
                cameraId = "a",
                name = "Nursery",
                level = 0f,
                lastAudioAtMs = SystemClock.elapsedRealtime(),
                connection = ConnectionState.Live,
            ),
        )

        assertEquals(ReadinessState.PASS, state(ReadinessCheck.CAMERAS_HEARD))
    }

    @Test
    fun `the app's own alert settings are read from the same store the alert uses`() = runTest {
        container.appSettings.update { it.copy(alertsEnabled = false) }

        assertEquals(ReadinessState.FAIL, state(ReadinessCheck.ALERTS_ON))

        container.appSettings.update { it.copy(alertsEnabled = true) }

        assertEquals(ReadinessState.PASS, state(ReadinessCheck.ALERTS_ON))
    }

    /**
     * The failure that looks most like health: the channel is on, the alert is
     * posted, and Android will not let it light a screen because someone
     * lowered its importance.
     */
    @Test
    fun `an alert channel turned down in Android is noticed`() = runTest {
        MonitoringNotifications.ensureChannels(context)
        shadowOf(notifications).setNotificationsEnabled(true)
        notifications.createNotificationChannel(
            NotificationChannel(
                MonitoringNotifications.ALERT_CHANNEL_ID,
                "Sound alerts",
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )

        assertEquals(ReadinessState.PASS, state(ReadinessCheck.ALERT_CHANNEL))
        assertEquals(ReadinessState.FAIL, state(ReadinessCheck.ALERT_CHANNEL_PRIORITY))
    }

    @Test
    fun `the channel Dozecam creates for itself wakes screens`() = runTest {
        MonitoringNotifications.ensureChannels(context)
        shadowOf(notifications).setNotificationsEnabled(true)

        assertEquals(ReadinessState.PASS, state(ReadinessCheck.ALERT_CHANNEL_PRIORITY))
    }
    /**
     * Media3 has no RTSP TLS, so an rtsps camera with no Protect livestream
     * behind it cannot be listened to at all — and the card must not offer to
     * start a monitor the arming gate would refuse.
     */
    @Test
    fun `a camera with no way in at all is reported as unmonitorable`() = runTest {
        container.cameras.upsert(Camera("a", "Nursery", "rtsps://cam:7441/token"))
        container.monitoringState.serviceRunning.value = false

        val monitoring = finding(ReadinessCheck.MONITORING)
        assertEquals(ReadinessRemedy.NONE, monitoring.remedy)

        val cameras = finding(ReadinessCheck.CAMERAS_HEARD)
        assertEquals(ReadinessState.FAIL, cameras.state)
        assertEquals(ReadinessRemedy.CAMERA_SETTINGS, cameras.remedy)
    }

    @Test
    fun `a plain RTSP camera is something to start the monitor for`() = runTest {
        container.cameras.upsert(Camera("a", "Nursery", "rtsp://cam:7447/a"))
        container.monitoringState.serviceRunning.value = false

        assertEquals(
            ReadinessRemedy.START_MONITORING,
            finding(ReadinessCheck.MONITORING).remedy,
        )
    }
}

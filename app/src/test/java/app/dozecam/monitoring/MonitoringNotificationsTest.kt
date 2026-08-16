package app.dozecam.monitoring

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.dozecam.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class MonitoringNotificationsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `ensureChannels creates the status and alert channels`() {
        MonitoringNotifications.ensureChannels(context)

        val manager = context.getSystemService(NotificationManager::class.java)
        assertEquals(
            NotificationManager.IMPORTANCE_LOW,
            manager.getNotificationChannel(MonitoringNotifications.STATUS_CHANNEL_ID).importance,
        )
        assertEquals(
            NotificationManager.IMPORTANCE_HIGH,
            manager.getNotificationChannel(MonitoringNotifications.ALERT_CHANNEL_ID).importance,
        )
    }

    @Test
    fun `alert notification carries a full-screen intent`() {
        MonitoringNotifications.ensureChannels(context)

        val notification = MonitoringNotifications.alertNotification(context, "a", "Nursery")

        assertNotNull(notification.fullScreenIntent)
        // Tap path for the heads-up fallback when full-screen is suppressed.
        assertNotNull(notification.contentIntent)
    }

    @Test
    fun `the alert wakes the viewer onto the camera that got loud`() {
        MonitoringNotifications.ensureChannels(context)

        val notification = MonitoringNotifications.alertNotification(context, "cam-a", "Nursery")
        val intent = shadowOf(notification.fullScreenIntent).savedIntent

        assertEquals(
            MainActivity::class.java.name,
            intent.component?.className,
        )
        assertEquals("cam-a", intent.getStringExtra("alert_camera_id"))
    }

    @Test
    fun `the alert names the camera so a two-camera house knows which room`() {
        MonitoringNotifications.ensureChannels(context)

        val notification = MonitoringNotifications.alertNotification(context, "cam-a", "Nursery")

        assertEquals(
            "Sound detected — Nursery",
            notification.extras.getString(android.app.Notification.EXTRA_TITLE),
        )
    }

    /**
     * The tap and the unattended wake must be separate PendingIntents, or the
     * tap could not be told apart from Android launching the viewer on its own.
     * They differ by request code as well as extras, because PendingIntent
     * equality ignores extras entirely.
     */
    @Test
    fun `tapping the alert is distinguishable from the screen being woken by it`() {
        MonitoringNotifications.ensureChannels(context)

        val notification = MonitoringNotifications.alertNotification(context, "cam-a", "Nursery")
        val tapped = shadowOf(notification.contentIntent).savedIntent
        val woken = shadowOf(notification.fullScreenIntent).savedIntent

        assertNotNull("the tap must carry its own key", tapped.getStringExtra("alert_tap_key"))
        assertNull("the unattended wake must not", woken.getStringExtra("alert_tap_key"))
        assertEquals("cam-a", tapped.getStringExtra("alert_camera_id"))
    }

    /**
     * The dismiss half of the acknowledgement: without this the alarm would keep
     * ringing with nothing on screen left to explain why.
     */
    @Test
    fun `dismissing the alert reaches the receiver that silences the alarm`() {
        MonitoringNotifications.ensureChannels(context)

        val notification = MonitoringNotifications.alertNotification(context, "cam-a", "Nursery")
        val intent = shadowOf(notification.deleteIntent).savedIntent

        assertEquals(
            AlertDismissReceiver::class.java.name,
            intent.component?.className,
        )
    }

    /**
     * The channel stays silent so the in-app alarm is the single audible
     * surface — and channel settings are immutable once created, so a sound set
     * here could never be changed again.
     */
    @Test
    fun `the alert channel makes no noise of its own`() {
        MonitoringNotifications.ensureChannels(context)

        val channel = context.getSystemService(NotificationManager::class.java)
            .getNotificationChannel(MonitoringNotifications.ALERT_CHANNEL_ID)

        assertEquals(null, channel.sound)
        assertEquals(false, channel.shouldVibrate())
    }

    @Test
    fun `status notification is ongoing`() {
        MonitoringNotifications.ensureChannels(context)

        val notification = MonitoringNotifications.statusNotification(context, "Listening")

        assertTrue(notification.flags and android.app.Notification.FLAG_ONGOING_EVENT != 0)
    }

    /**
     * With the screen off the ongoing notification is the only Dozecam there
     * is, so a tap on it has to lead back to the cameras rather than nowhere.
     */
    @Test
    fun `tapping the ongoing notification opens the viewer`() {
        MonitoringNotifications.ensureChannels(context)

        val notification = MonitoringNotifications.statusNotification(context, "Listening")
        val intent = shadowOf(notification.contentIntent).savedIntent

        assertEquals(MainActivity::class.java.name, intent.component?.className)
    }

    /**
     * It sits in the shade all night, which is exactly why it must not be able
     * to do what an alert can: no camera to open, and neither of the secrets
     * that authorise the viewer to appear over the keyguard.
     */
    @Test
    fun `the ongoing notification cannot wake the screen`() {
        MonitoringNotifications.ensureChannels(context)

        val notification = MonitoringNotifications.statusNotification(context, "Listening")
        val intent = shadowOf(notification.contentIntent).savedIntent

        assertNull(intent.getStringExtra("alert_camera_id"))
        assertNull(intent.getStringExtra("alert_token"))
        assertNull(intent.getStringExtra("alert_tap_key"))
    }

    @Test
    fun `the ongoing notification offers a way to stop monitoring`() {
        MonitoringNotifications.ensureChannels(context)

        val notification = MonitoringNotifications.statusNotification(context, "Listening")
        val action = notification.actions.single()

        assertEquals("Stop monitoring", action.title)
        assertEquals(
            StopMonitoringReceiver::class.java.name,
            shadowOf(action.actionIntent).savedIntent.component?.className,
        )
    }
}

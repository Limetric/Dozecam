package app.dozecam.monitoring

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.dozecam.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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

    @Test
    fun `status notification is ongoing`() {
        MonitoringNotifications.ensureChannels(context)

        val notification = MonitoringNotifications.statusNotification(context, "Listening")

        assertTrue(notification.flags and android.app.Notification.FLAG_ONGOING_EVENT != 0)
    }
}

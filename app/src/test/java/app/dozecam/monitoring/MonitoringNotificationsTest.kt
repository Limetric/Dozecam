package app.dozecam.monitoring

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.dozecam.MainActivity
import app.dozecam.R
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

    private val lostNursery = MonitoringFailure(
        FailureReason.CameraUnreachable("a", "Nursery", networkDown = false),
        sinceMs = 0L,
    )

    /**
     * The failure card is its own notification, beside the sound alert's
     * rather than in its place, and it wakes the viewer with the same urgency
     * — under words that could never be read as a room getting loud.
     */
    @Test
    fun `announcing a failure wakes the viewer and names what is wrong`() {
        MonitoringNotifications.ensureChannels(context)

        val notification = MonitoringNotifications.failureNotification(
            context,
            listOf(lostNursery, MonitoringFailure(FailureReason.LowBattery(22), 0L)),
            wakeScreen = true,
        )

        assertNotNull(notification.fullScreenIntent)
        assertNotNull(notification.contentIntent)
        assertEquals(
            "Can't reach Nursery · Battery low — 22%",
            notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString(),
        )
        assertTrue(MonitoringNotifications.FAILURE_NOTIFICATION_ID != MonitoringNotifications.ALERT_NOTIFICATION_ID)
        // Swiping it away acknowledges the alarm, as with a sound alert.
        assertNotNull(notification.deleteIntent)
    }

    /** A refresh of the card, as failures join or clear, must not re-light the bedroom. */
    @Test
    fun `refreshing the failure card does not wake the screen again`() {
        MonitoringNotifications.ensureChannels(context)

        val notification = MonitoringNotifications
            .failureNotification(context, listOf(lostNursery), wakeScreen = false)

        assertNull(notification.fullScreenIntent)
        assertNotNull(notification.contentIntent)
    }

    /** The unplugged notice is the mild one: the quiet channel, nothing lit. */
    @Test
    fun `the unplugged notice is quiet and says where the battery stands`() {
        MonitoringNotifications.ensureChannels(context)

        val notification = MonitoringNotifications.unpluggedNotification(context, 64)

        assertEquals(MonitoringNotifications.STATUS_CHANNEL_ID, notification.channelId)
        assertNull(notification.fullScreenIntent)
        assertTrue(
            notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains("64%"),
        )
    }

    @Test
    fun `a room already coming out of the speaker is not also lit up`() {
        MonitoringNotifications.ensureChannels(context)

        val notification = MonitoringNotifications
            .alertNotification(context, "a", "Nursery", wakeScreen = false)

        // Whoever switched listen mode on is already being told about this
        // room, continuously and in the most direct way there is.
        assertNull(notification.fullScreenIntent)
        // Everything else about the alert stands: the chime and vibration are
        // the signaller's, and the tap still opens the camera.
        assertNotNull(notification.contentIntent)
    }

    @Test
    fun `the status notification offers to stop a speaker it admits to`() {
        MonitoringNotifications.ensureChannels(context)

        val silent = MonitoringNotifications.statusNotification(context, "Listening")
        val aloud = MonitoringNotifications.statusNotification(context, "Listening", aloud = true)

        // Exit, and nothing else, while nothing is playing aloud.
        assertEquals(1, silent.actions.size)
        // A phone broadcasting a bedroom needs its off switch on the same
        // surface that admits to it — and separately from the one that would
        // end the baby monitor altogether.
        assertEquals(2, aloud.actions.size)
        assertEquals("Exit", aloud.actions[0].title)
        assertEquals("Stop playing aloud", aloud.actions[1].title)
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

    /**
     * Proof of life for the healthy listening state: the level bar and the
     * checked-at stamp only exist because the app really posted moments ago,
     * which is what makes a quiet night distinguishable from a stale shade.
     */
    @Test
    fun `while listening the ongoing notification carries a level bar and a checked-at stamp`() {
        MonitoringNotifications.ensureChannels(context)
        val checkedAtMs = 8 * 60_000L

        val notification = MonitoringNotifications.statusNotification(
            context,
            "Listening",
            levelBucket = 4,
            checkedAtMs = checkedAtMs,
        )

        assertEquals(
            StatusHeartbeat.LEVEL_BUCKETS,
            notification.extras.getInt(android.app.Notification.EXTRA_PROGRESS_MAX),
        )
        assertEquals(4, notification.extras.getInt(android.app.Notification.EXTRA_PROGRESS))
        val expectedTime = android.text.format.DateFormat.getTimeFormat(context)
            .format(java.util.Date(checkedAtMs))
        assertEquals(
            "Checked $expectedTime",
            notification.extras.getCharSequence(android.app.Notification.EXTRA_SUB_TEXT)
                ?.toString(),
        )
    }

    /** Unhealthy states keep their plain, honest text — no borrowed reassurance. */
    @Test
    fun `without a level the ongoing notification stays plain`() {
        MonitoringNotifications.ensureChannels(context)

        val notification = MonitoringNotifications.statusNotification(context, "Offline")

        assertEquals(0, notification.extras.getInt(android.app.Notification.EXTRA_PROGRESS_MAX))
        assertNull(notification.extras.getCharSequence(android.app.Notification.EXTRA_SUB_TEXT))
    }

    @Test
    fun `the ongoing notification offers the way out`() {
        MonitoringNotifications.ensureChannels(context)

        val notification = MonitoringNotifications.statusNotification(context, "Listening")
        val action = notification.actions.single()

        // Monitoring has no switch of its own: it runs for as long as the app
        // does, so the way to end it from the shade is to end the app.
        assertEquals("Exit", action.title)
        assertEquals(
            ExitReceiver::class.java.name,
            shadowOf(action.actionIntent).savedIntent.component?.className,
        )
    }

    /**
     * The test alert takes exactly the path a real one takes — same channel,
     * same priority, same full-screen intent — because a test down an easier
     * road would prove nothing about the road that matters.
     */
    @Test
    fun `a test alert is delivered exactly like a real one`() {
        MonitoringNotifications.ensureChannels(context)

        val test = MonitoringNotifications
            .alertNotification(context, MonitoringService.TEST_CAMERA_ID, "", test = true)
        val real = MonitoringNotifications.alertNotification(context, "a", "Nursery")

        assertNotNull(test.fullScreenIntent)
        assertNotNull(test.contentIntent)
        assertEquals(real.channelId, test.channelId)
        assertEquals(real.priority, test.priority)
        assertEquals(real.category, test.category)
    }

    /** And says so, in the first place anyone would read it. */
    @Test
    fun `a test alert says it is a test rather than naming a room`() {
        MonitoringNotifications.ensureChannels(context)

        val notification = MonitoringNotifications
            .alertNotification(context, MonitoringService.TEST_CAMERA_ID, "Nursery", test = true)

        val title = notification.extras.getString("android.title")
        assertEquals(context.getString(R.string.notification_test_alert_title), title)
        assertTrue("a test must not name a room", title?.contains("Nursery") != true)
    }
}

package app.dozecam

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import app.dozecam.data.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * MainActivity is the launcher, so it is exported, and it is also the wake
 * target. Those two facts together are why the lock-screen path is gated on a
 * token rather than declared in the manifest.
 */
@RunWith(RobolectricTestRunner::class)
class MainActivityAlertIntentTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** The alarm's own noise is beside the point here; only the latch is. */
    private val silentAlarmSettings =
        AppSettings(alertChime = false, alertVibrate = false)

    @Test
    fun `our own alert may wake the screen over the lock screen`() {
        val intent = MainActivity.alertIntent(context, "cam-a")

        val activity = Robolectric.buildActivity(MainActivity::class.java, intent).create().get()

        val shadow = shadowOf(activity)
        assertTrue(shadow.getShowWhenLocked())
        assertTrue(shadow.getTurnScreenOn())
    }

    @Test
    fun `another app cannot put the live nursery on the lock screen`() {
        // Everything a hostile caller could know: the component and the extra
        // name. What it cannot know is this process's token.
        val forged = Intent(context, MainActivity::class.java)
            .putExtra("alert_camera_id", "cam-a")
            .putExtra("alert_token", "guessed")

        val activity = Robolectric.buildActivity(MainActivity::class.java, forged).create().get()

        val shadow = shadowOf(activity)
        assertFalse(shadow.getShowWhenLocked())
        assertFalse(shadow.getTurnScreenOn())
    }

    @Test
    fun `a plain launch does not wake anything`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).create().get()

        val shadow = shadowOf(activity)
        assertFalse(shadow.getShowWhenLocked())
        assertFalse(shadow.getTurnScreenOn())
    }

    @Test
    fun `the alert intent names the camera and reuses the running task`() {
        val intent = MainActivity.alertIntent(context, "cam-a")

        assertEquals("cam-a", intent.getStringExtra("alert_camera_id"))
        assertNotNull(intent.getStringExtra("alert_token"))
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    fun `an alert token cannot be replayed`() {
        val intent = MainActivity.alertIntent(context, "cam-a")
        Robolectric.buildActivity(MainActivity::class.java, intent).create()

        // The same intent again — a second tap on the notification, or the
        // activity being recreated with its original launch intent.
        val replayed = Robolectric.buildActivity(MainActivity::class.java, intent).create().get()

        val shadow = shadowOf(replayed)
        assertFalse(shadow.getShowWhenLocked())
        assertFalse(shadow.getTurnScreenOn())
    }

    @Test
    fun `a spent alert still opens the camera it names`() {
        val intent = MainActivity.alertIntent(context, "cam-a")
        Robolectric.buildActivity(MainActivity::class.java, intent).create()

        val replayed = Robolectric.buildActivity(MainActivity::class.java, intent).create().get()

        // Only waking is gated; showing a camera to someone already past the
        // lock screen is not the risk being defended against.
        assertEquals("cam-a", replayed.intent.getStringExtra("alert_camera_id"))
    }

    /**
     * The other half of the alert: the viewer appearing cannot be the
     * acknowledgement, because our own full-screen intent is what put it there.
     */
    @Test
    fun `a touch on the woken viewer silences the alarm`() {
        val signaler = context.appContainer.alertSignaler
        signaler.signal("cam-a", silentAlarmSettings)
        val intent = MainActivity.alertIntent(context, "cam-a")
        val activity = Robolectric.buildActivity(MainActivity::class.java, intent)
            .create().start().resume().get()

        assertTrue("the alarm should survive the screen coming on", signaler.isAlarming)

        activity.onUserInteraction()

        assertFalse(signaler.isAlarming)
    }

    @Test
    fun `the viewer coming up on its own does not silence the alarm`() {
        val signaler = context.appContainer.alertSignaler
        signaler.signal("cam-a", silentAlarmSettings)
        val intent = MainActivity.alertIntent(context, "cam-a")

        Robolectric.buildActivity(MainActivity::class.java, intent).create().start().resume()

        // A screen nobody has their eyes open for is not an acknowledgement.
        assertTrue(signaler.isAlarming)
        signaler.stop()
    }

    @Test
    fun `ordinary use of the viewer costs nothing`() {
        val signaler = context.appContainer.alertSignaler
        val activity = Robolectric.buildActivity(MainActivity::class.java)
            .create().start().resume().get()

        activity.onUserInteraction()

        assertFalse(signaler.isAlarming)
    }

    @Test
    fun `the wake privilege does not outlive the alert`() {
        val intent = MainActivity.alertIntent(context, "cam-a")
        val controller = Robolectric.buildActivity(MainActivity::class.java, intent).create()

        controller.start().resume().pause()

        // Otherwise locking the phone later would put the nursery back on the
        // keyguard for whoever picks it up next.
        val shadow = shadowOf(controller.get())
        assertFalse(shadow.getShowWhenLocked())
        assertFalse(shadow.getTurnScreenOn())
    }
}

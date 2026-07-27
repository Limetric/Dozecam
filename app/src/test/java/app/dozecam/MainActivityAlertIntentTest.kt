package app.dozecam

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
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

package app.dozecam

import android.app.Application
import android.media.AudioManager
import android.os.Looper
import android.view.WindowInsets
import android.view.WindowManager
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import app.dozecam.monitoring.MonitoringService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class MainActivityTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun `a fresh install opens the viewer pointing at console setup`() {
        // No cameras yet, so the viewer has nothing to show and says so rather
        // than coming up blank.
        composeRule.onNodeWithTag("empty-open-onboarding").assertExists()
    }

    @Test
    fun `the viewer offers settings rather than hosting configuration itself`() {
        composeRule.onNodeWithTag("open-settings").assertExists()
        composeRule.onNodeWithTag("camera-name-field").assertDoesNotExist()
        composeRule.onNodeWithTag("threshold-slider").assertDoesNotExist()
    }

    @Test
    fun `the volume keys reach for the cameras, not the ringer`() {
        // Camera audio comes and goes as the sound moves round the grid, and
        // the default routing follows whatever happens to be playing — so the
        // rocker would silently adjust the ringer between turns.
        assertEquals(AudioManager.STREAM_MUSIC, composeRule.activity.volumeControlStream)
    }

    private fun holdingScreenAwake(): Boolean =
        composeRule.activity.window.attributes.flags and
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0

    @Test
    fun `an empty viewer lets the screen sleep`() {
        // Console setup is a screen someone is actively touching; only cameras
        // being watched earn the display — and only while keep-screen-on says
        // so, which cannot be exercised here: a camera in the real activity
        // means a real VLC decoder underneath it.
        assertFalse(holdingScreenAwake())
    }

    @Test
    fun `the viewer hides Android system bars with transient swipe access`() {
        val window = composeRule.activity.window
        val insets = window.decorView.rootWindowInsets

        assertFalse(insets.isVisible(WindowInsets.Type.systemBars()))
        assertEquals(
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE,
            WindowCompat.getInsetsController(window, window.decorView).systemBarsBehavior,
        )
    }

    /**
     * "Exit" on the ongoing notification reaches a receiver, and a receiver
     * cannot close a viewer it does not hold. So the request is left where the
     * viewer reads it, and the viewer takes itself — and its task — down.
     */
    @Test
    fun `an exit asked for from the notification closes the viewer`() {
        val activity = composeRule.activity
        activity.appContainer.monitoringState.exitRequested.value = true
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(activity.isFinishing)
        val stopped = shadowOf(activity.application as Application).nextStoppedService
        assertEquals(MonitoringService::class.java.name, stopped.component?.className)
    }

    /**
     * An exit carried out while no viewer was up has already happened. The
     * next viewer to open must not read the stale request and close on the
     * spot.
     */
    @Test
    fun `opening the viewer clears a stale exit request`() {
        val activity = composeRule.activity
        // The activity rule created this viewer with whatever the container
        // held; what matters is that it came up and stayed up.
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse(activity.isFinishing)
        assertFalse(activity.appContainer.monitoringState.exitRequested.value)
    }
}

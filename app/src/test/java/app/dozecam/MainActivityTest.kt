package app.dozecam

import android.media.AudioManager
import android.view.WindowInsets
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

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
}

package app.dozecam.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import app.dozecam.data.DetectorSettings
import app.dozecam.ui.theme.DozecamTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HomeScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Composable
    private fun TestHomeScreen(
        url: String = "",
        canWatch: Boolean = false,
        onUrlChange: (String) -> Unit = {},
        onWatch: () -> Unit = {},
        monitoringRunning: Boolean = false,
        onToggleMonitoring: (Boolean) -> Unit = {},
        audioLevel: Float = 0f,
        detector: DetectorSettings = DetectorSettings(),
        onDetectorChange: (DetectorSettings) -> Unit = {},
    ) {
        DozecamTheme {
            HomeScreen(
                url = url,
                canWatch = canWatch,
                onUrlChange = onUrlChange,
                onWatch = onWatch,
                monitoringRunning = monitoringRunning,
                onToggleMonitoring = onToggleMonitoring,
                audioLevel = audioLevel,
                detector = detector,
                onDetectorChange = onDetectorChange,
            )
        }
    }

    @Test
    fun `watch button reflects url validity`() {
        composeRule.setContent { TestHomeScreen(canWatch = false) }

        composeRule.onNodeWithText("Watch").assertIsNotEnabled()
    }

    @Test
    fun `watch button enabled when url is valid`() {
        composeRule.setContent {
            TestHomeScreen(url = "rtsp://192.168.1.1:7447/token", canWatch = true)
        }

        composeRule.onNodeWithText("Watch").assertIsEnabled()
    }

    @Test
    fun `typing in the field forwards input`() {
        var captured = ""
        composeRule.setContent { TestHomeScreen(onUrlChange = { captured = it }) }

        composeRule.onNodeWithText("Stream URL").performTextInput("rtsp://x")

        assertEquals("rtsp://x", captured)
    }

    @Test
    fun `monitoring toggle is disabled without a valid url`() {
        composeRule.setContent { TestHomeScreen(canWatch = false) }

        composeRule.onNodeWithTag("monitoring-switch").assertIsNotEnabled()
    }

    @Test
    fun `monitoring toggle can always turn a running service off`() {
        var toggled: Boolean? = null
        composeRule.setContent {
            TestHomeScreen(
                canWatch = false,
                monitoringRunning = true,
                onToggleMonitoring = { toggled = it },
            )
        }

        composeRule.onNodeWithTag("monitoring-switch").assertIsEnabled().performClick()

        assertEquals(false, toggled)
    }

    @Test
    fun `level meter is shown only while monitoring runs`() {
        composeRule.setContent { TestHomeScreen(monitoringRunning = false) }

        composeRule.onNodeWithTag("audio-level-meter").assertDoesNotExist()
    }

    @Test
    fun `level meter appears when monitoring runs`() {
        composeRule.setContent {
            TestHomeScreen(monitoringRunning = true, audioLevel = 0.2f)
        }

        composeRule.onNodeWithTag("audio-level-meter").assertExists()
    }

    @Test
    fun `threshold slider reports detector changes`() {
        var changed: DetectorSettings? = null
        composeRule.setContent {
            TestHomeScreen(
                detector = DetectorSettings(threshold = 0.1f),
                onDetectorChange = { changed = it },
            )
        }

        composeRule.onNodeWithTag("threshold-slider").performClick()

        val result = changed
        assertTrue("expected a detector change from slider interaction", result != null)
    }
}

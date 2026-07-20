package app.dozecam.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import app.dozecam.data.Camera
import app.dozecam.data.DetectorSettings
import app.dozecam.ui.theme.DozecamTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HomeScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val nursery = Camera("a", "Nursery", "rtsp://cam:7447/a")

    @Composable
    private fun TestHomeScreen(
        cameras: List<Camera> = emptyList(),
        selectedCameraId: String? = null,
        form: CameraFormState = CameraFormState(),
        onFormName: (String) -> Unit = {},
        onFormUrl: (String) -> Unit = {},
        onFormSave: () -> Unit = {},
        onSelect: (String) -> Unit = {},
        onWatch: (Camera) -> Unit = {},
        onEdit: (Camera) -> Unit = {},
        onDelete: (String) -> Unit = {},
        monitoringRunning: Boolean = false,
        canMonitor: Boolean = false,
        onToggleMonitoring: (Boolean) -> Unit = {},
        audioLevel: Float = 0f,
        detector: DetectorSettings = DetectorSettings(),
        onDetectorChange: ((DetectorSettings) -> DetectorSettings) -> Unit = {},
        onOpenSettings: () -> Unit = {},
    ) {
        DozecamTheme {
            HomeScreen(
                cameras = cameras,
                selectedCameraId = selectedCameraId,
                form = form,
                onFormName = onFormName,
                onFormUrl = onFormUrl,
                onFormSave = onFormSave,
                onFormCancel = {},
                onSelect = onSelect,
                onWatch = onWatch,
                onEdit = onEdit,
                onDelete = onDelete,
                monitoringRunning = monitoringRunning,
                canMonitor = canMonitor,
                onToggleMonitoring = onToggleMonitoring,
                audioLevel = audioLevel,
                detector = detector,
                onDetectorChange = onDetectorChange,
                onOpenSettings = onOpenSettings,
                onOpenOnboarding = {},
            )
        }
    }

    @Test
    fun `tapping a camera row watches it`() {
        var watched: Camera? = null
        composeRule.setContent {
            TestHomeScreen(cameras = listOf(nursery), onWatch = { watched = it })
        }

        composeRule.onNodeWithTag("camera-row-Nursery").performClick()

        assertEquals(nursery, watched)
    }

    @Test
    fun `selecting a camera reports its id`() {
        var selected: String? = null
        composeRule.setContent {
            TestHomeScreen(cameras = listOf(nursery), onSelect = { selected = it })
        }

        composeRule.onNodeWithTag("camera-select-Nursery").performClick()

        assertEquals("a", selected)
    }

    @Test
    fun `deleting a camera reports its id`() {
        var deleted: String? = null
        composeRule.setContent {
            TestHomeScreen(cameras = listOf(nursery), onDelete = { deleted = it })
        }

        composeRule.onNodeWithTag("camera-delete-Nursery").performClick()

        assertEquals("a", deleted)
    }

    @Test
    fun `form save is gated on validity`() {
        composeRule.setContent {
            TestHomeScreen(form = CameraFormState(name = "Nursery", url = "http://nope"))
        }

        composeRule.onNodeWithTag("camera-save").assertIsNotEnabled()
    }

    @Test
    fun `valid form saves`() {
        var saved = false
        composeRule.setContent {
            TestHomeScreen(
                form = CameraFormState(name = "Nursery", url = "rtsp://cam:7447/a"),
                onFormSave = { saved = true },
            )
        }

        composeRule.onNodeWithTag("camera-save").assertIsEnabled().performClick()

        assertEquals(true, saved)
    }

    @Test
    fun `typing in the url field forwards input`() {
        var captured = ""
        composeRule.setContent { TestHomeScreen(onFormUrl = { captured = it }) }

        composeRule.onNodeWithTag("camera-url-field").performTextInput("rtsp://x")

        assertEquals("rtsp://x", captured)
    }

    @Test
    fun `monitoring toggle disabled without a monitorable camera`() {
        composeRule.setContent { TestHomeScreen(canMonitor = false) }

        composeRule.onNodeWithTag("monitoring-switch").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun `monitoring toggle reports off for a running service`() {
        var toggled: Boolean? = null
        composeRule.setContent {
            TestHomeScreen(
                monitoringRunning = true,
                canMonitor = true,
                onToggleMonitoring = { toggled = it },
            )
        }

        composeRule.onNodeWithTag("monitoring-switch").performScrollTo().performClick()

        assertEquals(false, toggled)
    }

    @Test
    fun `level meter appears only while monitoring runs`() {
        composeRule.setContent { TestHomeScreen(monitoringRunning = false) }

        composeRule.onNodeWithTag("audio-level-meter").assertDoesNotExist()
    }

    @Test
    fun `settings button opens settings`() {
        var opened = false
        composeRule.setContent { TestHomeScreen(onOpenSettings = { opened = true }) }

        composeRule.onNodeWithTag("open-settings").performClick()

        assertEquals(true, opened)
    }

    @Test
    fun `threshold slider reports detector changes`() {
        var changed: DetectorSettings? = null
        composeRule.setContent {
            TestHomeScreen(
                detector = DetectorSettings(threshold = 0.1f),
                onDetectorChange = { changed = it(DetectorSettings(threshold = 0.1f)) },
            )
        }

        composeRule.onNodeWithTag("threshold-slider").performScrollTo().performClick()

        assertEquals(true, changed != null)
    }
}

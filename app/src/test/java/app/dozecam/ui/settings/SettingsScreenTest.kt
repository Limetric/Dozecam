package app.dozecam.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import app.dozecam.data.AppSettings
import app.dozecam.data.Camera
import app.dozecam.data.DetectorSettings
import app.dozecam.data.OrientationLock
import app.dozecam.ui.theme.DozecamTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SettingsScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val nursery = Camera("a", "Nursery", "rtsp://cam:7447/a")
    private val playroom = Camera("b", "Play room", "rtsp://cam:7447/b", enabled = false)

    /**
     * Every callback defaults to a no-op so each test names only the one it is
     * about; the rest still have to be supplied for the screen to compose.
     */
    @Composable
    private fun Screen(
        settings: AppSettings = AppSettings(),
        onSettingsChange: ((AppSettings) -> AppSettings) -> Unit = {},
        monitoringRunning: Boolean = false,
        canMonitor: Boolean = true,
        onToggleMonitoring: (Boolean) -> Unit = {},
        audioLevel: Float = 0f,
        cameras: List<Camera> = listOf(nursery, playroom),
        onCameraEnabled: (String, Boolean) -> Unit = { _, _ -> },
        onEditCamera: (Camera) -> Unit = {},
        onDeleteCamera: (String) -> Unit = {},
        form: CameraFormState = CameraFormState(),
        onFormName: (String) -> Unit = {},
        onFormUrl: (String) -> Unit = {},
        onFormSave: () -> Unit = {},
        onFormCancel: () -> Unit = {},
        detector: DetectorSettings = DetectorSettings(),
        onDetectorChange: ((DetectorSettings) -> DetectorSettings) -> Unit = {},
        onOpenOnboarding: () -> Unit = {},
        onBack: () -> Unit = {},
    ) {
        DozecamTheme {
            SettingsScreen(
                settings = settings,
                onSettingsChange = onSettingsChange,
                monitoringRunning = monitoringRunning,
                canMonitor = canMonitor,
                onToggleMonitoring = onToggleMonitoring,
                audioLevel = audioLevel,
                cameras = cameras,
                onCameraEnabled = onCameraEnabled,
                onEditCamera = onEditCamera,
                onDeleteCamera = onDeleteCamera,
                form = form,
                onFormName = onFormName,
                onFormUrl = onFormUrl,
                onFormSave = onFormSave,
                onFormCancel = onFormCancel,
                detector = detector,
                onDetectorChange = onDetectorChange,
                onOpenOnboarding = onOpenOnboarding,
                onBack = onBack,
            )
        }
    }

    @Test
    fun `toggling night theme reports the change`() {
        var changed: AppSettings? = null
        composeRule.setContent {
            Screen(
                settings = AppSettings(nightTheme = false),
                onSettingsChange = { changed = it(AppSettings(nightTheme = false)) },
            )
        }

        composeRule.onNodeWithTag("night-theme-switch").performScrollTo().performClick()

        assertEquals(true, changed?.nightTheme)
    }

    @Test
    fun `choosing an orientation lock reports the change`() {
        var changed: AppSettings? = null
        composeRule.setContent {
            Screen(onSettingsChange = { changed = it(AppSettings()) })
        }

        composeRule.onNodeWithTag("orientation-LANDSCAPE").performScrollTo().performClick()

        assertEquals(OrientationLock.LANDSCAPE, changed?.orientationLock)
    }

    @Test
    fun `disabling the chime reports the change`() {
        var changed: AppSettings? = null
        composeRule.setContent {
            Screen(
                settings = AppSettings(alertChime = true),
                onSettingsChange = { changed = it(AppSettings(alertChime = true)) },
            )
        }

        composeRule.onNodeWithTag("chime-switch").performScrollTo().performClick()

        assertEquals(false, changed?.alertChime)
    }

    @Test
    fun `switching a camera off reports it by id`() {
        var call: Pair<String, Boolean>? = null
        composeRule.setContent { Screen(onCameraEnabled = { id, on -> call = id to on }) }

        composeRule.onNodeWithTag("camera-enabled-Nursery").performScrollTo().performClick()

        assertEquals("a" to false, call)
    }

    @Test
    fun `switching a disabled camera back on reports it`() {
        var call: Pair<String, Boolean>? = null
        composeRule.setContent { Screen(onCameraEnabled = { id, on -> call = id to on }) }

        composeRule.onNodeWithTag("camera-enabled-Play room").performScrollTo().performClick()

        assertEquals("b" to true, call)
    }

    @Test
    fun `editing a camera hands back the whole camera`() {
        var edited: Camera? = null
        composeRule.setContent { Screen(onEditCamera = { edited = it }) }

        composeRule.onNodeWithTag("camera-edit-Nursery").performScrollTo().performClick()

        assertEquals(nursery, edited)
    }

    @Test
    fun `deleting a camera reports its id`() {
        var deleted: String? = null
        composeRule.setContent { Screen(onDeleteCamera = { deleted = it }) }

        composeRule.onNodeWithTag("camera-delete-Play room").performScrollTo().performClick()

        assertEquals("b", deleted)
    }

    @Test
    fun `the detector sliders live here now`() {
        var changed: DetectorSettings? = null
        composeRule.setContent {
            Screen(onDetectorChange = { changed = it(DetectorSettings()) })
        }

        composeRule.onNodeWithTag("threshold-slider").performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(0.4f) }

        assertEquals(0.4f, changed?.threshold)
    }

    @Test
    fun `an unsaveable form cannot be saved`() {
        var saved = false
        composeRule.setContent {
            Screen(
                form = CameraFormState(name = "Nursery", url = "not-a-url"),
                onFormSave = { saved = true },
            )
        }

        composeRule.onNodeWithTag("camera-save").performScrollTo().performClick()

        assertFalse(saved)
    }

    @Test
    fun `an empty camera list still offers the console route`() {
        composeRule.setContent { Screen(cameras = emptyList()) }

        composeRule.onNodeWithTag("open-onboarding").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `choosing which camera to monitor is gone`() {
        composeRule.setContent { Screen() }

        // Enabled is the whole story now: there is no second "monitor this" pick.
        composeRule.onNodeWithTag("camera-select-Nursery").assertDoesNotExist()
    }

    @Test
    fun `arming monitoring lives here now`() {
        var toggled: Boolean? = null
        composeRule.setContent {
            Screen(monitoringRunning = false, onToggleMonitoring = { toggled = it })
        }

        composeRule.onNodeWithTag("monitoring-switch").performScrollTo().performClick()

        assertEquals(true, toggled)
    }

    @Test
    fun `the switch is dead when there is nothing to listen to`() {
        var toggled: Boolean? = null
        composeRule.setContent {
            Screen(canMonitor = false, onToggleMonitoring = { toggled = it })
        }

        composeRule.onNodeWithTag("monitoring-switch").performScrollTo().performClick()

        // Offering to start a service that would immediately stop itself is
        // worse than showing the switch as unavailable.
        assertNull(toggled)
    }

    @Test
    fun `the level meter only shows while monitoring runs`() {
        composeRule.setContent { Screen(monitoringRunning = false) }

        composeRule.onNodeWithTag("audio-level-meter").assertDoesNotExist()
    }

    @Test
    fun `a running monitor shows its level`() {
        composeRule.setContent { Screen(monitoringRunning = true, audioLevel = 0.3f) }

        composeRule.onNodeWithTag("audio-level-meter").performScrollTo().assertExists()
    }
}

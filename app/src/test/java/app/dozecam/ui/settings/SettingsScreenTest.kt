package app.dozecam.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.dozecam.R
import app.dozecam.data.AppSettings
import app.dozecam.data.Camera
import app.dozecam.data.DetectorSettings
import app.dozecam.data.OrientationLock
import app.dozecam.monitoring.CameraAudibility
import app.dozecam.monitoring.Readiness
import app.dozecam.monitoring.ReadinessCheck
import app.dozecam.monitoring.ReadinessFacts
import app.dozecam.monitoring.ReadinessFinding
import app.dozecam.monitoring.ReadinessRemedy
import app.dozecam.ui.theme.DozecamTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        localNetworkGranted: Boolean = true,
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
        onPickAlertSound: () -> Unit = {},
        onPreviewAlertSound: () -> Unit = {},
        readiness: List<ReadinessFinding> = emptyList(),
        onReadinessRemedy: (ReadinessRemedy) -> Unit = {},
        onTestAlert: () -> Unit = {},
        initialDestination: String = "",
    ) {
        DozecamTheme {
            SettingsScreen(
                settings = settings,
                onSettingsChange = onSettingsChange,
                monitoringRunning = monitoringRunning,
                canMonitor = canMonitor,
                localNetworkGranted = localNetworkGranted,
                audioLevel = audioLevel,
                initialDestination = initialDestination,
                readiness = readiness,
                onReadinessRemedy = onReadinessRemedy,
                onTestAlert = onTestAlert,
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
                onPickAlertSound = onPickAlertSound,
                onPreviewAlertSound = onPreviewAlertSound,
            )
        }
    }

    private fun text(resId: Int): String =
        ApplicationProvider.getApplicationContext<Context>().getString(resId)

    /** The rows now live behind category doors; tests walk through them too. */
    private fun openCategory(category: SettingsCategory) {
        composeRule.onNodeWithTag("category-${category.name}").performScrollTo().performClick()
    }

    // ---- Hub ----

    @Test
    fun `the hub offers every category`() {
        composeRule.setContent { Screen() }

        SettingsCategory.entries.forEach { category ->
            composeRule.onNodeWithTag("category-${category.name}")
                .performScrollTo()
                .assertIsDisplayed()
        }
    }

    /**
     * Monitoring has no switch: it runs for as long as the app does, and the
     * way to end it — exiting — lives on the viewer. The hub says what the
     * monitor is doing and offers nothing that could quietly switch the night
     * off.
     */
    @Test
    fun `the hub reports monitoring rather than offering a switch`() {
        composeRule.setContent { Screen(monitoringRunning = true) }

        composeRule.onNodeWithTag("monitoring-status").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(text(R.string.monitoring_always_on))
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag("monitoring-switch").assertDoesNotExist()
    }

    @Test
    fun `a missing local network grant is named, not reported as idleness`() {
        composeRule.setContent { Screen(localNetworkGranted = false) }

        // "Not monitoring" beside a switch that flips itself back is a bug
        // report waiting to happen; the reason belongs on the row before the
        // switch is ever touched.
        composeRule.onNodeWithText(text(R.string.monitoring_needs_local_network))
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText(text(R.string.monitoring_idle)).assertDoesNotExist()
    }

    @Test
    fun `having nothing to listen to outranks the missing grant`() {
        composeRule.setContent { Screen(canMonitor = false, localNetworkGranted = false) }

        // A permission would buy nothing here: there is no camera behind it.
        composeRule.onNodeWithText(text(R.string.monitoring_unavailable))
            .performScrollTo()
            .assertIsDisplayed()
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

    @Test
    fun `leaving a category returns to the hub`() {
        composeRule.setContent { Screen() }
        openCategory(SettingsCategory.ALERTS)
        composeRule.onNodeWithTag("category-ALERTS").assertDoesNotExist()

        composeRule.onNodeWithTag("settings-back").performClick()

        composeRule.onNodeWithTag("category-ALERTS").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `the back arrow on the hub leaves settings`() {
        var left = false
        composeRule.setContent { Screen(onBack = { left = true }) }

        composeRule.onNodeWithTag("settings-back").performClick()

        assertEquals(true, left)
    }

    // ---- Search ----

    @Test
    fun `the wake alerts switch is searchable`() {
        composeRule.setContent { Screen() }

        composeRule.onNodeWithTag("settings-search").performTextInput("wake alerts")
        composeRule.onNodeWithTag("search-result-alerts").performScrollTo().performClick()

        composeRule.onNodeWithTag("alerts-switch").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `searching finds a setting and jumps into its category`() {
        composeRule.setContent { Screen() }

        composeRule.onNodeWithTag("settings-search").performTextInput("vibration")
        composeRule.onNodeWithTag("search-result-vibrate").performScrollTo().performClick()

        // The jump landed in Alerts with the row on screen and the query gone.
        composeRule.onNodeWithTag("vibrate-switch").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("settings-search").assertDoesNotExist()

        // Backing out lands on the hub proper, not on the stale result list.
        composeRule.onNodeWithTag("settings-back").performClick()
        composeRule.onNodeWithTag("category-ALERTS").performScrollTo().assertIsDisplayed()
    }

    /** Cameras are content, not settings; search deliberately skips them. */
    @Test
    fun `camera names are not searchable`() {
        composeRule.setContent { Screen() }

        composeRule.onNodeWithTag("settings-search").performTextInput("Nursery")

        composeRule.onNodeWithTag("search-empty").assertExists()
    }

    @Test
    fun `a search that matches nothing says so`() {
        composeRule.setContent { Screen() }

        composeRule.onNodeWithTag("settings-search").performTextInput("zzz")

        composeRule.onNodeWithTag("search-empty").assertExists()
        composeRule.onNodeWithTag("category-ALERTS").assertDoesNotExist()
    }

    @Test
    fun `clearing the search brings the hub back`() {
        composeRule.setContent { Screen() }
        composeRule.onNodeWithTag("settings-search").performTextInput("volume")
        composeRule.onNodeWithTag("category-ALERTS").assertDoesNotExist()

        composeRule.onNodeWithTag("settings-search-clear").performClick()

        composeRule.onNodeWithTag("category-ALERTS").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `the back arrow clears an open search instead of leaving`() {
        var left = false
        composeRule.setContent { Screen(onBack = { left = true }) }
        composeRule.onNodeWithTag("settings-search").performTextInput("volume")

        composeRule.onNodeWithTag("settings-back").performClick()

        assertFalse(left)
        composeRule.onNodeWithTag("category-ALERTS").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `a search result on the hub itself just puts the hub back`() {
        composeRule.setContent { Screen() }

        composeRule.onNodeWithTag("settings-search").performTextInput("Monitoring")
        composeRule.onNodeWithTag("search-result-monitoring").performScrollTo().performClick()

        composeRule.onNodeWithTag("monitoring-status").performScrollTo().assertIsDisplayed()
    }

    // ---- Display ----

    @Test
    fun `toggling night theme reports the change`() {
        var changed: AppSettings? = null
        composeRule.setContent {
            Screen(
                settings = AppSettings(nightTheme = false),
                onSettingsChange = { changed = it(AppSettings(nightTheme = false)) },
            )
        }
        openCategory(SettingsCategory.DISPLAY)

        composeRule.onNodeWithTag("night-theme-switch").performScrollTo().performClick()

        assertEquals(true, changed?.nightTheme)
    }

    @Test
    fun `toggling keep screen awake reports the change`() {
        var changed: AppSettings? = null
        composeRule.setContent {
            Screen(
                settings = AppSettings(keepScreenOn = true),
                onSettingsChange = { changed = it(AppSettings(keepScreenOn = true)) },
            )
        }
        openCategory(SettingsCategory.DISPLAY)

        composeRule.onNodeWithTag("keep-screen-switch").performScrollTo().performClick()

        assertEquals(false, changed?.keepScreenOn)
    }

    @Test
    fun `choosing an orientation lock reports the change`() {
        var changed: AppSettings? = null
        composeRule.setContent {
            Screen(onSettingsChange = { changed = it(AppSettings()) })
        }
        openCategory(SettingsCategory.DISPLAY)

        composeRule.onNodeWithTag("orientation-LANDSCAPE").performScrollTo().performClick()

        assertEquals(OrientationLock.LANDSCAPE, changed?.orientationLock)
    }

    @Test
    fun `the talk-back volume is settable`() {
        var changed: AppSettings? = null
        composeRule.setContent {
            Screen(onSettingsChange = { changed = it(AppSettings()) })
        }
        openCategory(SettingsCategory.DISPLAY)

        composeRule.onNodeWithTag("talkback-volume-slider").performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(0.3f) }

        assertEquals(0.3f, changed?.talkbackVolume)
    }

    // ---- Alerts ----

    /**
     * The same stored value as the viewer's alerts button, so the two cannot
     * disagree about whether the night is being watched.
     */
    @Test
    fun `switching wake alerts off reports the change`() {
        var changed: AppSettings? = null
        composeRule.setContent {
            Screen(
                settings = AppSettings(alertsEnabled = true),
                onSettingsChange = { changed = it(AppSettings(alertsEnabled = true)) },
            )
        }
        openCategory(SettingsCategory.ALERTS)

        composeRule.onNodeWithTag("alerts-switch").performScrollTo().performClick()

        assertEquals(false, changed?.alertsEnabled)
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
        openCategory(SettingsCategory.ALERTS)

        composeRule.onNodeWithTag("chime-switch").performScrollTo().performClick()

        assertEquals(false, changed?.alertChime)
    }

    @Test
    fun `the alert sound row opens the picker`() {
        var picked = false
        composeRule.setContent { Screen(onPickAlertSound = { picked = true }) }
        openCategory(SettingsCategory.ALERTS)

        composeRule.onNodeWithTag("alert-sound-row").performScrollTo().performClick()

        assertEquals(true, picked)
    }

    /** Nobody should first hear their alert sound at 3am. */
    @Test
    fun `the alert sound can be previewed without leaving settings`() {
        var previewed = false
        composeRule.setContent { Screen(onPreviewAlertSound = { previewed = true }) }
        openCategory(SettingsCategory.ALERTS)

        composeRule.onNodeWithTag("alert-sound-preview").performScrollTo().performClick()

        assertEquals(true, previewed)
    }

    @Test
    fun `switching the ramp off reports the change`() {
        var changed: AppSettings? = null
        composeRule.setContent {
            Screen(
                settings = AppSettings(alertRamp = true),
                onSettingsChange = { changed = it(AppSettings(alertRamp = true)) },
            )
        }
        openCategory(SettingsCategory.ALERTS)

        composeRule.onNodeWithTag("alert-ramp-switch").performScrollTo().performClick()

        assertEquals(false, changed?.alertRamp)
    }

    @Test
    fun `the alert volume and repeat interval are settable`() {
        var changed: AppSettings? = null
        composeRule.setContent {
            Screen(onSettingsChange = { changed = it(AppSettings()) })
        }
        openCategory(SettingsCategory.ALERTS)

        composeRule.onNodeWithTag("alert-volume-slider").performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(0.5f) }
        assertEquals(0.5f, changed?.alertVolume)

        composeRule.onNodeWithTag("alert-repeat-slider").performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(15f) }
        assertEquals(15_000L, changed?.alertRepeatIntervalMs)
    }

    // ---- Cameras ----

    @Test
    fun `switching a camera off reports it by id`() {
        var call: Pair<String, Boolean>? = null
        composeRule.setContent { Screen(onCameraEnabled = { id, on -> call = id to on }) }
        openCategory(SettingsCategory.CAMERAS)

        composeRule.onNodeWithTag("camera-enabled-Nursery").performScrollTo().performClick()

        assertEquals("a" to false, call)
    }

    @Test
    fun `switching a disabled camera back on reports it`() {
        var call: Pair<String, Boolean>? = null
        composeRule.setContent { Screen(onCameraEnabled = { id, on -> call = id to on }) }
        openCategory(SettingsCategory.CAMERAS)

        composeRule.onNodeWithTag("camera-enabled-Play room").performScrollTo().performClick()

        assertEquals("b" to true, call)
    }

    @Test
    fun `editing a camera hands back the whole camera`() {
        var edited: Camera? = null
        composeRule.setContent { Screen(onEditCamera = { edited = it }) }
        openCategory(SettingsCategory.CAMERAS)

        composeRule.onNodeWithTag("camera-edit-Nursery").performScrollTo().performClick()

        assertEquals(nursery, edited)
    }

    @Test
    fun `deleting a camera reports its id`() {
        var deleted: String? = null
        composeRule.setContent { Screen(onDeleteCamera = { deleted = it }) }
        openCategory(SettingsCategory.CAMERAS)

        composeRule.onNodeWithTag("camera-delete-Play room").performScrollTo().performClick()

        assertEquals("b", deleted)
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
        openCategory(SettingsCategory.CAMERAS)

        composeRule.onNodeWithTag("camera-save").performScrollTo().performClick()

        assertFalse(saved)
    }

    @Test
    fun `an empty camera list still offers the console route`() {
        composeRule.setContent { Screen(cameras = emptyList()) }
        openCategory(SettingsCategory.CAMERAS)

        composeRule.onNodeWithTag("open-onboarding").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `choosing which camera to monitor is gone`() {
        composeRule.setContent { Screen() }
        openCategory(SettingsCategory.CAMERAS)

        // Enabled is the whole story now: there is no second "monitor this" pick.
        composeRule.onNodeWithTag("camera-select-Nursery").assertDoesNotExist()
    }

    // ---- Detection ----

    @Test
    fun `the detector sliders live behind the detection door`() {
        var changed: DetectorSettings? = null
        composeRule.setContent {
            Screen(onDetectorChange = { changed = it(DetectorSettings()) })
        }
        openCategory(SettingsCategory.DETECTION)

        composeRule.onNodeWithTag("threshold-slider").performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(0.4f) }

        assertEquals(0.4f, changed?.threshold)
    }

    @Test
    fun `settings opens checklist as a separate screen and back returns to settings`() {
        var left = false
        composeRule.setContent { Screen(readiness = healthy, onBack = { left = true }) }
        composeRule.onNodeWithTag("readiness-test").assertDoesNotExist()

        composeRule.onNodeWithTag("settings-checklist").performScrollTo().performClick()

        composeRule.onNodeWithText(text(R.string.checklist_title)).assertIsDisplayed()
        composeRule.onNodeWithTag("readiness-test").assertIsDisplayed()
        composeRule.onNodeWithTag("settings-search").assertDoesNotExist()
        composeRule.onNodeWithTag("settings-back").performClick()

        assertFalse(left)
        composeRule.onNodeWithTag("settings-checklist").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("readiness-test").assertDoesNotExist()
    }

    @Test
    fun `a checklist opened from the monitor returns to the monitor`() {
        var left = false
        composeRule.setContent {
            Screen(initialDestination = CHECKLIST_DESTINATION, readiness = healthy, onBack = { left = true })
        }

        composeRule.onNodeWithTag("settings-back").performClick()

        assertTrue(left)
    }

    @Test
    fun `camera remedy returns to checklist before leaving settings`() {
        var left = false
        var externalRemedy: ReadinessRemedy? = null
        composeRule.setContent {
            Screen(
                initialDestination = CHECKLIST_DESTINATION,
                readiness = Readiness.of(ReadinessFacts(cameras = emptyList())),
                onReadinessRemedy = { externalRemedy = it },
                onBack = { left = true },
            )
        }
        composeRule.onNodeWithTag("readiness-remedy-CAMERAS_HEARD").performScrollTo().performClick()
        composeRule.onNodeWithText(text(R.string.section_cameras)).assertIsDisplayed()
        composeRule.onNodeWithTag("readiness-test").assertDoesNotExist()

        composeRule.onNodeWithTag("settings-back").performClick()

        assertFalse(left)
        assertNull(externalRemedy)
        composeRule.onNodeWithTag("readiness-test").assertIsDisplayed()
        composeRule.onNodeWithTag("settings-back").performClick()
        assertTrue(left)
    }

    @Test
    fun `searching for the test alert opens the checklist`() {
        composeRule.setContent { Screen(readiness = healthy) }
        composeRule.onNodeWithTag("settings-search").performTextInput(text(R.string.readiness_test))

        composeRule.onNodeWithTag("search-result-readiness").performScrollTo().performClick()

        composeRule.onNodeWithTag("settings-search").assertDoesNotExist()
        composeRule.onNodeWithTag("readiness-test").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("settings-back").performClick()
        composeRule.onNodeWithTag("settings-checklist").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `checklist updates from checking to problem to warning to ready`() {
        val findings = mutableStateOf<List<ReadinessFinding>>(emptyList())
        composeRule.setContent {
            Screen(initialDestination = CHECKLIST_DESTINATION, readiness = findings.value)
        }
        composeRule.onNodeWithTag("checklist-checking").assertIsDisplayed()
        composeRule.onNodeWithTag("readiness-test").assertDoesNotExist()

        composeRule.runOnIdle { findings.value = Readiness.of(ReadinessFacts(notificationsAllowed = false)) }
        composeRule.onNodeWithTag("checklist-checking").assertDoesNotExist()
        composeRule.onNodeWithText(text(R.string.readiness_notifications_fail))
            .performScrollTo()
            .assertIsDisplayed()

        composeRule.runOnIdle { findings.value = alertsOff }
        composeRule.onNodeWithText(text(R.string.readiness_notifications_fail)).assertDoesNotExist()
        composeRule.onNodeWithText(text(R.string.readiness_alerts_on_fail))
            .performScrollTo()
            .assertIsDisplayed()

        // Every row a sentence about tonight: the one that was failing now
        // reads as the thing being true, rather than disappearing.
        composeRule.runOnIdle { findings.value = healthy }
        composeRule.onNodeWithText(text(R.string.readiness_alerts_on_fail)).assertDoesNotExist()
        composeRule.onNodeWithText(text(R.string.readiness_alerts_on_pass))
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun `problems appear before warnings even when check order is different`() {
        composeRule.setContent {
            Screen(
                initialDestination = CHECKLIST_DESTINATION,
                readiness = Readiness.of(ReadinessFacts(alertsEnabled = false, monitoringRunning = false)),
            )
        }

        val rows = composeRule.onAllNodes(
            hasTestTag("readiness-MONITORING") or hasTestTag("readiness-ALERTS_ON"),
        ).fetchSemanticsNodes().map { it.config[SemanticsProperties.TestTag] }
        assertEquals(listOf("readiness-MONITORING", "readiness-ALERTS_ON"), rows)
    }

    @Test
    fun `a test confirmation closes if alerts become unavailable`() {
        val findings = mutableStateOf(healthy)
        var fired = false
        composeRule.setContent {
            Screen(
                initialDestination = CHECKLIST_DESTINATION,
                readiness = findings.value,
                onTestAlert = { fired = true },
            )
        }
        composeRule.onNodeWithTag("readiness-test").performScrollTo().performClick()
        composeRule.onNodeWithTag("readiness-test-confirm").assertIsDisplayed()

        composeRule.runOnIdle { findings.value = alertsOff }

        composeRule.onNodeWithTag("readiness-test-confirm").assertDoesNotExist()
        assertFalse(fired)
        composeRule.runOnIdle { findings.value = healthy }
        composeRule.onNodeWithTag("readiness-test-confirm").assertDoesNotExist()
    }

    // ---- The bedtime check ----

    private val healthy = Readiness.of(ReadinessFacts())
    private val alertsOff = Readiness.of(ReadinessFacts(alertsEnabled = false))

    /**
     * The probe's first read is a frame or two behind the screen. "Ready for
     * tonight" is the one thing this section must never say before it knows.
     */
    @Test
    fun `an unread checklist claims nothing`() {
        composeRule.setContent { Screen(initialDestination = CHECKLIST_DESTINATION, readiness = emptyList()) }

        composeRule.onNodeWithTag("checklist-checking").assertIsDisplayed()
        composeRule.onNodeWithTag("readiness-test").assertDoesNotExist()
        composeRule.onNodeWithText(text(R.string.readiness_alerts_on_pass)).assertDoesNotExist()
    }

    @Test
    fun `a phone with nothing wrong says so`() {
        composeRule.setContent { Screen(initialDestination = CHECKLIST_DESTINATION, readiness = healthy) }

        composeRule.onNodeWithText(text(R.string.readiness_alerts_on_pass))
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText(text(R.string.readiness_monitoring_pass))
            .performScrollTo()
            .assertIsDisplayed()
    }

    /** What is wrong comes first, before anything that is right. */
    @Test
    fun `a failing check is on screen without being asked for`() {
        composeRule.setContent { Screen(initialDestination = CHECKLIST_DESTINATION, readiness = alertsOff) }

        composeRule.onNodeWithTag("readiness-${ReadinessCheck.ALERTS_ON.name}")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithText(text(R.string.readiness_alerts_on_fail))
            .performScrollTo()
            .assertIsDisplayed()
    }

    /** A checklist nobody can see the whole of is just a reassuring word. */
    @Test
    fun `passing checks are listed without being asked for`() {
        composeRule.setContent { Screen(initialDestination = CHECKLIST_DESTINATION, readiness = alertsOff) }

        composeRule.onNodeWithTag("readiness-${ReadinessCheck.NOTIFICATIONS.name}")
            .performScrollTo()
            .assertIsDisplayed()
    }

    /** The failures are what somebody opened this to fix; they go first. */
    @Test
    fun `a failure is listed above the checks that passed`() {
        composeRule.setContent { Screen(initialDestination = CHECKLIST_DESTINATION, readiness = alertsOff) }

        val rows = composeRule.onAllNodes(
            hasTestTag("readiness-${ReadinessCheck.ALERTS_ON.name}") or
                hasTestTag("readiness-${ReadinessCheck.NOTIFICATIONS.name}"),
        ).fetchSemanticsNodes().map { it.config[SemanticsProperties.TestTag] }
        assertEquals(
            listOf(
                "readiness-${ReadinessCheck.ALERTS_ON.name}",
                "readiness-${ReadinessCheck.NOTIFICATIONS.name}",
            ),
            rows,
        )
    }

    /** Pressing it is the only thing on this page that proves anything. */
    @Test
    fun `the test alert sits above the checks`() {
        composeRule.setContent { Screen(initialDestination = CHECKLIST_DESTINATION, readiness = alertsOff) }

        val rows = composeRule.onAllNodes(
            hasTestTag("readiness-test") or hasTestTag("readiness-${ReadinessCheck.ALERTS_ON.name}"),
        ).fetchSemanticsNodes().map { it.config[SemanticsProperties.TestTag] }
        assertEquals(listOf("readiness-test", "readiness-${ReadinessCheck.ALERTS_ON.name}"), rows)
    }

    @Test
    fun `a failing check offers the button that fixes it`() {
        var remedy: ReadinessRemedy? = null
        composeRule.setContent {
            Screen(initialDestination = CHECKLIST_DESTINATION, readiness = alertsOff, onReadinessRemedy = { remedy = it })
        }

        composeRule.onNodeWithTag("readiness-remedy-${ReadinessCheck.ALERTS_ON.name}")
            .performScrollTo()
            .performClick()

        assertEquals(ReadinessRemedy.TURN_ALERTS_ON, remedy)
    }

    @Test
    fun `a passing check offers no button to press`() {
        composeRule.setContent { Screen(initialDestination = CHECKLIST_DESTINATION, readiness = healthy) }

        composeRule.onNodeWithTag("readiness-${ReadinessCheck.ALERTS_ON.name}")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag("readiness-remedy-${ReadinessCheck.ALERTS_ON.name}")
            .assertDoesNotExist()
    }

    @Test
    fun `muted camera sound explains the problem and opens sound settings`() {
        var remedy: ReadinessRemedy? = null
        composeRule.setContent {
            Screen(
                initialDestination = CHECKLIST_DESTINATION,
                readiness = Readiness.of(ReadinessFacts(cameraSoundEnabled = true, mediaVolume = 0)),
                onReadinessRemedy = { remedy = it },
            )
        }

        composeRule.onNodeWithText(text(R.string.readiness_media_volume_fail))
            .performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(text(R.string.readiness_media_volume_why))
            .performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("readiness-remedy-${ReadinessCheck.MEDIA_VOLUME.name}")
            .performScrollTo().performClick()

        assertEquals(ReadinessRemedy.SOUND_SETTINGS, remedy)
    }

    @Test
    fun `camera volume check updates when volume recovers and disappears when sound is off`() {
        val facts = mutableStateOf(ReadinessFacts(cameraSoundEnabled = true, mediaVolume = 0))
        composeRule.setContent {
            Screen(initialDestination = CHECKLIST_DESTINATION, readiness = Readiness.of(facts.value))
        }
        composeRule.onNodeWithText(text(R.string.readiness_media_volume_fail))
            .performScrollTo().assertIsDisplayed()

        composeRule.runOnIdle { facts.value = facts.value.copy(mediaVolume = 6) }

        composeRule.onNodeWithText(text(R.string.readiness_media_volume_pass))
            .performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("readiness-remedy-${ReadinessCheck.MEDIA_VOLUME.name}")
            .assertDoesNotExist()

        composeRule.runOnIdle { facts.value = facts.value.copy(cameraSoundEnabled = false, mediaVolume = 0) }

        composeRule.onNodeWithTag("readiness-${ReadinessCheck.MEDIA_VOLUME.name}").assertDoesNotExist()
        composeRule.onNodeWithText(text(R.string.readiness_media_volume_pass)).assertDoesNotExist()
    }

    /** The unheard rooms are named, because "two cameras" sends someone hunting. */
    @Test
    fun `an unheard room is named on its row`() {
        val findings = Readiness.of(
            ReadinessFacts(
                cameras = listOf(CameraAudibility("play-room", "Play room", live = true, lastAudioAtMs = null)),
            ),
        )

        composeRule.setContent { Screen(initialDestination = CHECKLIST_DESTINATION, readiness = findings) }

        composeRule.onNodeWithText("Play room", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
    }

    // ---- The test alert ----

    @Test
    fun `the test alert asks before it startles anyone`() {
        var fired = false
        composeRule.setContent { Screen(initialDestination = CHECKLIST_DESTINATION, readiness = healthy, onTestAlert = { fired = true }) }

        composeRule.onNodeWithTag("readiness-test").performScrollTo().performClick()

        assertFalse("a button that fires it outright would be a trap", fired)
        composeRule.onNodeWithText(text(R.string.readiness_test_body)).assertIsDisplayed()
    }

    @Test
    fun `confirming fires the real thing`() {
        var fired = false
        composeRule.setContent { Screen(initialDestination = CHECKLIST_DESTINATION, readiness = healthy, onTestAlert = { fired = true }) }
        composeRule.onNodeWithTag("readiness-test").performScrollTo().performClick()

        composeRule.onNodeWithTag("readiness-test-confirm").performClick()

        assertEquals(true, fired)
    }

    /**
     * With alerts off the service deliberately raises nothing, so a test would
     * be a button that does nothing while a toast claimed it had been sent.
     */
    @Test
    fun `with alerts off the test says so rather than pretending`() {
        val alertsOffFindings = Readiness.of(ReadinessFacts(alertsEnabled = false))
        var fired = false

        composeRule.setContent {
            Screen(initialDestination = CHECKLIST_DESTINATION, readiness = alertsOffFindings, onTestAlert = { fired = true })
        }

        composeRule.onNodeWithText(text(R.string.readiness_test_unavailable_alerts_off))
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag("readiness-test").performScrollTo().performClick()
        composeRule.onNodeWithText(text(R.string.readiness_test_body)).assertDoesNotExist()
        assertFalse(fired)
    }

    /**
     * The monitor raises it, so there has to be one — said out loud rather than
     * left as a button that does nothing, which is how a person concludes the
     * feature is broken.
     */
    @Test
    fun `with no monitor running the test says why it cannot run`() {
        val stopped = Readiness.of(ReadinessFacts(monitoringRunning = false))

        composeRule.setContent { Screen(initialDestination = CHECKLIST_DESTINATION, readiness = stopped) }

        composeRule.onNodeWithText(text(R.string.readiness_test_unavailable))
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag("readiness-test").performScrollTo().performClick()
        composeRule.onNodeWithText(text(R.string.readiness_test_body)).assertDoesNotExist()
    }
    /**
     * A masked check stood aside because something it depends on failed first.
     * Listing it as checked would put a green tick beside "the sound alert is
     * switched on in Android" over a channel nobody has looked at — a claim
     * this card exists to stop the app making.
     */
    @Test
    fun `a check that stood aside is not listed as one that passed`() {
        val denied = Readiness.of(ReadinessFacts(notificationsAllowed = false))

        composeRule.setContent { Screen(initialDestination = CHECKLIST_DESTINATION, readiness = denied) }

        composeRule.onNodeWithTag("readiness-${ReadinessCheck.ALERT_CHANNEL.name}")
            .assertDoesNotExist()
        composeRule.onNodeWithTag("readiness-${ReadinessCheck.ALERT_CHANNEL_PRIORITY.name}")
            .assertDoesNotExist()
        // The one that was actually checked is still there.
        composeRule.onNodeWithTag("readiness-${ReadinessCheck.ALARM_VOLUME.name}")
            .performScrollTo()
            .assertIsDisplayed()
    }
}

package app.dozecam.ui.settings

import app.dozecam.MainDispatcherRule
import app.dozecam.data.AppSettings
import app.dozecam.data.AppSettingsStore
import app.dozecam.data.Camera
import app.dozecam.data.CameraStore
import app.dozecam.data.DetectorSettings
import app.dozecam.data.DetectorSettingsStore
import app.dozecam.data.ProtectStream
import app.dozecam.monitoring.CameraMonitorState
import app.dozecam.monitoring.MonitoringState
import app.dozecam.monitoring.Readiness
import app.dozecam.monitoring.ReadinessCheck
import app.dozecam.monitoring.ReadinessFacts
import app.dozecam.monitoring.ReadinessFinding
import app.dozecam.protect.CredentialsStore
import app.dozecam.protect.ProtectCredentials
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SettingsViewModelTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private class FakeCameraStore(initial: List<Camera> = emptyList()) : CameraStore {
        val state = MutableStateFlow(initial)

        override val cameras: Flow<List<Camera>> = state
        override val enabledCameras: Flow<List<Camera>> = state.map { it.filter { c -> c.enabled } }
        override suspend fun upsert(camera: Camera) {
            val index = state.value.indexOfFirst { it.id == camera.id }
            state.value = if (index >= 0) {
                state.value.toMutableList().also { it[index] = camera }
            } else {
                state.value + camera
            }
        }

        override suspend fun remove(id: String) {
            state.value = state.value.filterNot { it.id == id }
        }


        override suspend fun setEnabled(id: String, enabled: Boolean) {
            state.value = state.value.map { if (it.id == id) it.copy(enabled = enabled) else it }
        }
    }

    private class FakeAppSettings : AppSettingsStore {
        val state = MutableStateFlow(AppSettings())
        override val settings: Flow<AppSettings> = state
        override suspend fun update(transform: (AppSettings) -> AppSettings) {
            state.value = transform(state.value)
        }
    }

    private class FakeDetectorSettings : DetectorSettingsStore {
        val state = MutableStateFlow(DetectorSettings())
        override val settings: Flow<DetectorSettings> = state
        override suspend fun update(transform: (DetectorSettings) -> DetectorSettings) {
            state.value = transform(state.value)
        }
    }

    /** No console signed in: every camera is judged on its own RTSP URL. */
    private class NoCredentials : CredentialsStore {
        override fun save(credentials: ProtectCredentials) = Unit
        override fun load(): ProtectCredentials? = null
        override fun clear() = Unit
    }

    private class MutableCredentials(var stored: ProtectCredentials? = null) : CredentialsStore {
        override fun save(credentials: ProtectCredentials) {
            stored = credentials
        }

        override fun load(): ProtectCredentials? = stored
        override fun clear() {
            stored = null
        }
    }

    private fun viewModel(
        cameras: FakeCameraStore = FakeCameraStore(),
        detector: FakeDetectorSettings = FakeDetectorSettings(),
        monitoring: MonitoringState = MonitoringState(),
        credentials: CredentialsStore = NoCredentials(),
        settings: FakeAppSettings = FakeAppSettings(),
        readiness: Flow<List<ReadinessFinding>> = emptyFlow(),
    ) = SettingsViewModel(
        settings,
        cameras,
        detector,
        monitoring,
        credentials,
        readiness,
        ioDispatcher = mainDispatcher.dispatcher,
    )

    @Test
    fun `saving a new camera normalizes its url and enables it`() = runTest {
        val store = FakeCameraStore()
        val model = viewModel(store)

        model.onFormName("  Nursery  ")
        model.onFormUrl("rtsps://cam:7441/token")
        model.saveCamera()

        val saved = store.state.value.single()
        assertEquals("Nursery", saved.name)
        // rtsps is not a real Protect stream; it is rewritten before it is stored.
        assertEquals("rtsp://cam:7447/token", saved.url)
        assertTrue(saved.enabled)
    }

    @Test
    fun `editing a camera keeps its protect identity`() = runTest {
        val existing = Camera(
            id = "a",
            name = "Nursery",
            url = "rtsp://cam:7447/a",
            protect = ProtectStream("cam1", 1, consoleHost = "console.lan"),
        )
        val store = FakeCameraStore(listOf(existing))
        val model = viewModel(store)

        model.startEdit(existing)
        model.onFormName("Baby room")
        model.saveCamera()

        val saved = store.state.value.single()
        assertEquals("Baby room", saved.name)
        // Losing this would send an AV1 camera back to RTSP and a black screen.
        assertEquals("cam1", saved.protect?.cameraId)
        assertEquals("console.lan", saved.protect?.consoleHost)
        assertEquals(1, store.state.value.size)
    }

    @Test
    fun `editing a switched-off camera does not switch it back on`() = runTest {
        val existing = Camera("a", "Nursery", "rtsp://cam:7447/a", enabled = false)
        val store = FakeCameraStore(listOf(existing))
        val model = viewModel(store)

        model.startEdit(existing)
        model.onFormName("Nursery 2")
        model.saveCamera()

        assertEquals(false, store.state.value.single().enabled)
    }

    @Test
    fun `a new camera gets its own id rather than reusing an edited one`() = runTest {
        val store = FakeCameraStore(listOf(Camera("a", "Nursery", "rtsp://cam:7447/a")))
        val model = viewModel(store)

        model.onFormName("Play room")
        model.onFormUrl("rtsp://cam:7447/b")
        model.saveCamera()

        assertEquals(2, store.state.value.size)
        assertNotEquals("a", store.state.value.last().id)
    }

    @Test
    fun `an invalid form saves nothing`() = runTest {
        val store = FakeCameraStore()
        val model = viewModel(store)

        model.onFormName("Nursery")
        model.onFormUrl("http://cam/not-rtsp")
        model.saveCamera()

        assertTrue(store.state.value.isEmpty())
    }

    @Test
    fun `switching a camera off writes through to the store`() = runTest {
        val store = FakeCameraStore(listOf(Camera("a", "Nursery", "rtsp://cam:7447/a")))
        val model = viewModel(store)

        model.setCameraEnabled("a", false)

        assertEquals(false, store.state.value.single().enabled)
    }

    @Test
    fun `deleting the camera being edited clears the form`() = runTest {
        val existing = Camera("a", "Nursery", "rtsp://cam:7447/a")
        val store = FakeCameraStore(listOf(existing))
        val model = viewModel(store)
        model.startEdit(existing)

        model.deleteCamera("a")

        assertNull(model.form.value.editingId)
        assertEquals("", model.form.value.name)
    }

    @Test
    fun `detector changes write through`() = runTest {
        val detector = FakeDetectorSettings()
        val model = viewModel(detector = detector)

        model.onDetectorChange { it.copy(threshold = 0.25f) }

        assertEquals(0.25f, detector.state.value.threshold)
    }

    @Test
    fun `monitoring has something to do when a monitorable camera is on`() = runTest {
        val model = viewModel(FakeCameraStore(listOf(Camera("a", "Nursery", "rtsp://c:7447/a"))))
        runCurrent()

        assertTrue(model.canMonitor.value)
    }

    @Test
    fun `monitoring has nothing to do when every camera is watch-only`() = runTest {
        val model = viewModel(FakeCameraStore(listOf(Camera("a", "Stale", "rtsps://c:7441/a"))))
        runCurrent()

        // Offering to start a service that would immediately stop itself is
        // worse than showing the switch as unavailable.
        assertFalse(model.canMonitor.value)
    }

    @Test
    fun `signing in to a console gives monitoring something to do`() = runTest {
        val credentials = MutableCredentials()
        val monitoring = MonitoringState()
        val model = viewModel(
            cameras = FakeCameraStore(
                listOf(
                    Camera(
                        id = "a",
                        name = "Nursery",
                        url = "rtsps://c:7441/a",
                        protect = ProtectStream("cam1", 1, "console.lan"),
                    ),
                ),
            ),
            monitoring = monitoring,
            credentials = credentials,
        )
        runCurrent()
        // Nothing signed in yet, so the livestream is not a transport at all.
        assertFalse(model.canMonitor.value)

        credentials.save(ProtectCredentials("console.lan", "user", "pass"))
        monitoring.consoleGeneration.value++
        runCurrent()

        // Onboarding can be reached from this very screen and left without
        // importing anything, so neither the camera list nor the service state
        // moves; a switch that waited for one of those would stay dead over a
        // camera the monitor could now listen to perfectly well.
        assertTrue(model.canMonitor.value)
    }

    @Test
    fun `a switched-off camera gives monitoring nothing to do`() = runTest {
        val model = viewModel(
            FakeCameraStore(listOf(Camera("a", "Nursery", "rtsp://c:7447/a", enabled = false))),
        )
        runCurrent()

        assertFalse(model.canMonitor.value)
    }

    @Test
    fun `a running monitor counts as having something to do`() = runTest {
        val monitoring = MonitoringState().apply { serviceRunning.value = true }
        val model = viewModel(
            FakeCameraStore(listOf(Camera("a", "Stale", "rtsps://c:7441/a"))),
            monitoring = monitoring,
        )
        runCurrent()

        assertTrue(model.canMonitor.value)
    }

    @Test
    fun `the meter shows the loudest camera rather than an average`() = runTest {
        val monitoring = MonitoringState()
        val model = viewModel(monitoring = monitoring)
        runCurrent()

        monitoring.put(CameraMonitorState("a", "Nursery", level = 0.1f))
        monitoring.put(CameraMonitorState("b", "Play room", level = 0.42f))
        runCurrent()

        // Averaging would hide one loud room behind three quiet ones.
        assertEquals(0.42f, model.audioLevel.value)
    }
    /**
     * Forgetting a check that has started passing again cannot belong to the
     * viewer alone. This is the screen a readiness prompt sends people to and
     * the screen they fix things on — and leaving the app from here with a
     * stale acknowledgement would spend the one interruption that failure is
     * owed the next time it comes back.
     */
    @Test
    fun `a check that starts passing again is forgotten from settings too`() = runTest {
        val settings = FakeAppSettings()
        settings.state.value = AppSettings(
            acknowledgedReadinessChecks = setOf(ReadinessCheck.ALERTS_ON.name),
        )

        val model = viewModel(
            settings = settings,
            readiness = MutableStateFlow(Readiness.of(ReadinessFacts())),
        )
        // The checklist is only watched while a screen is looking at it, which
        // is the whole point of it not polling all night; a test has to look too.
        backgroundScope.launch { model.readiness.collect {} }
        runCurrent()

        assertEquals(
            emptySet<String>(),
            settings.state.value.acknowledgedReadinessChecks,
        )
    }

    @Test
    fun `a check that is still failing keeps its acknowledgement`() = runTest {
        val settings = FakeAppSettings()
        settings.state.value = AppSettings(
            acknowledgedReadinessChecks = setOf(ReadinessCheck.ALERTS_ON.name),
        )

        val model = viewModel(
            settings = settings,
            readiness = MutableStateFlow(Readiness.of(ReadinessFacts(alertsEnabled = false))),
        )
        backgroundScope.launch { model.readiness.collect {} }
        runCurrent()

        assertEquals(
            setOf(ReadinessCheck.ALERTS_ON.name),
            settings.state.value.acknowledgedReadinessChecks,
        )
    }
}

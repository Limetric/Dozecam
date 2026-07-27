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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
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

    private fun viewModel(
        cameras: FakeCameraStore = FakeCameraStore(),
        detector: FakeDetectorSettings = FakeDetectorSettings(),
        monitoring: MonitoringState = MonitoringState(),
    ) = SettingsViewModel(FakeAppSettings(), cameras, detector, monitoring)

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
    fun `the monitoring switch is live when a monitorable camera is on`() = runTest {
        val model = viewModel(FakeCameraStore(listOf(Camera("a", "Nursery", "rtsp://c:7447/a"))))
        runCurrent()

        assertTrue(model.canMonitor.value)
    }

    @Test
    fun `the monitoring switch is dead when every camera is watch-only`() = runTest {
        val model = viewModel(FakeCameraStore(listOf(Camera("a", "Stale", "rtsps://c:7441/a"))))
        runCurrent()

        // Offering to start a service that would immediately stop itself is
        // worse than showing the switch as unavailable.
        assertFalse(model.canMonitor.value)
    }

    @Test
    fun `a switched-off camera does not make the switch live`() = runTest {
        val model = viewModel(
            FakeCameraStore(listOf(Camera("a", "Nursery", "rtsp://c:7447/a", enabled = false))),
        )
        runCurrent()

        assertFalse(model.canMonitor.value)
    }

    @Test
    fun `a running monitor can always be switched off`() = runTest {
        val monitoring = MonitoringState().apply { serviceRunning.value = true }
        val model = viewModel(
            FakeCameraStore(listOf(Camera("a", "Stale", "rtsps://c:7441/a"))),
            monitoring = monitoring,
        )
        runCurrent()

        assertTrue(model.canMonitor.value)
    }

    @Test
    fun `switching monitoring off records the deliberate stop`() = runTest {
        val monitoring = MonitoringState()
        val model = viewModel(monitoring = monitoring)

        model.onMonitoringIntent(enabled = false)

        assertTrue(monitoring.userStopped.value)
        assertFalse(monitoring.shouldAutoArm(enabledCameraCount = 1))
    }

    @Test
    fun `switching it back on clears the stop`() = runTest {
        val monitoring = MonitoringState().apply { userStopped.value = true }
        val model = viewModel(monitoring = monitoring)

        model.onMonitoringIntent(enabled = true)

        assertFalse(monitoring.userStopped.value)
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
}

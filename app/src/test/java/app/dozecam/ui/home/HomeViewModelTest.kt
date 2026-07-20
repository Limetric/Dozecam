package app.dozecam.ui.home

import app.dozecam.MainDispatcherRule
import app.dozecam.data.Camera
import app.dozecam.data.CameraStore
import app.dozecam.data.DetectorSettings
import app.dozecam.data.DetectorSettingsStore
import app.dozecam.monitoring.MonitoringState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

private class FakeCameraStore(initial: List<Camera> = emptyList()) : CameraStore {
    val stored = MutableStateFlow(initial)
    val selectedId = MutableStateFlow<String?>(null)

    override val cameras: Flow<List<Camera>> = stored
    override val selectedCamera: Flow<Camera?> =
        combine(stored, selectedId) { list, id ->
            list.firstOrNull { it.id == id } ?: list.firstOrNull()
        }

    override suspend fun upsert(camera: Camera) {
        val index = stored.value.indexOfFirst { it.id == camera.id }
        stored.value = if (index >= 0) {
            stored.value.toMutableList().also { it[index] = camera }
        } else {
            stored.value + camera
        }
    }

    override suspend fun remove(id: String) {
        stored.value = stored.value.filterNot { it.id == id }
        if (selectedId.value == id) selectedId.value = null
    }

    override suspend fun select(id: String) {
        selectedId.value = id
    }
}

private class FakeDetectorSettings : DetectorSettingsStore {
    val stored = MutableStateFlow(DetectorSettings())
    override val settings: Flow<DetectorSettings> = stored
    override suspend fun update(transform: (DetectorSettings) -> DetectorSettings) {
        stored.value = transform(stored.value)
    }
}

class HomeViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun viewModel(
        cameraStore: FakeCameraStore = FakeCameraStore(),
        detectorSettings: FakeDetectorSettings = FakeDetectorSettings(),
        monitoringState: MonitoringState = MonitoringState(),
    ) = HomeViewModel(cameraStore, detectorSettings, monitoringState)

    @Test
    fun `saving the form adds a camera and clears the form`() = runTest {
        val store = FakeCameraStore()
        val viewModel = viewModel(store)

        viewModel.onFormName("Nursery")
        viewModel.onFormUrl("rtsp://cam:7447/token")
        assertTrue(viewModel.form.value.canSave)
        viewModel.saveCamera()

        assertEquals(1, store.stored.value.size)
        assertEquals("Nursery", store.stored.value.first().name)
        assertEquals(CameraFormState(), viewModel.form.value)
    }

    @Test
    fun `form cannot save with an invalid url or blank name`() = runTest {
        val viewModel = viewModel()

        viewModel.onFormName("Nursery")
        viewModel.onFormUrl("http://nope")
        assertFalse(viewModel.form.value.canSave)

        viewModel.onFormUrl("rtsp://cam:7447/token")
        viewModel.onFormName("  ")
        assertFalse(viewModel.form.value.canSave)
        viewModel.saveCamera()
        assertTrue(viewModel.cameras.value.isEmpty())
    }

    @Test
    fun `editing loads the camera into the form and save updates in place`() = runTest {
        val camera = Camera("a", "Nursery", "rtsp://cam:7447/a")
        val store = FakeCameraStore(listOf(camera))
        val viewModel = viewModel(store)

        viewModel.startEdit(camera)
        assertEquals("Nursery", viewModel.form.value.name)
        viewModel.onFormName("Nursery 2")
        viewModel.saveCamera()

        assertEquals(listOf("Nursery 2"), store.stored.value.map { it.name })
        assertEquals(1, store.stored.value.size)
    }

    @Test
    fun `deleting the camera being edited clears the form`() = runTest {
        val camera = Camera("a", "Nursery", "rtsp://cam:7447/a")
        val store = FakeCameraStore(listOf(camera))
        val viewModel = viewModel(store)

        viewModel.startEdit(camera)
        viewModel.deleteCamera("a")

        assertEquals(CameraFormState(), viewModel.form.value)
        assertTrue(store.stored.value.isEmpty())
    }

    @Test
    fun `selection follows the store`() = runTest {
        val store = FakeCameraStore(
            listOf(
                Camera("a", "Nursery", "rtsp://cam:7447/a"),
                Camera("b", "Play room", "rtsp://cam:7447/b"),
            ),
        )
        val viewModel = viewModel(store)

        assertEquals("a", viewModel.selectedCamera.value?.id)
        viewModel.selectCamera("b")
        assertEquals("b", viewModel.selectedCamera.value?.id)
    }

    @Test
    fun `canMonitor requires a selected camera or a running service`() = runTest {
        val monitoringState = MonitoringState()
        val viewModel = viewModel(monitoringState = monitoringState)

        assertNull(viewModel.selectedCamera.value)
        assertFalse(viewModel.canMonitor.value)

        monitoringState.serviceRunning.value = true
        assertTrue(viewModel.canMonitor.value)
    }

    @Test
    fun `detector settings load and persist through the view model`() = runTest {
        val detectorSettings = FakeDetectorSettings()
        val viewModel = viewModel(detectorSettings = detectorSettings)

        assertEquals(DetectorSettings(), viewModel.detector.value)

        val custom = DetectorSettings(threshold = 0.3f, sustainMs = 2_000, quietMs = 15_000)
        viewModel.onDetectorChange { custom }

        assertEquals(custom, detectorSettings.stored.value)
        assertEquals(custom, viewModel.detector.value)
    }
}

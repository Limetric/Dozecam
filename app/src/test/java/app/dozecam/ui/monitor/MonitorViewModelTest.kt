package app.dozecam.ui.monitor

import app.dozecam.MainDispatcherRule
import app.dozecam.data.Camera
import app.dozecam.data.CameraStore
import app.dozecam.data.ProtectStream
import app.dozecam.player.StreamSource
import app.dozecam.protect.CredentialsStore
import app.dozecam.protect.ProtectCredentials
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MonitorViewModelTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private class FakeCameraStore(initial: List<Camera>) : CameraStore {
        val state = MutableStateFlow(initial)

        override val cameras: Flow<List<Camera>> = state
        override val enabledCameras: Flow<List<Camera>> = state.map { it.filter(Camera::enabled) }

        override suspend fun upsert(camera: Camera) = Unit
        override suspend fun remove(id: String) = Unit

        override suspend fun setEnabled(id: String, enabled: Boolean) {
            state.value = state.value.map { if (it.id == id) it.copy(enabled = enabled) else it }
        }
    }


    private class MutableCredentials(var host: String?) : CredentialsStore {
        override fun save(credentials: ProtectCredentials) = Unit
        override fun load(): ProtectCredentials? =
            host?.let { ProtectCredentials(it, "user", "pass") }

        override fun clear() = Unit
    }


    private fun viewModel(
        cameras: List<Camera>,
        consoleHost: String? = "console.lan",
    ) = MonitorViewModel(
        FakeCameraStore(cameras),
        MutableCredentials(consoleHost),
        ioDispatcher = mainDispatcher.dispatcher,
    )

    @Test
    fun `the viewer shows only the switched-on cameras`() = runTest {
        val model = viewModel(
            listOf(
                Camera("a", "Nursery", "rtsp://cam:7447/a"),
                Camera("b", "Play room", "rtsp://cam:7447/b", enabled = false),
            ),
        )
        runCurrent()

        assertEquals(listOf("a"), model.cameras.value.map { it.id })
    }

    @Test
    fun `every camera switched off is distinguished from having none`() = runTest {
        val model = viewModel(
            listOf(Camera("a", "Nursery", "rtsp://cam:7447/a", enabled = false)),
        )
        runCurrent()

        // The two need different empty states: one sends you to settings, the
        // other to console setup.
        assertTrue(model.cameras.value.isEmpty())
        assertTrue(model.hasDisabledOnly.value)
    }

    @Test
    fun `having no cameras at all is not reported as all-disabled`() = runTest {
        val model = viewModel(emptyList())
        runCurrent()

        assertFalse(model.hasDisabledOnly.value)
    }

    @Test
    fun `a protect camera resolves to the livestream transport`() = runTest {
        val model = viewModel(
            listOf(
                Camera(
                    id = "a",
                    name = "Nursery",
                    url = "rtsp://cam:7447/a",
                    protect = ProtectStream("cam1", 1, consoleHost = "console.lan"),
                ),
            ),
        )
        runCurrent()

        // RTSP cannot depayload AV1; this is the only transport that can.
        assertEquals(StreamSource.Livestream("cam1", 1), model.sources.value["a"])
    }

    @Test
    fun `a camera from another console falls back to its own rtsp url`() = runTest {
        val model = viewModel(
            listOf(
                Camera(
                    id = "a",
                    name = "Nursery",
                    url = "rtsp://cam:7447/a",
                    protect = ProtectStream("cam1", 1, consoleHost = "old-console.lan"),
                ),
            ),
            consoleHost = "new-console.lan",
        )
        runCurrent()

        assertEquals(StreamSource.Rtsp("rtsp://cam:7447/a"), model.sources.value["a"])
    }

    @Test
    fun `signing in to another console re-resolves the transport`() = runTest {
        val credentials = MutableCredentials("console.lan")
        val model = MonitorViewModel(
            FakeCameraStore(
                listOf(
                    Camera(
                        id = "a",
                        name = "Nursery",
                        url = "rtsp://cam:7447/a",
                        protect = ProtectStream("cam1", 1, consoleHost = "console.lan"),
                    ),
                ),
            ),
            credentials,
            ioDispatcher = mainDispatcher.dispatcher,
        )
        runCurrent()
        assertEquals(StreamSource.Livestream("cam1", 1), model.sources.value["a"])

        // Onboarding replaced the credentials without touching the camera list.
        credentials.host = "other-console.lan"
        model.refreshSources()
        runCurrent()

        // Negotiating cam1 against a console that never issued it would fail
        // every reconnect; its own RTSP URL still works.
        assertEquals(StreamSource.Rtsp("rtsp://cam:7447/a"), model.sources.value["a"])
    }

    @Test
    fun `a camera that cannot be listened to is called out`() = runTest {
        val model = viewModel(
            listOf(
                Camera("a", "Nursery", "rtsp://cam:7447/a"),
                Camera("b", "Stale", "rtsps://cam:7441/b"),
            ),
        )
        runCurrent()

        assertEquals(listOf("b"), model.unmonitorable.value.map { it.id })
    }






}

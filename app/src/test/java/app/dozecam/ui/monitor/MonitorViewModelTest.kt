package app.dozecam.ui.monitor

import app.dozecam.MainDispatcherRule
import app.dozecam.data.Camera
import app.dozecam.data.CameraStore
import app.dozecam.data.DetectorSettings
import app.dozecam.data.DetectorSettingsStore
import app.dozecam.data.ProtectStream
import app.dozecam.monitoring.CameraMonitorState
import app.dozecam.monitoring.MonitoringState
import app.dozecam.player.ConnectionState
import app.dozecam.player.StreamSource
import app.dozecam.protect.CredentialsStore
import app.dozecam.protect.ProtectCredentials
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.emptyFlow
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

    private class FakeDetectorSettings : DetectorSettingsStore {
        val state = MutableStateFlow(DetectorSettings())
        override val settings: Flow<DetectorSettings> = state
        override suspend fun update(transform: (DetectorSettings) -> DetectorSettings) {
            state.value = transform(state.value)
        }
    }

    /** A camera whose monitor is decoding audio — the only state with a meter. */
    private fun live(id: String, name: String, level: Float) =
        CameraMonitorState(id, name, level = level, connection = ConnectionState.Live)

    private fun viewModel(
        cameras: List<Camera>,
        consoleHost: String? = "console.lan",
        monitoringState: MonitoringState = MonitoringState(),
        detectorSettings: FakeDetectorSettings = FakeDetectorSettings(),
    ) = MonitorViewModel(
        FakeCameraStore(cameras),
        MutableCredentials(consoleHost),
        monitoringState,
        detectorSettings,
        emptyFlow(),
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
            MonitoringState(),
            FakeDetectorSettings(),
            emptyFlow(),
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

    @Test
    fun `an rtsps camera Protect can carry is no longer called out`() = runTest {
        val model = viewModel(
            listOf(
                Camera(
                    id = "a",
                    name = "Nursery",
                    url = "rtsps://cam:7441/a",
                    protect = ProtectStream("cam1", 1, "console.lan"),
                ),
            ),
        )
        runCurrent()

        // The URL alone still says "cannot be monitored" — Media3 has no RTSP
        // TLS — but the livestream carries this camera's audio perfectly well,
        // so warning about it would now be the lie.
        assertEquals(emptyList<String>(), model.unmonitorable.value.map { it.id })
    }

    @Test
    fun `the viewer follows whether the monitor is actually listening`() = runTest {
        val monitoring = MonitoringState()
        val model = viewModel(
            listOf(Camera("a", "Nursery", "rtsp://cam:7447/a")),
            monitoringState = monitoring,
        )
        runCurrent()
        assertFalse(model.monitoringRunning.value)

        monitoring.serviceRunning.value = true

        assertTrue(model.monitoringRunning.value)
    }

    @Test
    fun `a camera that can be heard is a reason to offer to start`() = runTest {
        val model = viewModel(listOf(Camera("a", "Nursery", "rtsp://cam:7447/a")))
        runCurrent()

        assertTrue(model.canMonitor.value)
    }

    /**
     * The mirror of [unmonitorable]: offering to start a monitor that would
     * have nothing to listen to is the same lie the notice exists to prevent,
     * told as a button instead.
     */
    @Test
    fun `nothing listenable is nothing to offer`() = runTest {
        val model = viewModel(
            listOf(Camera("a", "Nursery", "rtsps://cam:7441/a")),
            consoleHost = null,
        )
        runCurrent()

        assertEquals(listOf("a"), model.unmonitorable.value.map { it.id })
        assertFalse(model.canMonitor.value)
    }

    @Test
    fun `each monitored camera reports its own level`() = runTest {
        val monitoring = MonitoringState()
        val model = viewModel(
            listOf(
                Camera("a", "Nursery", "rtsp://cam:7447/a"),
                Camera("b", "Play room", "rtsp://cam:7447/b"),
            ),
            monitoringState = monitoring,
        )
        runCurrent()

        monitoring.put(live("a", "Nursery", level = 0.3f))
        monitoring.put(live("b", "Play room", level = 0.05f))
        runCurrent()

        // Per camera, not the peak: the meter's job on a tile is to say which
        // room the noise is in, which an aggregate cannot.
        assertEquals(mapOf("a" to 0.3f, "b" to 0.05f), model.audioLevels.value)
    }

    @Test
    fun `rounding never carries a quiet level over the threshold`() = runTest {
        val monitoring = MonitoringState()
        val model = viewModel(
            listOf(Camera("a", "Nursery", "rtsp://cam:7447/a")),
            monitoringState = monitoring,
        )
        runCurrent()

        // 0.096 rounds to 0.10 — the default threshold. Shown rounded, the
        // meter would swell as triggered while the detector says quiet.
        monitoring.put(live("a", "Nursery", level = 0.096f))
        runCurrent()

        assertEquals(mapOf("a" to 0.096f), model.audioLevels.value)
    }

    @Test
    fun `a monitor that is not live reports no level`() = runTest {
        val monitoring = MonitoringState()
        val model = viewModel(
            listOf(
                Camera("a", "Nursery", "rtsp://cam:7447/a"),
                Camera("b", "Play room", "rtsp://cam:7447/b"),
            ),
            monitoringState = monitoring,
        )
        runCurrent()

        // Still connecting: the zero it holds was never measured. Reconnecting:
        // the level it holds is from before the stream dropped. Either shown
        // would be a meter lying about a room nobody can hear.
        monitoring.put(CameraMonitorState("a", "Nursery"))
        monitoring.put(
            live("b", "Play room", level = 0.3f)
                .withConnection(ConnectionState.Reconnecting(attempt = 1)),
        )
        runCurrent()

        assertEquals(emptyMap<String, Float>(), model.audioLevels.value)
    }

    @Test
    fun `a live connection that has decoded nothing reports no level`() = runTest {
        val monitoring = MonitoringState()
        val model = viewModel(
            listOf(Camera("a", "Nursery", "rtsp://cam:7447/a")),
            monitoringState = monitoring,
        )
        runCurrent()

        // The player's clock reached Live before its first PCM buffer did. A
        // meter here would show a zero nobody measured.
        monitoring.put(CameraMonitorState("a", "Nursery").withConnection(ConnectionState.Live))
        runCurrent()

        assertEquals(emptyMap<String, Float>(), model.audioLevels.value)
    }

    @Test
    fun `noise-floor jitter collapses to one level`() = runTest {
        val monitoring = MonitoringState()
        val model = viewModel(
            listOf(Camera("a", "Nursery", "rtsp://cam:7447/a")),
            monitoringState = monitoring,
        )
        runCurrent()

        // A quiet room reports a slightly different RMS on every buffer; the
        // viewer must see one value, not a recomposition per buffer all night.
        monitoring.put(live("a", "Nursery", level = 0.0503f))
        runCurrent()
        val first = model.audioLevels.value
        monitoring.update("a") { it.copy(level = 0.0497f) }
        runCurrent()

        assertEquals(mapOf("a" to 0.05f), first)
        assertEquals(first, model.audioLevels.value)
    }

    @Test
    fun `a camera the monitor stops listening to loses its level`() = runTest {
        val monitoring = MonitoringState()
        val model = viewModel(
            listOf(Camera("a", "Nursery", "rtsp://cam:7447/a")),
            monitoringState = monitoring,
        )
        monitoring.put(live("a", "Nursery", level = 0.3f))
        runCurrent()

        monitoring.remove("a")
        runCurrent()

        // Gone, not zero: a level of 0 would claim the room is quiet when the
        // truth is nobody is checking.
        assertEquals(emptyMap<String, Float>(), model.audioLevels.value)
    }

    @Test
    fun `the meters mark the same threshold the detector uses`() = runTest {
        val detector = FakeDetectorSettings()
        val model = viewModel(
            listOf(Camera("a", "Nursery", "rtsp://cam:7447/a")),
            detectorSettings = detector,
        )
        runCurrent()
        assertEquals(DetectorSettings().threshold, model.audioThreshold.value)

        detector.state.value = DetectorSettings(threshold = 0.42f)
        runCurrent()

        assertEquals(0.42f, model.audioThreshold.value)
    }

    @Test
    fun `one listenable camera among several is enough`() = runTest {
        val model = viewModel(
            listOf(
                Camera("a", "Nursery", "rtsps://cam:7441/a"),
                Camera("b", "Play room", "rtsp://cam:7447/b"),
            ),
            consoleHost = null,
        )
        runCurrent()

        assertTrue(model.canMonitor.value)
    }



}

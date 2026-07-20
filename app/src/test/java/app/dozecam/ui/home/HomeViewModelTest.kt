package app.dozecam.ui.home

import app.dozecam.MainDispatcherRule
import app.dozecam.data.DetectorSettings
import app.dozecam.data.DetectorSettingsStore
import app.dozecam.data.StreamSettings
import app.dozecam.monitoring.MonitoringState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

private class FakeStreamSettings(initial: String = "") : StreamSettings {
    val stored = MutableStateFlow(initial)
    override val streamUrl: Flow<String> = stored
    override suspend fun setStreamUrl(url: String) {
        stored.value = url
    }
}

private class FakeDetectorSettings : DetectorSettingsStore {
    val stored = MutableStateFlow(DetectorSettings())
    override val settings: Flow<DetectorSettings> = stored
    override suspend fun update(settings: DetectorSettings) {
        stored.value = settings
    }
}

class HomeViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun viewModel(
        streamSettings: FakeStreamSettings = FakeStreamSettings(),
        detectorSettings: FakeDetectorSettings = FakeDetectorSettings(),
        monitoringState: MonitoringState = MonitoringState(),
    ) = HomeViewModel(streamSettings, detectorSettings, monitoringState)

    @Test
    fun `loads the saved url into the input field`() = runTest {
        val settings = FakeStreamSettings("rtsp://192.168.1.1:7447/token")
        val viewModel = viewModel(settings)

        assertEquals("rtsp://192.168.1.1:7447/token", viewModel.urlInput.value)
        assertTrue(viewModel.canWatch.value)
    }

    @Test
    fun `keeps user input over a slower saved-url load`() = runTest {
        val settings = FakeStreamSettings("rtsp://old:7447/a")
        val viewModel = viewModel(settings)
        viewModel.onUrlChange("rtsp://new:7447/b")

        assertEquals("rtsp://new:7447/b", viewModel.urlInput.value)
    }

    @Test
    fun `canWatch tracks input validity`() = runTest {
        val viewModel = viewModel()

        assertFalse(viewModel.canWatch.value)
        viewModel.onUrlChange("rtsp://192.168.1.1:7447/token")
        assertTrue(viewModel.canWatch.value)
        viewModel.onUrlChange("http://nope")
        assertFalse(viewModel.canWatch.value)
    }

    @Test
    fun `commitUrl persists the trimmed url and returns it`() = runTest {
        val settings = FakeStreamSettings()
        val viewModel = viewModel(settings)
        viewModel.onUrlChange("  rtsp://192.168.1.1:7447/token ")

        val committed = viewModel.commitUrl()

        assertEquals("rtsp://192.168.1.1:7447/token", committed)
        assertEquals("rtsp://192.168.1.1:7447/token", settings.stored.value)
    }

    @Test
    fun `detector settings load and persist through the view model`() = runTest {
        val detectorSettings = FakeDetectorSettings()
        val viewModel = viewModel(detectorSettings = detectorSettings)

        assertEquals(DetectorSettings(), viewModel.detector.value)

        val custom = DetectorSettings(threshold = 0.3f, sustainMs = 2_000, quietMs = 15_000)
        viewModel.onDetectorChange(custom)

        assertEquals(custom, detectorSettings.stored.value)
        assertEquals(custom, viewModel.detector.value)
    }

    @Test
    fun `monitoring state flows straight through`() = runTest {
        val monitoringState = MonitoringState()
        val viewModel = viewModel(monitoringState = monitoringState)

        assertFalse(viewModel.monitoringRunning.value)
        monitoringState.serviceRunning.value = true
        monitoringState.audioLevel.value = 0.42f

        assertTrue(viewModel.monitoringRunning.value)
        assertEquals(0.42f, viewModel.audioLevel.value)
    }
}

package app.dozecam.ui.home

import app.dozecam.MainDispatcherRule
import app.dozecam.data.StreamSettings
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

class HomeViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `loads the saved url into the input field`() = runTest {
        val settings = FakeStreamSettings("rtsp://192.168.1.1:7447/token")
        val viewModel = HomeViewModel(settings)

        assertEquals("rtsp://192.168.1.1:7447/token", viewModel.urlInput.value)
        assertTrue(viewModel.canWatch.value)
    }

    @Test
    fun `keeps user input over a slower saved-url load`() = runTest {
        val settings = FakeStreamSettings("rtsp://old:7447/a")
        val viewModel = HomeViewModel(settings)
        viewModel.onUrlChange("rtsp://new:7447/b")

        assertEquals("rtsp://new:7447/b", viewModel.urlInput.value)
    }

    @Test
    fun `canWatch tracks input validity`() = runTest {
        val viewModel = HomeViewModel(FakeStreamSettings())

        assertFalse(viewModel.canWatch.value)
        viewModel.onUrlChange("rtsp://192.168.1.1:7447/token")
        assertTrue(viewModel.canWatch.value)
        viewModel.onUrlChange("http://nope")
        assertFalse(viewModel.canWatch.value)
    }

    @Test
    fun `commitUrl persists the trimmed url and returns it`() = runTest {
        val settings = FakeStreamSettings()
        val viewModel = HomeViewModel(settings)
        viewModel.onUrlChange("  rtsp://192.168.1.1:7447/token ")

        val committed = viewModel.commitUrl()

        assertEquals("rtsp://192.168.1.1:7447/token", committed)
        assertEquals("rtsp://192.168.1.1:7447/token", settings.stored.value)
    }
}

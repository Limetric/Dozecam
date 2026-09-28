package app.dozecam.monitoring

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.dozecam.MainDispatcherRule
import app.dozecam.appContainer
import app.dozecam.data.Camera
import app.dozecam.data.DetectorSettings
import app.dozecam.player.StreamSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The audio monitor's one audible degree of freedom.
 *
 * Everything else this class does needs a live stream to say anything about,
 * and is covered by [MonitorPlanTest] and the player tests instead. The volume
 * is different: it is the whole of listen mode, and it is decided before a
 * single byte has arrived.
 */
@RunWith(RobolectricTestRunner::class)
class CameraAudioMonitorTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(Dispatchers.Main)
    private val monitors = mutableListOf<CameraAudioMonitor>()

    @After
    fun tearDown() {
        monitors.forEach { it.stop() }
        scope.cancel()
    }

    /**
     * An address nothing answers on. The monitor's job here is settled before
     * any of it connects, and a stream that never arrives leaves the watchdog
     * doing the only other thing it knows — waiting.
     */
    private fun monitor(id: String): CameraAudioMonitor = CameraAudioMonitor(
        context = context,
        camera = Camera(id, "Nursery", "rtsp://127.0.0.1:1/$id"),
        transports = listOf(StreamSource.Rtsp("rtsp://127.0.0.1:1/$id")),
        // Never negotiated: the transport above is plain RTSP.
        livestreamProvider = context.appContainer.protectLivestream,
        scope = scope,
        detectorSettings = DetectorSettings(),
        onLevel = { _, _ -> },
        onPhase = {},
        onConnection = {},
        onTrigger = {},
    ).also { monitors += it }

    @Test
    fun `a monitor measures a room without being heard`() {
        val monitor = monitor("a").apply { start() }

        // Every camera is decoded all night; being audible is something exactly
        // one of them is ever asked for.
        assertEquals(0f, monitor.playerVolume)
    }

    @Test
    fun `the target camera's volume follows the setting`() {
        val monitor = monitor("a").apply { start() }

        monitor.setAudible(true)
        assertEquals(1f, monitor.playerVolume)

        // Switched off, lost to a call, or handed back to the viewer — the
        // speaker closes on the spot rather than at the next reconnect.
        monitor.setAudible(false)
        assertEquals(0f, monitor.playerVolume)
    }

    @Test
    fun `a monitor asked to play aloud before it starts comes up audible`() {
        // The order the service reconciles in: a camera can be told it is the
        // listen target while its monitor is still being built.
        val monitor = monitor("a")
        monitor.setAudible(true)

        monitor.start()

        // Otherwise a monitor rebuilt onto another transport would come back
        // silent mid-night with the switch still on.
        assertEquals(1f, monitor.playerVolume)
    }
}

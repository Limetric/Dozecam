package app.dozecam.player

import android.view.ViewGroup
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CameraStreamTest {

    private class RecordingController : VideoPlayerController {
        override var listener: ((PlayerEvent) -> Unit)? = null
        override fun attach(container: ViewGroup) = Unit
        override fun detach() = Unit
        override fun play(source: StreamSource) = Unit
        override fun setMuted(muted: Boolean) = Unit
        override fun setVideoEnabled(enabled: Boolean) = Unit
        override fun stop() = Unit
        override fun release() = Unit
    }

    @Test
    fun `the picture's shape reaches the session without dulling the watchdog`() = runTest {
        val controller = RecordingController()
        val stream = CameraStream(
            source = StreamSource.Rtsp("rtsp://cam:7447/a"),
            controller = controller,
            scope = this,
        )
        stream.start(networkOnline = true)

        assertEquals(null, stream.videoAspect.value)
        controller.listener?.invoke(PlayerEvent.VideoAspect(16f / 9f))
        // The shape is kept for whoever draws the picture...
        assertEquals(16f / 9f, stream.videoAspect.value!!, 0.001f)

        // ...while liveness still flows: a frame after the shape event must
        // reach the watchdog exactly as it did before shapes existed.
        controller.listener?.invoke(PlayerEvent.TimeChanged(1_000))
        testScheduler.runCurrent()
        assertEquals(ConnectionState.Live, stream.connection.value)

        stream.release()
    }
}

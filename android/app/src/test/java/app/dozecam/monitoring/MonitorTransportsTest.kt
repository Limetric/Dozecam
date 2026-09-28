package app.dozecam.monitoring

import app.dozecam.data.Camera
import app.dozecam.player.StreamSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MonitorTransportsTest {

    private val rtspUrl = "rtsp://console:7447/abc"
    private val host = "console.lan"
    private val livestream = StreamSource.Livestream("cam-1", 1)

    private fun camera(url: String = rtspUrl) = Camera("a", "Nursery", url)

    @Test
    fun `a plain RTSP camera is listened to over RTSP and nothing else`() {
        val transports = MonitorTransports.of(camera(), StreamSource.Rtsp(rtspUrl), host)

        assertEquals(listOf(StreamSource.Rtsp(rtspUrl)), transports)
    }

    @Test
    fun `a Protect camera keeps RTSP first and the livestream in reserve`() {
        val transports = MonitorTransports.of(camera(), livestream, host)

        // RTSP asks for the audio track alone. The livestream carries the
        // camera's video whether or not anything looks at it, so it is what to
        // fall back to, not what to start with.
        assertEquals(listOf(StreamSource.Rtsp(rtspUrl), livestream), transports)
    }

    @Test
    fun `an rtsps camera is monitorable after all when Protect can carry it`() {
        // Media3 has no RTSP TLS, so this used to be a camera the monitor had
        // to skip — watchable but not listenable.
        val transports =
            MonitorTransports.of(camera("rtsps://console:7441/abc"), livestream, host)

        assertEquals(listOf(livestream), transports)
    }

    @Test
    fun `an rtsps camera with no console behind it cannot be listened to at all`() {
        val camera = camera("rtsps://console:7441/abc")

        val transports = MonitorTransports.of(camera, StreamSource.Rtsp(camera.url), host)

        // Empty is the honest answer; the caller says so rather than leaving a
        // room quietly uncovered.
        assertTrue(transports.isEmpty())
    }

    @Test
    fun `a livestream is not offered while nobody is signed in`() {
        // A camera stored before the console host was recorded still resolves
        // to a livestream identity, but negotiating one without a sign-in can
        // only ever throw — and would count the camera as monitored while it
        // failed, which is the lie the notice exists to prevent.
        val transports =
            MonitorTransports.of(camera("rtsps://console:7441/abc"), livestream, null)

        assertTrue(transports.isEmpty())
    }
}

class TransportFallbackTest {

    @Test
    fun `a transport is given several restarts before being abandoned`() {
        val fallback = TransportFallback(transportCount = 2, restartsBeforeFallback = 3)

        assertFalse(fallback.onRestart())
        assertFalse(fallback.onRestart())
        assertEquals(0, fallback.index)

        assertTrue(fallback.onRestart())
        assertEquals(1, fallback.index)
    }

    @Test
    fun `restarts are counted here rather than read off the watchdog`() {
        val fallback = TransportFallback(transportCount = 2, restartsBeforeFallback = 3)

        // The failure this exists for is a session that reaches "playing" and
        // only then fails to decode: the watchdog counts that as a recovery and
        // resets its attempt number every time round, so anything keyed on that
        // number would never climb and the camera would stay uncovered forever.
        repeat(3) { fallback.onRestart() }

        assertEquals(1, fallback.index)
    }

    @Test
    fun `a transport that has ever decoded is kept through any later trouble`() {
        val fallback = TransportFallback(transportCount = 2, restartsBeforeFallback = 3)
        fallback.onAudioDecoded()

        repeat(20) { assertFalse(fallback.onRestart()) }

        // By now the trouble really is the network, and the other transport
        // would fare no better.
        assertEquals(0, fallback.index)
    }

    @Test
    fun `a lone transport is never abandoned, because there is nowhere to go`() {
        val fallback = TransportFallback(transportCount = 1, restartsBeforeFallback = 3)

        repeat(10) { assertFalse(fallback.onRestart()) }
        assertEquals(0, fallback.index)
    }

    @Test
    fun `a fallback that is no better itself hands the turn back`() {
        val fallback = TransportFallback(transportCount = 2, restartsBeforeFallback = 3)

        repeat(3) { fallback.onRestart() }
        assertEquals(1, fallback.index)

        // The fallback can be just as unusable as what it replaced — stale
        // credentials, a console that will not serve a livestream. Stopping
        // here would pin the camera to it for good while the stream it started
        // on came back to life unnoticed.
        repeat(3) { fallback.onRestart() }
        assertEquals(0, fallback.index)
    }

    @Test
    fun `each transport gets its own run of restarts rather than the tail of the last`() {
        val fallback = TransportFallback(transportCount = 3, restartsBeforeFallback = 3)

        repeat(3) { fallback.onRestart() }
        assertEquals(1, fallback.index)

        // Without rebasing the count, the second transport would be abandoned
        // on its first failure and the third never tried properly either.
        assertFalse(fallback.onRestart())
        assertFalse(fallback.onRestart())
        assertTrue(fallback.onRestart())
        assertEquals(2, fallback.index)

        repeat(3) { fallback.onRestart() }
        assertEquals(0, fallback.index)
    }
}

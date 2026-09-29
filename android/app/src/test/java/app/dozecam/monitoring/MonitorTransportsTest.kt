package app.dozecam.monitoring

import app.dozecam.data.Camera
import app.dozecam.player.StreamSource
import app.dozecam.testing.Fixtures
import kotlinx.serialization.Serializable
import org.junit.Assert.assertEquals
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

/**
 * The fallback rules themselves live in `shared/fixtures/transport-fallback`,
 * which the iOS monitor is held to as well.
 */
class TransportFallbackTest {

    @Serializable
    private data class Table(val restartsBeforeFallback: Int, val cases: List<Case>)

    @Serializable
    private data class Case(val name: String, val transportCount: Int, val steps: List<Step>)

    @Serializable
    private data class Step(
        val event: String,
        val times: Int = 1,
        val movesOn: Boolean? = null,
        val index: Int? = null,
    )

    private val table = Fixtures.decode<Table>("transport-fallback/fallback.json")

    /** Plays the fixture case called [name] against a fresh fallback. */
    private fun play(name: String) {
        val case = table.cases.singleOrNull { it.name == name } ?: error("no fixture case \"$name\"")
        val fallback = TransportFallback(case.transportCount, table.restartsBeforeFallback)
        case.steps.forEachIndexed { i, step ->
            val where = "${case.name}, step ${i + 1}"
            repeat(step.times) { n ->
                when (step.event) {
                    "restart" -> {
                        val movedOn = fallback.onRestart()
                        step.movesOn?.let { assertEquals("$where: restart ${n + 1} moved on", it, movedOn) }
                    }
                    "audioDecoded" -> fallback.onAudioDecoded()
                    else -> error("$where: unknown event ${step.event}")
                }
            }
            step.index?.let { assertEquals("$where: index", it, fallback.index) }
        }
    }

    @Test
    fun `a transport is given several restarts before being abandoned`() {
        play("a transport is given several restarts before being abandoned")
    }

    @Test
    fun `restarts are counted here rather than read off the watchdog`() {
        // The failure this exists for is a session that reaches "playing" and
        // only then fails to decode: the watchdog counts that as a recovery and
        // resets its attempt number every time round, so anything keyed on that
        // number would never climb and the camera would stay uncovered forever.
        play("restarts are counted here rather than read off the watchdog")
    }

    @Test
    fun `a transport that has ever decoded is kept through any later trouble`() {
        // By the twentieth restart the trouble really is the network, and the
        // other transport would fare no better.
        play("a transport that has ever decoded is kept through any later trouble")
    }

    @Test
    fun `a lone transport is never abandoned, because there is nowhere to go`() {
        play("a lone transport is never abandoned, because there is nowhere to go")
    }

    @Test
    fun `a fallback that is no better itself hands the turn back`() {
        // The fallback can be just as unusable as what it replaced — stale
        // credentials, a console that will not serve a livestream. Stopping
        // there would pin the camera to it for good while the stream it started
        // on came back to life unnoticed.
        play("a fallback that is no better itself hands the turn back")
    }

    @Test
    fun `each transport gets its own run of restarts rather than the tail of the last`() {
        // Without rebasing the count, the second transport would be abandoned
        // on its first failure and the third never tried properly either.
        play("each transport gets its own run of restarts rather than the tail of the last")
    }
}

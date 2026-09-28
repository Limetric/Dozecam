package app.dozecam.monitoring

import app.dozecam.data.Camera
import app.dozecam.data.StreamUrlValidator
import app.dozecam.player.StreamSource
import app.dozecam.protect.CredentialsStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Where a camera's audio can be listened to, best first.
 *
 * RTSP leads wherever it works. The monitor asks the console for the audio
 * track alone, so it costs a few kilobits a second and can run all night on
 * battery. The livestream carries the camera's video whether or not anything
 * ever looks at it, which is why it follows rather than leads.
 *
 * But it does carry every codec, and Media3's RTSP stack does not: its Opus
 * depayloader insists on the RFC 7845 header pair in-band, which an RTP sender
 * has no reason to send and Protect does not, so an Opus camera fails on the
 * first packet and every packet after it. For those cameras the livestream is
 * the difference between monitoring a room and only appearing to. Which is
 * also why an rtsps-only camera — no plain RTSP to fall back to, and Media3
 * has no RTSP TLS — is monitorable at all now, where before it was listed as a
 * camera the monitor had to skip.
 */
internal object MonitorTransports {

    /**
     * [source] is what the viewer resolved for this camera: a livestream
     * identity only exists for a Protect camera whose console is the one
     * currently signed in.
     *
     * Empty means there is no way to listen to this camera at all, which the
     * caller must report rather than quietly leave a room uncovered.
     */
    fun of(camera: Camera, source: StreamSource, consoleHost: String?): List<StreamSource> =
        buildList {
            if (StreamUrlValidator.isMonitorable(camera.url)) add(StreamSource.Rtsp(camera.url))
            // A livestream is negotiated against a signed-in console. Without
            // one the provider throws on every single attempt, and a transport
            // that can only fail is not a transport, it is a loop — one that
            // would also count this camera as monitored and silence the notice
            // saying otherwise. Cameras stored before the console host was
            // recorded, and the legacy `protect-<id>-<channel>` ids, both reach
            // here with nothing to check against, so this is the check.
            if (source is StreamSource.Livestream && consoleHost != null) add(source)
        }
}

/**
 * The transports for each of [cameras] that can be listened to at all, keyed by
 * camera id; cameras with no way in are absent.
 *
 * One place for the console read, because every gate that decides whether
 * monitoring is worth arming has to reach the same answer as the service that
 * carries it out — a switch greyed out over a camera the service would happily
 * listen to is the same bug as the reverse.
 *
 * The credentials are only decrypted when some camera actually has a console
 * identity to check against.
 */
internal suspend fun transportsFor(
    cameras: List<Camera>,
    credentials: CredentialsStore,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
): Map<String, List<StreamSource>> {
    val host = consoleHostFor(cameras, credentials, ioDispatcher)
    return cameras
        .associate { it.id to MonitorTransports.of(it, StreamSource.of(it, host), host) }
        .filterValues { it.isNotEmpty() }
}

/**
 * The signed-in console, read only when some camera's answer could depend on
 * it. Decrypting the keystore to resolve a list of plain RTSP cameras would buy
 * nothing — and would make the service's first pass asynchronous, which it
 * holds a wake lock across.
 */
internal suspend fun consoleHostFor(
    cameras: List<Camera>,
    credentials: CredentialsStore,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
): String? {
    // Two ways to be a console camera: a recorded identity, or the id shape
    // onboarding used before there was one. Neither is worth a keystore read on
    // its own, but between them they are every camera the host can change the
    // answer for.
    val mayNeedConsole = cameras.any {
        it.protect != null || StreamSource.of(it, null) is StreamSource.Livestream
    }
    return if (mayNeedConsole) withContext(ioDispatcher) { credentials.load()?.host } else null
}

/** The subset of [cameras] there is some way to listen to. */
internal suspend fun monitorable(
    cameras: List<Camera>,
    credentials: CredentialsStore,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
): List<Camera> {
    val usable = transportsFor(cameras, credentials, ioDispatcher)
    return cameras.filter { it.id in usable }
}

/**
 * Decides when a monitor should stop trying a transport and take the next one.
 *
 * A stream that cannot be decoded does not say so — it looks exactly like a
 * camera in a quiet room, and the reconnect loop it provokes looks exactly like
 * a flaky network. The one signal that separates them is whether a single audio
 * buffer has ever arrived: if none has after several restarts, no number of
 * further restarts will change that.
 *
 * Restarts are counted here rather than read off the watchdog's attempt number,
 * which is the whole point: a session that reaches "playing" and only then
 * fails to decode resets that number every time round, so it never climbs and
 * the camera stays uncovered forever. Which is exactly the shape of the bug
 * this exists to escape.
 *
 * Transports are taken in turn and then come round again, because a fallback
 * can be just as unusable as what it replaced — stale credentials, a console
 * that will not serve a livestream — and stopping at the last one would pin a
 * camera to it forever while the stream it started on quietly recovered.
 *
 * The cycling stops the moment anything decodes. That transport is then kept
 * through any later trouble, because by then the trouble really is the network,
 * and the others would fare no better.
 */
internal class TransportFallback(
    private val transportCount: Int,
    private val restartsBeforeFallback: Int = RESTARTS_BEFORE_FALLBACK,
) {
    var index = 0
        private set

    private var decoded = false
    private var failedRestarts = 0

    /** Audio arrived: this transport works, and is now kept for good. */
    fun onAudioDecoded() {
        decoded = true
    }

    /** Called as a restart is made. Returns true when [index] has moved on. */
    fun onRestart(): Boolean {
        if (decoded) return false
        failedRestarts++
        if (failedRestarts < restartsBeforeFallback) return false
        if (transportCount <= 1) return false // nowhere else to go
        index = (index + 1) % transportCount
        failedRestarts = 0
        return true
    }

    private companion object {
        /**
         * Enough restarts to rule out a console that was merely busy or a
         * network that blinked, few enough that a room is not left uncovered
         * for long. The watchdog's backoff caps at 4 s, so this is seconds.
         */
        const val RESTARTS_BEFORE_FALLBACK = 3
    }
}

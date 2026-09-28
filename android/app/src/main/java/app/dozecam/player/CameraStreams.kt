package app.dozecam.player

import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * One camera's live session, kept deliberately apart from whatever is showing
 * it. A tile borrows a stream for as long as it is on screen; the stream
 * outlives the tile, which is the whole point — it lets a camera move between
 * the grid and a screen of its own without negotiating the stream again.
 */
class CameraStream internal constructor(
    val source: StreamSource,
    private val controller: VideoPlayerController,
    scope: CoroutineScope,
    config: PlaybackWatchdog.Config = PlaybackWatchdog.Config(),
) {
    private val watchdog = PlaybackWatchdog(
        scope = scope,
        onReconnect = { controller.play(source) },
        config = config,
    )

    val connection = watchdog.state
    val lastFrameAtMs = watchdog.lastFrameAtMs

    private val _videoAspect = MutableStateFlow<Float?>(null)

    /**
     * The picture's width over height, or null until the first video output
     * says. Kept on the session rather than per tile for the same reason the
     * connection state is: a camera opened from the grid already knows its
     * shape, and must not forget it in the handover.
     */
    val videoAspect: StateFlow<Float?> = _videoAspect

    private var host: ViewGroup? = null
    private var muted = true
    private var videoEnabled = true

    /**
     * Starts silent, always. Whichever tile is entitled to be heard says so
     * once it is up; setting the volume after [VideoPlayerController.play]
     * would still let a camera joining the grid blurt out a burst of room audio
     * before anything could silence it.
     */
    internal fun start(networkOnline: Boolean) {
        controller.listener = { event ->
            if (event is PlayerEvent.VideoAspect) _videoAspect.value = event.ratio
            watchdog.onPlayerEvent(event)
        }
        controller.setMuted(true)
        watchdog.start()
        if (!networkOnline) watchdog.onNetworkLost()
        controller.play(source)
    }

    /**
     * Hands this camera's picture to [host]. Attaching is allowed to follow an
     * attach: a camera being promoted is claimed by its new tile and given up
     * by the old one in whichever order Compose applies the two.
     */
    fun attach(host: ViewGroup) {
        if (this.host === host) return
        this.host = host
        controller.attach(host)
    }

    /**
     * Ignored unless [host] is the view actually holding the picture, so a tile
     * being disposed after its replacement has already taken over cannot pull
     * the camera off the screen it just arrived on.
     */
    fun detach(host: ViewGroup) {
        if (this.host !== host) return
        this.host = null
        controller.detach()
    }

    fun setMuted(muted: Boolean) {
        if (this.muted == muted) return
        this.muted = muted
        controller.setMuted(muted)
    }

    internal fun setVideoEnabled(enabled: Boolean) {
        if (videoEnabled == enabled) return
        videoEnabled = enabled
        controller.setVideoEnabled(enabled)
        if (enabled) watchdog.onVideoEnabled() else watchdog.onVideoDisabled()
    }

    internal fun onNetworkAvailable() = watchdog.onNetworkAvailable()

    internal fun onNetworkLost() = watchdog.onNetworkLost()

    internal fun release() {
        watchdog.stop()
        controller.listener = null
        controller.stop()
        controller.detach()
        controller.release()
        host = null
    }
}

/**
 * Every camera session the viewer currently holds, and the one place that
 * decides which cameras deserve one.
 *
 * Tiles claim a camera for as long as they are on screen. The screen above them
 * separately names cameras to [keepWarm] — those whose tiles are gone because a
 * single camera was opened, not because the user navigated away from them.
 * A warm camera keeps its session and drops its video track; it costs a socket
 * rather than a decoder, and returning to the grid costs a keyframe rather than
 * a fresh negotiation.
 *
 * Warmth never conjures a session: it only spares one that already exists.
 */
@Stable
class CameraStreams internal constructor(
    private val controllerFactory: (StreamSource) -> VideoPlayerController,
    private val watchdogConfig: PlaybackWatchdog.Config,
) {
    /**
     * Keyed by the tile holding the claim rather than by the camera, so a
     * camera being handed from one tile to another survives however Compose
     * orders the two. Keyed by camera, the same handover would net to nothing
     * whenever the arriving tile claimed before the leaving one let go — and
     * release the very session being promoted.
     */
    private val claims = mutableStateMapOf<Any, Pair<String, StreamSource>>()
    private val streams = mutableStateMapOf<String, CameraStream>()
    private var warm by mutableStateOf<Set<String>>(emptySet())
    private var online = true

    /** The session showing [cameraId], or null until one has been built. */
    operator fun get(cameraId: String): CameraStream? = streams[cameraId]

    /** [tile] identifies the holder; pass the same value back to [unclaim]. */
    fun claim(tile: Any, cameraId: String, source: StreamSource) {
        claims[tile] = cameraId to source
    }

    fun unclaim(tile: Any) {
        claims.remove(tile)
    }

    fun keepWarm(cameraIds: Set<String>) {
        warm = cameraIds
    }

    /**
     * Holds sessions open for as long as the caller keeps this running, and
     * releases every one of them when it stops. Called from a STARTED-scoped
     * block, so backgrounding the app still tears every decoder down rather
     * than leaving a house's worth of cameras streaming into nothing.
     */
    internal suspend fun keepAlive(networkOnline: () -> Boolean): Unit = try {
        coroutineScope {
            launch {
                snapshotFlow(networkOnline).collect { up ->
                    online = up
                    streams.values.forEach {
                        if (up) it.onNetworkAvailable() else it.onNetworkLost()
                    }
                }
            }
            // Settled snapshots only, so a promotion's new claim, its lost one,
            // and the warm set it came with are all reconciled together rather
            // than one at a time with a teardown in between.
            snapshotFlow { claims.values.toMap() to warm }.collect { (wanted, warm) ->
                reconcile(wanted, warm, this)
            }
        }
    } finally {
        streams.values.forEach { it.release() }
        streams.clear()
    }

    private fun reconcile(
        wanted: Map<String, StreamSource>,
        warm: Set<String>,
        scope: CoroutineScope,
    ) {
        val keep = wanted.keys + warm.intersect(streams.keys)
        (streams.keys - keep).toList().forEach { streams.remove(it)?.release() }

        wanted.forEach { (cameraId, source) ->
            val existing = streams[cameraId]
            // A source change — a URL edit, or a console swap putting a camera
            // back on RTSP — means the running session is for the wrong stream.
            if (existing != null && existing.source == source) {
                existing.setVideoEnabled(true)
            } else {
                existing?.release()
                streams[cameraId] = CameraStream(
                    source = source,
                    controller = controllerFactory(source),
                    scope = scope,
                    config = watchdogConfig,
                ).also { it.start(online) }
            }
        }

        (keep - wanted.keys).forEach {
            streams[it]?.apply {
                // Nothing on screen can say where a sound is coming from while
                // the camera making it is not on it.
                setMuted(true)
                setVideoEnabled(false)
            }
        }
    }
}

/**
 * The viewer's camera sessions, held for as long as the host is at least
 * STARTED. Going to the background releases every one of them; coming back
 * builds them again for whichever tiles are on screen by then.
 */
@Composable
fun rememberCameraStreams(
    controllerFactory: (StreamSource) -> VideoPlayerController,
    networkOnline: Boolean,
    watchdogConfig: PlaybackWatchdog.Config = PlaybackWatchdog.Config(),
): CameraStreams {
    // Deliberately not keyed on the factory. Rebuilding the registry releases
    // every session it holds, and a caller passing a lambda that is a new
    // object each recomposition would have the whole viewer reconnect on any
    // state change at all. New sessions read whichever factory is current.
    val factory by rememberUpdatedState(controllerFactory)
    val streams = remember { CameraStreams({ factory(it) }, watchdogConfig) }
    val lifecycleOwner = LocalLifecycleOwner.current
    // Read inside the session rather than keyed on: connectivity changing is
    // what the watchdogs exist to absorb, so a flapping Wi-Fi must feed them
    // events rather than tear every session down and build it again.
    val online by rememberUpdatedState(networkOnline)
    LaunchedEffect(streams, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            streams.keepAlive { online }
        }
    }
    return streams
}

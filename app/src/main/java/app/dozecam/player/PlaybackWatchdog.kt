package app.dozecam.player

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Frame-level connection watchdog. Feed it player and network events; it
 * detects stalls (no frames within [Config.stallTimeoutMs]), drives reconnect
 * attempts with capped exponential backoff, and reports honest state — a
 * frozen frame must never pretend to be live.
 */
class PlaybackWatchdog(
    private val scope: CoroutineScope,
    private val onReconnect: suspend () -> Unit,
    /** Wall clock, only for the user-facing "last frame … ago" age. */
    private val wallClock: () -> Long = System::currentTimeMillis,
    /** Monotonic clock for all deadlines; wall-time jumps must not move them. */
    private val monotonicClock: () -> Long = SystemClock::elapsedRealtime,
    private val config: Config = Config(),
) {
    data class Config(
        val stallTimeoutMs: Long = 2_500,
        val connectTimeoutMs: Long = 5_000,
        val initialBackoffMs: Long = 500,
        val maxBackoffMs: Long = 4_000,
    )

    private sealed interface Input {
        data class Player(val event: PlayerEvent) : Input
        data object NetworkUp : Input
        data object NetworkDown : Input
        data class Video(val enabled: Boolean) : Input
    }

    private val events = Channel<Input>(Channel.UNLIMITED)
    private var job: Job? = null

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Connecting)
    val state: StateFlow<ConnectionState> = _state

    private val _lastFrameAtMs = MutableStateFlow<Long?>(null)
    val lastFrameAtMs: StateFlow<Long?> = _lastFrameAtMs

    fun onPlayerEvent(event: PlayerEvent) {
        events.trySend(Input.Player(event))
    }

    fun onNetworkAvailable() {
        events.trySend(Input.NetworkUp)
    }

    fun onNetworkLost() {
        events.trySend(Input.NetworkDown)
    }

    /**
     * Says whether this stream is still expected to produce a picture. It is
     * not, while the camera is kept connected for a grid nobody is looking at:
     * the video track is dropped, and stall detection has to stop with it or
     * every warm camera would be declared dead within [Config.stallTimeoutMs]
     * and reconnected — the exact teardown keeping it warm exists to avoid.
     *
     * Errors and network changes are still acted on, so a session that really
     * does die while unwatched is recovered rather than discovered on return.
     */
    fun onVideoEnabled() {
        events.trySend(Input.Video(true))
    }

    fun onVideoDisabled() {
        events.trySend(Input.Video(false))
    }

    fun start() {
        if (job?.isActive == true) return
        // Discard events queued while stopped (e.g. an Error racing
        // backgrounding); they describe a session that no longer exists.
        while (events.tryReceive().isSuccess) Unit
        _state.value = ConnectionState.Connecting
        job = scope.launch { run() }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun run() {
        var attempts = 0
        var networkUp = true
        // True between issuing a reconnect and seeing frames; teardown echoes
        // (Stopped from the old session) are ignored in this window.
        var awaitingRecovery = false
        // Whether a picture is still expected. Nothing is timed while it is
        // not: the frames every deadline here waits for are exactly what a
        // dropped video track stops producing.
        //
        // Nothing is restarted while it is not, either. A stream with no video
        // track offers nothing that distinguishes a session which survived from
        // one which did not — its audio clock ticks on either way — so rather
        // than guess from events that cannot answer the question, a failure is
        // remembered and settled at the one moment the answer is knowable: when
        // the picture is wanted again. That also keeps a repair for a camera
        // nobody can see from competing for the network with the one they are
        // actually watching.
        var videoOn = true
        var brokeWhileWarm = false
        // Absolute time at which the current phase (initial connect, live
        // stall watch, reconnect attempt) is declared failed. Only frame
        // events and phase transitions may move it — ignored events such as
        // Buffering must never push the deadline out.
        var deadline: Long? = monotonicClock() + config.connectTimeoutMs

        fun markLive() {
            _lastFrameAtMs.value = wallClock()
            _state.value = ConnectionState.Live
            attempts = 0
            awaitingRecovery = false
            deadline = if (videoOn) monotonicClock() + config.stallTimeoutMs else null
        }

        // Waits out the backoff window but stays responsive: returns the input
        // that should abort the reconnect (frames resumed, or network dropped),
        // or null when the window elapsed and the reconnect should proceed.
        suspend fun awaitBackoff(windowMs: Long): Input? {
            val deadline = monotonicClock() + windowMs
            while (true) {
                val remaining = deadline - monotonicClock()
                if (remaining <= 0) return null
                val input = withTimeoutOrNull(remaining) { events.receive() } ?: return null
                when (input) {
                    is Input.Player -> when (input.event) {
                        is PlayerEvent.Playing, is PlayerEvent.TimeChanged -> return input
                        else -> Unit // stale error/stop echoes from the failing session
                    }
                    Input.NetworkDown -> return input
                    Input.NetworkUp -> Unit // already about to reconnect
                    is Input.Video -> {
                        videoOn = input.enabled
                        // The camera stopped being watched partway through a
                        // restart. Carrying on would leave a reconnect in flight
                        // for a picture nobody is waiting for — and put this back
                        // to guessing whether the events that follow belong to
                        // the old session or the new one. Abandoned here and
                        // settled when the picture is wanted again.
                        if (!videoOn) return input
                    }
                }
            }
        }

        suspend fun attemptReconnect(immediate: Boolean = false) {
            attempts++
            _state.value = ConnectionState.Reconnecting(attempts)
            if (!immediate) {
                when (awaitBackoff(backoffFor(attempts))) {
                    is Input.Player -> {
                        markLive() // stream recovered on its own; skip the restart
                        return
                    }
                    Input.NetworkDown -> {
                        networkUp = false
                        _state.value = ConnectionState.Offline
                        deadline = null
                        return
                    }
                    is Input.Video -> {
                        brokeWhileWarm = true
                        deadline = null
                        return
                    }
                    else -> Unit
                }
            }
            onReconnect()
            awaitingRecovery = true
            // Only ever reached with the picture wanted, so there are frames
            // coming to judge the attempt by.
            deadline = monotonicClock() + config.connectTimeoutMs
        }

        while (true) {
            val timeoutAt = deadline
            val input = if (timeoutAt == null) {
                events.receive()
            } else {
                val remaining = timeoutAt - monotonicClock()
                if (remaining <= 0) null else withTimeoutOrNull(remaining) { events.receive() }
            }

            when (input) {
                null -> attemptReconnect() // stall while live, or connect attempt hung

                is Input.Player -> when (input.event) {
                    is PlayerEvent.Playing, is PlayerEvent.TimeChanged -> when {
                        // Nothing is painting, so nothing here is a frame. The
                        // audio clock goes on ticking for a camera nobody is
                        // watching, and counting it would date a frame that was
                        // never drawn and call a dropped track live. Nothing is
                        // waiting on it either, so there is nothing to retire.
                        !videoOn -> Unit
                        // Buffered frames can trickle in after network loss;
                        // never let them repaint an offline monitor as live.
                        networkUp -> markLive()
                        else -> _lastFrameAtMs.value = wallClock()
                    }
                    is PlayerEvent.Error -> when {
                        !videoOn -> brokeWhileWarm = true
                        networkUp -> attemptReconnect()
                        else -> _state.value = ConnectionState.Offline
                    }
                    is PlayerEvent.Stopped -> {
                        when {
                            !videoOn -> brokeWhileWarm = true
                            awaitingRecovery -> Unit // our own teardown echoing back
                            networkUp -> attemptReconnect()
                            else -> _state.value = ConnectionState.Offline
                        }
                    }
                    is PlayerEvent.Buffering -> Unit
                    // The picture's shape says nothing about whether it is
                    // still painting.
                    is PlayerEvent.VideoAspect -> Unit
                }

                Input.NetworkUp -> {
                    networkUp = true
                    val current = _state.value
                    when {
                        // A camera nobody is watching does not get the network
                        // back to itself. Whatever the drop did to its session
                        // is repaired when the picture is next wanted, so the
                        // room being looked at has the Wi-Fi to come back on.
                        !videoOn -> brokeWhileWarm = true
                        current is ConnectionState.Live -> Unit
                        current is ConnectionState.Connecting -> Unit
                        else -> {
                            attempts = 0
                            attemptReconnect(immediate = true)
                        }
                    }
                }

                Input.NetworkDown -> {
                    networkUp = false
                    _state.value = ConnectionState.Offline
                    deadline = null
                }

                is Input.Video -> {
                    videoOn = input.enabled
                    // The decoder went away with the track, so whatever the
                    // stream was doing before, it is not painting now. Leaving
                    // the pill on Live would be the frozen-frame lie this
                    // watchdog exists to prevent — and would hide the last
                    // frame's timestamp, which is the one thing that would give
                    // it away. A frame coming back sets it right.
                    if (videoOn && _state.value == ConnectionState.Live) {
                        _state.value = ConnectionState.Connecting
                    }
                    when {
                        // A fresh warm spell: nothing has gone wrong in it yet.
                        !videoOn -> {
                            brokeWhileWarm = false
                            deadline = null
                        }
                        // Nothing is coming until the network is back, and the
                        // offline state already says so.
                        !networkUp -> deadline = null
                        // Something did go wrong while nobody was looking, and
                        // now is when it can be answered rather than guessed at.
                        brokeWhileWarm -> {
                            brokeWhileWarm = false
                            attempts = 0
                            attemptReconnect(immediate = true)
                        }
                        // The first-frame allowance rather than the stall one:
                        // the decoder went away with the track, and it cannot
                        // paint until the stream's next keyframe comes round.
                        else -> deadline = monotonicClock() + config.connectTimeoutMs
                    }
                }
            }
        }
    }

    private fun backoffFor(attempt: Int): Long {
        val exp = config.initialBackoffMs shl (attempt - 1).coerceIn(0, 20)
        return exp.coerceAtMost(config.maxBackoffMs)
    }
}

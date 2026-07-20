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
    /** Wall clock, only for the user-facing "last frame at" timestamp. */
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
            deadline = monotonicClock() + config.stallTimeoutMs
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
                    else -> Unit
                }
            }
            onReconnect()
            awaitingRecovery = true
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
                    is PlayerEvent.Playing, is PlayerEvent.TimeChanged -> {
                        // Buffered frames can trickle in after network loss;
                        // never let them repaint an offline monitor as live.
                        if (networkUp) markLive() else _lastFrameAtMs.value = wallClock()
                    }
                    is PlayerEvent.Error -> {
                        if (networkUp) attemptReconnect() else _state.value = ConnectionState.Offline
                    }
                    is PlayerEvent.Stopped -> {
                        when {
                            awaitingRecovery -> Unit // our own teardown echoing back
                            networkUp -> attemptReconnect()
                            else -> _state.value = ConnectionState.Offline
                        }
                    }
                    is PlayerEvent.Buffering -> Unit
                }

                Input.NetworkUp -> {
                    networkUp = true
                    val current = _state.value
                    if (current !is ConnectionState.Live && current !is ConnectionState.Connecting) {
                        attempts = 0
                        attemptReconnect(immediate = true)
                    }
                }

                Input.NetworkDown -> {
                    networkUp = false
                    _state.value = ConnectionState.Offline
                    deadline = null
                }
            }
        }
    }

    private fun backoffFor(attempt: Int): Long {
        val exp = config.initialBackoffMs shl (attempt - 1).coerceIn(0, 20)
        return exp.coerceAtMost(config.maxBackoffMs)
    }
}

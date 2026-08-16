package app.dozecam.audio.talkback

import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Whether the phone can reach a camera at all.
 *
 * Talk-back audio goes to the camera, while its video arrives through the
 * console, so the two can disagree: a camera on an isolated VLAN streams
 * perfectly and cannot be spoken to. UDP will not say so — a datagram sent
 * nowhere reports success — so the only honest moment to find out is before the
 * control is offered.
 *
 * A TCP connection is the available proxy. It proves the phone can route to the
 * camera, which is the failure that actually happens; it cannot prove UDP on
 * 7004 passes, and a camera restricting its senders through `filterAddr` would
 * pass this and still drop the audio. So this decides whether to offer a
 * control, never whether one worked.
 *
 * Answers are cached per host because they change only with the network, and
 * [forget] is called when it does.
 */
class TalkbackReachability(
    private val connect: suspend (host: String, port: Int) -> Boolean = ::tcpConnect,
) {
    private val mutex = Mutex()
    private val known = mutableMapOf<String, Boolean>()

    suspend fun isReachable(host: String): Boolean {
        mutex.withLock { known[host] }?.let { return it }
        // Probed outside the lock: a slow camera must not hold up every other
        // camera's answer, and a duplicate probe is cheaper than a queue.
        val reachable = PROBE_PORTS.any { connect(host, it) }
        mutex.withLock { known[host] = reachable }
        return reachable
    }

    /** The network changed, so every previous answer was about a different one. */
    suspend fun forget() {
        mutex.withLock { known.clear() }
    }

    companion object {
        /**
         * Protect cameras answer on both; either is enough, since the question
         * is whether packets can get there rather than what is listening.
         */
        private val PROBE_PORTS = listOf(443, 80)

        /** Long enough for a busy LAN, short enough not to stall a button. */
        const val TIMEOUT_MILLIS = 1_500
    }
}

private suspend fun tcpConnect(host: String, port: Int): Boolean =
    withContext(Dispatchers.IO) {
        runCatching {
            Socket().use { it.connect(InetSocketAddress(host, port), TalkbackReachability.TIMEOUT_MILLIS) }
        }.isSuccess
    }

package app.dozecam.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Where the device's default network stands in relation to the cameras.
 *
 * Dozecam is LAN-only by design: the console and its cameras live on the
 * house's own network and are never exposed to the internet. So "has a
 * network" and "can reach the cameras" are genuinely different questions, and
 * a phone on mobile data answers yes to the first and no to the second.
 */
enum class NetworkReach {
    /** No default network at all. */
    OFFLINE,

    /** A network that could carry LAN traffic — Wi-Fi, Ethernet, a tunnel home. */
    LOCAL,

    /** Online, but by a route that cannot reach the console: mobile data. */
    MOBILE_DATA,
}

/** Emits how the default network stands, and re-emits whenever that changes. */
class NetworkMonitor(context: Context) {

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    val reach: Flow<NetworkReach> = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            // During a handover, onLost(old) can arrive after onAvailable(new);
            // only the loss of the network we currently consider default counts.
            // Callbacks are delivered serially, so plain state is safe.
            private var current: Network? = null

            override fun onAvailable(network: Network) {
                current = network
                trySend(reachOf(connectivityManager.getNetworkCapabilities(network)))
            }

            // How a network is carried can change without the network itself
            // being replaced, and this is also where the transports missing
            // from [onAvailable] arrive — Android delivers them immediately
            // after, not with it.
            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities,
            ) {
                if (network == current) trySend(reachOf(networkCapabilities))
            }

            override fun onLost(network: Network) {
                if (network == current) {
                    current = null
                    trySend(NetworkReach.OFFLINE)
                }
            }
        }
        connectivityManager.registerDefaultNetworkCallback(callback)
        // Seed after registering: the callback never fires onLost for a device
        // that starts with no network, and registering first means a transition
        // racing this seed still delivers its callback afterwards.
        trySend(
            connectivityManager.activeNetwork
                ?.let { reachOf(connectivityManager.getNetworkCapabilities(it)) }
                ?: NetworkReach.OFFLINE,
        )
        awaitClose { connectivityManager.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged()

    /**
     * Emits once for every default network the device settles on, including
     * replacements that leave [reach] exactly where it was.
     *
     * [reach] answers "could this network carry LAN traffic", which is the same
     * answer for the house's Wi-Fi, a café's, and a tunnel home — so it is
     * deduplicated away precisely when the network underneath has changed
     * completely. Anything holding knowledge about a particular network rather
     * than about its kind has to listen here instead: whether a given camera
     * answers is exactly that sort of knowledge.
     */
    val defaultNetworkChanges: Flow<Unit> = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(Unit)
            }

            override fun onLost(network: Network) {
                trySend(Unit)
            }
        }
        connectivityManager.registerDefaultNetworkCallback(callback)
        awaitClose { connectivityManager.unregisterNetworkCallback(callback) }
    }

    /** True when a default network is available, false when it is lost. */
    val isOnline: Flow<Boolean> =
        reach.map { it != NetworkReach.OFFLINE }.distinctUntilChanged()

    private fun reachOf(capabilities: NetworkCapabilities?): NetworkReach = when {
        // Capabilities not delivered yet. Read as local on purpose: the real
        // transports land a moment later, and guessing the other way would
        // flash the warning at every reconnect.
        capabilities == null -> NetworkReach.LOCAL
        capabilities.reachesLocalNetwork() -> NetworkReach.LOCAL
        else -> NetworkReach.MOBILE_DATA
    }

    /**
     * Whether traffic on this network could plausibly reach a console on the
     * house's own LAN.
     *
     * Asked as a question about mobile data rather than about Wi-Fi, and
     * deliberately so. Wi-Fi is the ordinary answer, but a docked tablet on
     * Ethernet reaches the console too, and so does a phone tunnelled home
     * over WireGuard or Tailscale — which is exactly how this app is meant to
     * be used from away. Anything unrecognised gets the benefit of the doubt:
     * a warning that fires at a viewer streaming perfectly well is one the
     * user has learned to ignore by the night it is true.
     */
    private fun NetworkCapabilities.reachesLocalNetwork(): Boolean =
        hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
            hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
            !hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
}

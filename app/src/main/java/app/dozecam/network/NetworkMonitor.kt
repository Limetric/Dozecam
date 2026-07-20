package app.dozecam.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/** Emits true when a default network is available, false when it is lost. */
class NetworkMonitor(context: Context) {

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    val isOnline: Flow<Boolean> = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            // During a handover, onLost(old) can arrive after onAvailable(new);
            // only the loss of the network we currently consider default counts.
            // Callbacks are delivered serially, so plain state is safe.
            private var current: Network? = null

            override fun onAvailable(network: Network) {
                current = network
                trySend(true)
            }

            override fun onLost(network: Network) {
                if (network == current) {
                    current = null
                    trySend(false)
                }
            }
        }
        connectivityManager.registerDefaultNetworkCallback(callback)
        // Seed after registering: the callback never fires onLost for a device
        // that starts with no network, and registering first means a transition
        // racing this seed still delivers its callback afterwards.
        trySend(connectivityManager.activeNetwork != null)
        awaitClose { connectivityManager.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged()
}

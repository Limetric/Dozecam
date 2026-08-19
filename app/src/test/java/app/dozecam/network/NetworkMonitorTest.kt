package app.dozecam.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class NetworkMonitorTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    /** A network carried by exactly [transports], registered with the shadow. */
    private fun network(id: Int, vararg transports: Int): Network {
        val network = ShadowNetwork.newInstance(id)
        val capabilities = ShadowNetworkCapabilities.newInstance()
        shadowOf(capabilities).apply {
            // Robolectric hands out a fresh set with Wi-Fi already on it, which
            // would make every network below look local.
            removeTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            transports.forEach { addTransportType(it) }
        }
        shadowOf(connectivityManager).setNetworkCapabilities(network, capabilities)
        return network
    }

    /** Collects [NetworkMonitor.reach] for the body of the test. */
    private fun TestScope.reaches(monitor: NetworkMonitor): List<NetworkReach> {
        val seen = mutableListOf<NetworkReach>()
        backgroundScope.launch { monitor.reach.collect { seen += it } }
        return seen
    }

    private fun defaultCallback(): ConnectivityManager.NetworkCallback =
        shadowOf(connectivityManager).networkCallbacks.first()

    @Test
    fun `emits the current network state on subscription`() = runTest {
        val monitor = NetworkMonitor(context)

        // Robolectric's default environment has an active network.
        assertTrue(monitor.isOnline.first())
    }

    @Test
    fun `losing the old network after a handover does not report offline`() =
        runTest(UnconfinedTestDispatcher()) {
            val monitor = NetworkMonitor(context)
            val emissions = mutableListOf<Boolean>()
            val job = launch { monitor.isOnline.collect { emissions += it } }

            val connectivityManager =
                context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val callback = shadowOf(connectivityManager).networkCallbacks.first()
            val oldNetwork = ShadowNetwork.newInstance(100)
            val newNetwork = ShadowNetwork.newInstance(101)

            callback.onAvailable(oldNetwork)
            callback.onAvailable(newNetwork) // handover: new default arrives first
            callback.onLost(oldNetwork) // stale loss of the replaced network

            assertEquals(listOf(true), emissions)
            job.cancel()
        }

    @Test
    fun `losing the current network reports offline`() =
        runTest(UnconfinedTestDispatcher()) {
            val monitor = NetworkMonitor(context)
            val emissions = mutableListOf<Boolean>()
            val job = launch { monitor.isOnline.collect { emissions += it } }

            val connectivityManager =
                context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val callback = shadowOf(connectivityManager).networkCallbacks.first()
            val network = ShadowNetwork.newInstance(100)

            callback.onAvailable(network)
            callback.onLost(network)

            assertEquals(listOf(true, false), emissions)
            job.cancel()
        }

    @Test
    fun `mobile data is online but out of reach of the cameras`() =
        runTest(UnconfinedTestDispatcher()) {
            val monitor = NetworkMonitor(context)
            val seen = reaches(monitor)
            val cellular = network(100, NetworkCapabilities.TRANSPORT_CELLULAR)

            defaultCallback().onAvailable(cellular)

            // The console is only ever on the LAN, so a working data connection
            // is no help at all — and saying "offline" would be a lie the user
            // could disprove by opening any other app.
            assertEquals(NetworkReach.MOBILE_DATA, seen.last())
        }

    @Test
    fun `Wi-Fi reaches the cameras`() = runTest(UnconfinedTestDispatcher()) {
        val monitor = NetworkMonitor(context)
        val seen = reaches(monitor)

        defaultCallback().onAvailable(network(100, NetworkCapabilities.TRANSPORT_WIFI))

        assertEquals(NetworkReach.LOCAL, seen.last())
    }

    @Test
    fun `a docked tablet on Ethernet is not warned about Wi-Fi`() =
        runTest(UnconfinedTestDispatcher()) {
            val monitor = NetworkMonitor(context)
            val seen = reaches(monitor)

            defaultCallback().onAvailable(network(100, NetworkCapabilities.TRANSPORT_ETHERNET))

            assertEquals(NetworkReach.LOCAL, seen.last())
        }

    @Test
    fun `a tunnel home over mobile data counts as local`() =
        runTest(UnconfinedTestDispatcher()) {
            val monitor = NetworkMonitor(context)
            val seen = reaches(monitor)
            // What a VPN looks like: its own transport over the one carrying it.
            val tunnel = network(
                100,
                NetworkCapabilities.TRANSPORT_VPN,
                NetworkCapabilities.TRANSPORT_CELLULAR,
            )

            defaultCallback().onAvailable(tunnel)

            // WireGuard or Tailscale is how the design doc says to watch from
            // away, and it does reach the console.
            assertEquals(NetworkReach.LOCAL, seen.last())
        }

    @Test
    fun `losing the network reports offline rather than mobile data`() =
        runTest(UnconfinedTestDispatcher()) {
            val monitor = NetworkMonitor(context)
            val seen = reaches(monitor)
            val cellular = network(100, NetworkCapabilities.TRANSPORT_CELLULAR)

            defaultCallback().onAvailable(cellular)
            defaultCallback().onLost(cellular)

            assertEquals(NetworkReach.OFFLINE, seen.last())
        }

    @Test
    fun `a network that turns out to be cellular is re-reported`() =
        runTest(UnconfinedTestDispatcher()) {
            val monitor = NetworkMonitor(context)
            val seen = reaches(monitor)
            val cellular = ShadowNetwork.newInstance(100)

            // onAvailable with nothing known about how the network is carried:
            // Android delivers the transports separately, a moment later.
            defaultCallback().onAvailable(cellular)
            assertEquals(NetworkReach.LOCAL, seen.last())

            val capabilities = ShadowNetworkCapabilities.newInstance()
            shadowOf(capabilities).apply {
                removeTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            }
            defaultCallback().onCapabilitiesChanged(cellular, capabilities)

            assertEquals(NetworkReach.MOBILE_DATA, seen.last())
        }

    @Test
    fun `capabilities of a network already replaced are ignored`() =
        runTest(UnconfinedTestDispatcher()) {
            val monitor = NetworkMonitor(context)
            val seen = reaches(monitor)
            val old = network(100, NetworkCapabilities.TRANSPORT_CELLULAR)
            val new = network(101, NetworkCapabilities.TRANSPORT_WIFI)

            defaultCallback().onAvailable(old)
            defaultCallback().onAvailable(new)
            // A late word about the network that has already been handed over
            // from must not put the warning back over a viewer on Wi-Fi.
            defaultCallback().onCapabilitiesChanged(
                old,
                ShadowNetworkCapabilities.newInstance().also {
                    shadowOf(it).apply {
                        removeTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                        addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                    }
                },
            )

            assertEquals(NetworkReach.LOCAL, seen.last())
        }
}

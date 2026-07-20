package app.dozecam.network

import android.content.Context
import android.net.ConnectivityManager
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNetwork

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class NetworkMonitorTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

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
}

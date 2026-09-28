package app.dozecam.monitoring

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.dozecam.DozecamApp
import app.dozecam.data.Camera
import app.dozecam.data.ProtectStream
import app.dozecam.protect.ProtectCredentials
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ArmingTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val container get() = (context.applicationContext as DozecamApp).container

    @Before
    fun reset() {
        container.monitoringState.serviceRunning.value = false
        container.protectCredentials.clear()
    }

    @Test
    fun `a monitorable camera arms monitoring`() = runTest {
        container.cameras.upsert(Camera("a", "Nursery", "rtsp://cam:7447/a"))

        assertTrue(container.shouldArmMonitoring(context, localNetworkGranted = true))
    }

    @Test
    fun `nothing arms without local network access`() = runTest {
        container.cameras.upsert(Camera("a", "Nursery", "rtsp://cam:7447/a"))

        // Every RTSP connection would be dropped as a timeout, so this would be
        // a foreground service holding a wake lock to reconnect all night.
        assertFalse(container.shouldArmMonitoring(context, localNetworkGranted = false))
    }

    @Test
    fun `a watch-only camera does not arm monitoring`() = runTest {
        // rtsps cannot be monitored, so the service would stop itself the
        // moment it started — once per resume, forever.
        container.cameras.upsert(Camera("a", "Stale", "rtsps://cam:7441/a"))

        assertFalse(container.shouldArmMonitoring(context, localNetworkGranted = true))
    }

    @Test
    fun `a watch-only camera Protect can carry does arm monitoring`() = runTest {
        container.protectCredentials.save(ProtectCredentials("console.lan", "user", "pass"))
        container.cameras.upsert(
            Camera(
                id = "a",
                name = "Nursery",
                url = "rtsps://cam:7441/a",
                protect = ProtectStream("cam1", 1, "console.lan"),
            ),
        )

        // Its URL is one Media3 cannot open, but the livestream carries this
        // camera's audio — and a gate that refused to arm here would leave the
        // service unable to listen to a room it is perfectly able to hear.
        assertTrue(container.shouldArmMonitoring(context, localNetworkGranted = true))
    }

    @Test
    fun `a camera from a console we are no longer signed in to does not arm`() = runTest {
        container.protectCredentials.save(ProtectCredentials("new-console.lan", "user", "pass"))
        container.cameras.upsert(
            Camera(
                id = "a",
                name = "Nursery",
                url = "rtsps://cam:7441/a",
                protect = ProtectStream("cam1", 1, "old-console.lan"),
            ),
        )

        // The new console has never heard of cam1, so there is no livestream to
        // fall back to and rtsps is still unopenable.
        assertFalse(container.shouldArmMonitoring(context, localNetworkGranted = true))
    }

    @Test
    fun `a switched-off camera does not arm monitoring`() = runTest {
        container.cameras.upsert(
            Camera("a", "Nursery", "rtsp://cam:7447/a", enabled = false),
        )

        assertFalse(container.shouldArmMonitoring(context, localNetworkGranted = true))
    }

    @Test
    fun `an already-running service is not started again`() = runTest {
        container.cameras.upsert(Camera("a", "Nursery", "rtsp://cam:7447/a"))
        container.monitoringState.serviceRunning.value = true

        assertFalse(container.shouldArmMonitoring(context, localNetworkGranted = true))
    }
}

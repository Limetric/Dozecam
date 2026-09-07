package app.dozecam.monitoring

import android.Manifest
import android.app.Application
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Automatic arming must never interrupt the viewer with permission UI. */
@RunWith(RobolectricTestRunner::class)
class MonitoringStarterTest {
    private val application = ApplicationProvider.getApplicationContext<Application>()

    @Test
    @Config(sdk = [34])
    fun `arming starts monitoring immediately with notifications denied`() {
        shadowOf(application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).get()

        MonitoringStarter(activity).start()

        val started = shadowOf(application).nextStartedService
        assertNotNull(started)
        assertEquals(MonitoringService::class.java.name, started.component?.className)
        assertNull(shadowOf(activity).lastRequestedPermission)
        assertNull(shadowOf(application).nextStartedActivity)
    }

    @Test
    @Config(sdk = [34])
    fun `missing full screen access does not open settings while arming`() {
        shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).get()

        MonitoringStarter(activity).start()

        assertNotNull(shadowOf(application).nextStartedService)
        assertNull(shadowOf(application).nextStartedActivity)
        assertNull(shadowOf(activity).lastRequestedPermission)
    }
}

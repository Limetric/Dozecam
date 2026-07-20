package app.dozecam.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MonitoringPrefsTest {

    @Test
    fun `active url round-trips and clears`() = runTest {
        val prefs = MonitoringPrefs(
            ApplicationProvider.getApplicationContext<Context>()
                .getSharedPreferences("test_monitoring", Context.MODE_PRIVATE),
        )

        assertNull(prefs.activeMonitoringUrl())

        prefs.setActiveMonitoringUrl("rtsp://cam:7447/a")
        assertEquals("rtsp://cam:7447/a", prefs.activeMonitoringUrl())

        prefs.clearActiveMonitoringUrl()
        assertNull(prefs.activeMonitoringUrl())
    }
}

package app.dozecam.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MonitoringPrefsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `active url round-trips and clears`() = runTest {
        val prefs = MonitoringPrefs(
            PreferenceDataStoreFactory.create(
                scope = backgroundScope,
                produceFile = { File(tmp.root, "monitoring.preferences_pb") },
            ),
        )

        assertNull(prefs.activeMonitoringUrl.first())

        prefs.setActiveMonitoringUrl("rtsp://cam:7447/a")
        assertEquals("rtsp://cam:7447/a", prefs.activeMonitoringUrl.first())

        prefs.clearActiveMonitoringUrl()
        assertNull(prefs.activeMonitoringUrl.first())
    }
}

package app.dozecam.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DetectorSettingsRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `defaults are returned before anything is stored`() = runTest {
        val dataStore = PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { File(tmp.root, "test.preferences_pb") },
        )
        val repository = DetectorSettingsRepository(dataStore)

        assertEquals(DetectorSettings(), repository.settings.first())
    }

    @Test
    fun `updated settings round-trip`() = runTest {
        val dataStore = PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { File(tmp.root, "test.preferences_pb") },
        )
        val repository = DetectorSettingsRepository(dataStore)
        val custom = DetectorSettings(threshold = 0.25f, sustainMs = 3_000, quietMs = 20_000)

        repository.update(custom)

        assertEquals(custom, repository.settings.first())
    }
}

package app.dozecam.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AppSettingsRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `defaults then round-trip`() = runTest {
        val dataStore = PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { File(tmp.root, "settings.preferences_pb") },
        )
        val repository = AppSettingsRepository(dataStore)

        assertEquals(AppSettings(), repository.settings.first())

        val custom = AppSettings(
            nightTheme = true,
            alertChime = false,
            alertVibrate = false,
            orientationLock = OrientationLock.LANDSCAPE,
        )
        repository.update { custom }

        assertEquals(custom, repository.settings.first())
    }
}

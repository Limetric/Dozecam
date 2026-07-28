package app.dozecam.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.dozecam.monitoring.AlarmSchedule
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        // Stated outright rather than left to the round-trip: a viewer that
        // starts audible would talk over a sleeping room the moment it opens.
        assertFalse(repository.settings.first().viewerSound)

        val custom = AppSettings(
            nightTheme = true,
            alertChime = false,
            alertVibrate = false,
            alertSoundUri = "content://media/internal/audio/media/42",
            alertRamp = false,
            alertRepeatIntervalMs = 12_000,
            alertVolume = 0.6f,
            alertBypassDnd = true,
            orientationLock = OrientationLock.LANDSCAPE,
            viewerSound = true,
        )
        repository.update { custom }

        assertEquals(custom, repository.settings.first())
    }

    /**
     * Alert defaults are the ones nobody will have touched before the night they
     * matter, so they are stated here rather than left to the round-trip: an
     * alert that starts quiet, never repeats, or overrides Do Not Disturb
     * uninvited would each be a bug nobody discovers until 3am.
     */
    @Test
    fun `alerts default to escalating, repeating, and no system overrides`() = runTest {
        val defaults = AppSettings()

        assertNull("no choice made means the phone's own alarm sound", defaults.alertSoundUri)
        assertTrue(defaults.alertChime)
        assertTrue(defaults.alertVibrate)
        assertTrue(defaults.alertRamp)
        assertEquals(1f, defaults.alertVolume, 0.0001f)
        assertFalse("a system override must always be asked for", defaults.alertBypassDnd)
        assertTrue(
            "the repeat has to be inside the range settings offers",
            defaults.alertRepeatIntervalMs in
                AlarmSchedule.MIN_REPEAT_INTERVAL_MS..AlarmSchedule.MAX_REPEAT_INTERVAL_MS,
        )
    }

    /** Clearing the choice has to mean "the phone's alarm sound", not an empty URI. */
    @Test
    fun `clearing the alert sound restores the default rather than an empty value`() = runTest {
        val dataStore = PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { File(tmp.root, "cleared.preferences_pb") },
        )
        val repository = AppSettingsRepository(dataStore)

        repository.update { it.copy(alertSoundUri = "content://media/internal/audio/media/7") }
        assertEquals(
            "content://media/internal/audio/media/7",
            repository.settings.first().alertSoundUri,
        )

        repository.update { it.copy(alertSoundUri = null) }

        assertNull(repository.settings.first().alertSoundUri)
    }
}

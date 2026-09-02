package app.dozecam.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.dozecam.monitoring.AlarmSchedule
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
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
        // Likewise: a monitor that went dark at the system timeout on the
        // first night would look like a dead app, so sleeping is opt-in.
        assertTrue(repository.settings.first().keepScreenOn)
        // Full volume by default: the slider only ever quiets talk-back, so
        // until it is touched the camera sounds exactly as it always has.
        assertEquals(1f, repository.settings.first().talkbackVolume, 0.0001f)

        val custom = AppSettings(
            nightTheme = true,
            alertChime = false,
            alertVibrate = false,
            alertSoundUri = "content://media/internal/audio/media/42",
            alertRamp = false,
            alertRepeatIntervalMs = 12_000,
            alertVolume = 0.6f,
            orientationLock = OrientationLock.LANDSCAPE,
            viewerSound = true,
            keepScreenOn = false,
            talkbackVolume = 0.4f,
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
        assertTrue(
            "the repeat has to be inside the range settings offers",
            defaults.alertRepeatIntervalMs in
                AlarmSchedule.MIN_REPEAT_INTERVAL_MS..AlarmSchedule.MAX_REPEAT_INTERVAL_MS,
        )
    }

    /**
     * Listen mode once remembered a chosen room here. It plays every room now,
     * so the entry has nothing left to mean — and the fact that a speaker was
     * on has never been stored at all: a phone that rebooted itself at 4am
     * must not come back broadcasting a bedroom with nobody having asked.
     */
    @Test
    fun `a room once chosen for listen mode is forgotten on the next write`() = runTest {
        val file = File(tmp.root, "listen.preferences_pb")
        val legacyKey = stringPreferencesKey("listen_camera_id")
        val before = CoroutineScope(backgroundScope.coroutineContext + Job())
        PreferenceDataStoreFactory.create(scope = before, produceFile = { file })
            .edit { it[legacyKey] = "nursery" }
        before.cancel()

        val dataStore = PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { file },
        )
        AppSettingsRepository(dataStore).update { it.copy(nightTheme = true) }

        assertNull(dataStore.data.first()[legacyKey])
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

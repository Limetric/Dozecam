package app.dozecam.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class OrientationLock { AUTO, PORTRAIT, LANDSCAPE }

data class AppSettings(
    /** Dim red palette that preserves night vision next to a crib. */
    val nightTheme: Boolean = false,
    val alertChime: Boolean = true,
    val alertVibrate: Boolean = true,
    val orientationLock: OrientationLock = OrientationLock.AUTO,
    /**
     * Whether the viewer may play camera audio. Off until asked for, and
     * remembered: a viewer that comes back talking after a restart — or after
     * the alert that woke the screen — is a surprise nobody asked for twice.
     */
    val viewerSound: Boolean = false,
)

interface AppSettingsStore {
    val settings: Flow<AppSettings>

    /** Atomic read-modify-write; rapid successive updates cannot clobber each other. */
    suspend fun update(transform: (AppSettings) -> AppSettings)
}

class AppSettingsRepository(private val dataStore: DataStore<Preferences>) : AppSettingsStore {

    override val settings: Flow<AppSettings> = dataStore.data.map(::settingsFrom)

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        dataStore.edit { prefs ->
            val next = transform(settingsFrom(prefs))
            prefs[KEY_NIGHT_THEME] = next.nightTheme
            prefs[KEY_ALERT_CHIME] = next.alertChime
            prefs[KEY_ALERT_VIBRATE] = next.alertVibrate
            prefs[KEY_ORIENTATION] = next.orientationLock.name
            prefs[KEY_VIEWER_SOUND] = next.viewerSound
        }
    }

    private fun settingsFrom(prefs: Preferences): AppSettings {
        val defaults = AppSettings()
        return AppSettings(
            nightTheme = prefs[KEY_NIGHT_THEME] ?: defaults.nightTheme,
            alertChime = prefs[KEY_ALERT_CHIME] ?: defaults.alertChime,
            alertVibrate = prefs[KEY_ALERT_VIBRATE] ?: defaults.alertVibrate,
            orientationLock = prefs[KEY_ORIENTATION]
                ?.let { stored -> OrientationLock.entries.firstOrNull { it.name == stored } }
                ?: defaults.orientationLock,
            viewerSound = prefs[KEY_VIEWER_SOUND] ?: defaults.viewerSound,
        )
    }

    private companion object {
        val KEY_NIGHT_THEME = booleanPreferencesKey("night_theme")
        val KEY_ALERT_CHIME = booleanPreferencesKey("alert_chime")
        val KEY_ALERT_VIBRATE = booleanPreferencesKey("alert_vibrate")
        val KEY_ORIENTATION = stringPreferencesKey("orientation_lock")
        val KEY_VIEWER_SOUND = booleanPreferencesKey("viewer_sound")
    }
}

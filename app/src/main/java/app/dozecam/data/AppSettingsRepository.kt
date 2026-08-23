package app.dozecam.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class OrientationLock { AUTO, PORTRAIT, LANDSCAPE }

data class AppSettings(
    /** Dim red palette that preserves night vision next to a crib. */
    val nightTheme: Boolean = false,
    val alertChime: Boolean = true,
    val alertVibrate: Boolean = true,
    /**
     * The alert tone, or null for the phone's own alarm sound. Never a
     * notification tone: that is the one sound trained to be slept through.
     */
    val alertSoundUri: String? = null,
    /** Climb from a gentle first note instead of starting at full volume. */
    val alertRamp: Boolean = true,
    val alertRepeatIntervalMs: Long = 8_000,
    /**
     * Ceiling as a fraction of the phone's alarm volume. Dozecam plays on the
     * alarm stream and never rewrites what the user set there, so this can quiet
     * an alert but never make it louder than their own alarm clock.
     */
    val alertVolume: Float = 1f,
    val orientationLock: OrientationLock = OrientationLock.AUTO,
    /**
     * Whether the viewer may play camera audio. Off until asked for, and
     * remembered: a viewer that comes back talking after a restart — or after
     * the alert that woke the screen — is a surprise nobody asked for twice.
     */
    val viewerSound: Boolean = false,
    /**
     * Whether the viewer holds the display awake while cameras are showing.
     * On by default: a monitor propped up for the night that went dark at the
     * system timeout would be a broken baby monitor, so sleeping is the choice
     * that has to be asked for.
     */
    val keepScreenOn: Boolean = true,
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
            // Absent rather than empty: "no choice made" has to keep meaning the
            // phone's own alarm sound, whatever that becomes later.
            next.alertSoundUri
                ?.let { prefs[KEY_ALERT_SOUND] = it }
                ?: prefs.remove(KEY_ALERT_SOUND)
            prefs[KEY_ALERT_RAMP] = next.alertRamp
            prefs[KEY_ALERT_REPEAT_MS] = next.alertRepeatIntervalMs
            prefs[KEY_ALERT_VOLUME] = next.alertVolume
            prefs[KEY_ORIENTATION] = next.orientationLock.name
            prefs[KEY_VIEWER_SOUND] = next.viewerSound
            prefs[KEY_KEEP_SCREEN_ON] = next.keepScreenOn
        }
    }

    private fun settingsFrom(prefs: Preferences): AppSettings {
        val defaults = AppSettings()
        return AppSettings(
            nightTheme = prefs[KEY_NIGHT_THEME] ?: defaults.nightTheme,
            alertChime = prefs[KEY_ALERT_CHIME] ?: defaults.alertChime,
            alertVibrate = prefs[KEY_ALERT_VIBRATE] ?: defaults.alertVibrate,
            alertSoundUri = prefs[KEY_ALERT_SOUND] ?: defaults.alertSoundUri,
            alertRamp = prefs[KEY_ALERT_RAMP] ?: defaults.alertRamp,
            alertRepeatIntervalMs = prefs[KEY_ALERT_REPEAT_MS] ?: defaults.alertRepeatIntervalMs,
            alertVolume = prefs[KEY_ALERT_VOLUME] ?: defaults.alertVolume,
            orientationLock = prefs[KEY_ORIENTATION]
                ?.let { stored -> OrientationLock.entries.firstOrNull { it.name == stored } }
                ?: defaults.orientationLock,
            viewerSound = prefs[KEY_VIEWER_SOUND] ?: defaults.viewerSound,
            keepScreenOn = prefs[KEY_KEEP_SCREEN_ON] ?: defaults.keepScreenOn,
        )
    }

    private companion object {
        val KEY_NIGHT_THEME = booleanPreferencesKey("night_theme")
        val KEY_ALERT_CHIME = booleanPreferencesKey("alert_chime")
        val KEY_ALERT_VIBRATE = booleanPreferencesKey("alert_vibrate")
        val KEY_ALERT_SOUND = stringPreferencesKey("alert_sound_uri")
        val KEY_ALERT_RAMP = booleanPreferencesKey("alert_ramp")
        val KEY_ALERT_REPEAT_MS = longPreferencesKey("alert_repeat_interval_ms")
        val KEY_ALERT_VOLUME = floatPreferencesKey("alert_volume")
        val KEY_ORIENTATION = stringPreferencesKey("orientation_lock")
        val KEY_VIEWER_SOUND = booleanPreferencesKey("viewer_sound")
        val KEY_KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
    }
}

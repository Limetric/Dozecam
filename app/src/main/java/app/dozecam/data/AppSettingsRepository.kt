package app.dozecam.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class OrientationLock { AUTO, PORTRAIT, LANDSCAPE }

/**
 * What the phone's speaker does with the cameras.
 *
 * One setting for the viewer and the monitor both, because it is one speaker.
 * [ROTATING] is the viewer's: one tile at a time takes a turn, and it ends
 * when the screen does. [ALL_ALOUD] is the house: every camera plays at once
 * on screen, and the monitoring service carries the same mix on with the
 * display off — listen mode, under the same switch as the rest.
 */
enum class SoundMode { OFF, ROTATING, ALL_ALOUD }

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
    /**
     * How long a monitored camera may be unreachable before that is a failure
     * worth an alarm. Long enough that an ordinary reconnect never crosses it:
     * an alarm for every brief drop is one people learn to sleep through.
     */
    val failureGraceMs: Long = 60_000,
    val orientationLock: OrientationLock = OrientationLock.AUTO,
    /**
     * What the speaker plays. Off until asked for, and remembered — including
     * [SoundMode.ALL_ALOUD], which the service picks up again the next time
     * the app is opened. Opening the app is the ask: there is no boot start,
     * so a phone that reboots itself in the night stays silent until somebody
     * comes back to it.
     */
    val soundMode: SoundMode = SoundMode.OFF,
    /**
     * Whether a room getting loud does anything at all — wakes the screen,
     * chimes, vibrates. Off, the monitor keeps listening (the meters move and
     * the speaker still plays) but nothing reaches anyone. On by default: a
     * baby monitor that starts out not waking anyone is the failure nobody
     * discovers until the one night it matters.
     */
    val alertsEnabled: Boolean = true,
    /**
     * Whether the viewer holds the display awake while cameras are showing.
     * On by default: a monitor propped up for the night that went dark at the
     * system timeout would be a broken baby monitor, so sleeping is the choice
     * that has to be asked for.
     */
    val keepScreenOn: Boolean = true,
    /**
     * How loud a talk-back press comes out of the camera, as a 0..1 slider
     * position. Applied to the samples this phone sends; the camera's own
     * speaker volume — a console setting shared with every other viewer — is
     * never touched.
     */
    val talkbackVolume: Float = 1f,
    /**
     * The bedtime checks whose failure the user has already been told about,
     * by id (see [app.dozecam.monitoring.ReadinessCheck]).
     *
     * Remembered rather than held in memory because the viewer arms the
     * monitor every time it comes to the front, and a warning that returned on
     * every open would be a nightly nag about a thing the user has looked at
     * and decided to live with. An entry is dropped the moment its check passes
     * again, so the *next* time that same thing breaks it is worth saying
     * again — which is the difference between a reminder and a nag.
     */
    val acknowledgedReadinessChecks: Set<String> = emptySet(),
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
            prefs[KEY_FAILURE_GRACE_MS] = next.failureGraceMs
            prefs[KEY_ORIENTATION] = next.orientationLock.name
            prefs[KEY_SOUND_MODE] = next.soundMode.name
            prefs[KEY_ALERTS_ENABLED] = next.alertsEnabled
            // Listen mode once chose a single room and remembered it here, and
            // the viewer's sound was once a plain switch. Both have been read
            // into [soundMode] by now, so neither is left to disagree with it.
            prefs.remove(KEY_LEGACY_LISTEN_CAMERA)
            prefs.remove(KEY_LEGACY_VIEWER_SOUND)
            prefs[KEY_KEEP_SCREEN_ON] = next.keepScreenOn
            prefs[KEY_TALKBACK_VOLUME] = next.talkbackVolume
            prefs[KEY_ACKNOWLEDGED_READINESS] = next.acknowledgedReadinessChecks
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
            failureGraceMs = prefs[KEY_FAILURE_GRACE_MS] ?: defaults.failureGraceMs,
            orientationLock = prefs[KEY_ORIENTATION]
                ?.let { stored -> OrientationLock.entries.firstOrNull { it.name == stored } }
                ?: defaults.orientationLock,
            soundMode = prefs[KEY_SOUND_MODE]
                ?.let { stored -> SoundMode.entries.firstOrNull { it.name == stored } }
                // An install that had the viewer's sound on comes back rotating,
                // which is what that switch meant.
                ?: if (prefs[KEY_LEGACY_VIEWER_SOUND] == true) SoundMode.ROTATING else defaults.soundMode,
            alertsEnabled = prefs[KEY_ALERTS_ENABLED] ?: defaults.alertsEnabled,
            keepScreenOn = prefs[KEY_KEEP_SCREEN_ON] ?: defaults.keepScreenOn,
            talkbackVolume = prefs[KEY_TALKBACK_VOLUME] ?: defaults.talkbackVolume,
            acknowledgedReadinessChecks = prefs[KEY_ACKNOWLEDGED_READINESS]
                ?: defaults.acknowledgedReadinessChecks,
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
        val KEY_FAILURE_GRACE_MS = longPreferencesKey("failure_grace_ms")
        val KEY_ORIENTATION = stringPreferencesKey("orientation_lock")
        val KEY_SOUND_MODE = stringPreferencesKey("sound_mode")
        val KEY_ALERTS_ENABLED = booleanPreferencesKey("alerts_enabled")
        val KEY_LEGACY_VIEWER_SOUND = booleanPreferencesKey("viewer_sound")
        val KEY_LEGACY_LISTEN_CAMERA = stringPreferencesKey("listen_camera_id")
        val KEY_KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val KEY_TALKBACK_VOLUME = floatPreferencesKey("talkback_volume")
        val KEY_ACKNOWLEDGED_READINESS = stringSetPreferencesKey("acknowledged_readiness_checks")
    }
}

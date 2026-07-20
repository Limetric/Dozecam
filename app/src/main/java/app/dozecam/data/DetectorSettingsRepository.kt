package app.dozecam.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Wake-on-sound tuning. Every nursery (and white noise machine) is different,
 * so all three knobs are user-adjustable.
 */
data class DetectorSettings(
    /** Normalized RMS level (0..1) that counts as "loud". */
    val threshold: Float = 0.10f,
    /** How long the level must stay loud before triggering. */
    val sustainMs: Long = 1_500,
    /** How long the level must stay quiet after a trigger before re-arming. */
    val quietMs: Long = 10_000,
)

interface DetectorSettingsStore {
    val settings: Flow<DetectorSettings>
    suspend fun update(settings: DetectorSettings)
}

class DetectorSettingsRepository(
    private val dataStore: DataStore<Preferences>,
) : DetectorSettingsStore {

    override val settings: Flow<DetectorSettings> = dataStore.data.map { prefs ->
        val defaults = DetectorSettings()
        DetectorSettings(
            threshold = prefs[KEY_THRESHOLD] ?: defaults.threshold,
            sustainMs = prefs[KEY_SUSTAIN_MS] ?: defaults.sustainMs,
            quietMs = prefs[KEY_QUIET_MS] ?: defaults.quietMs,
        )
    }

    override suspend fun update(settings: DetectorSettings) {
        dataStore.edit { prefs ->
            prefs[KEY_THRESHOLD] = settings.threshold
            prefs[KEY_SUSTAIN_MS] = settings.sustainMs
            prefs[KEY_QUIET_MS] = settings.quietMs
        }
    }

    private companion object {
        val KEY_THRESHOLD = floatPreferencesKey("detector_threshold")
        val KEY_SUSTAIN_MS = longPreferencesKey("detector_sustain_ms")
        val KEY_QUIET_MS = longPreferencesKey("detector_quiet_ms")
    }
}

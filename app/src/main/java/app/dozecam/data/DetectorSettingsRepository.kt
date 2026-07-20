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

    /** Atomic read-modify-write; rapid successive updates cannot clobber each other. */
    suspend fun update(transform: (DetectorSettings) -> DetectorSettings)
}

class DetectorSettingsRepository(
    private val dataStore: DataStore<Preferences>,
) : DetectorSettingsStore {

    override val settings: Flow<DetectorSettings> = dataStore.data.map(::settingsFrom)

    override suspend fun update(transform: (DetectorSettings) -> DetectorSettings) {
        dataStore.edit { prefs ->
            val next = transform(settingsFrom(prefs))
            prefs[KEY_THRESHOLD] = next.threshold
            prefs[KEY_SUSTAIN_MS] = next.sustainMs
            prefs[KEY_QUIET_MS] = next.quietMs
        }
    }

    private fun settingsFrom(prefs: Preferences): DetectorSettings {
        val defaults = DetectorSettings()
        return DetectorSettings(
            threshold = prefs[KEY_THRESHOLD] ?: defaults.threshold,
            sustainMs = prefs[KEY_SUSTAIN_MS] ?: defaults.sustainMs,
            quietMs = prefs[KEY_QUIET_MS] ?: defaults.quietMs,
        )
    }

    private companion object {
        val KEY_THRESHOLD = floatPreferencesKey("detector_threshold")
        val KEY_SUSTAIN_MS = longPreferencesKey("detector_sustain_ms")
        val KEY_QUIET_MS = longPreferencesKey("detector_quiet_ms")
    }
}

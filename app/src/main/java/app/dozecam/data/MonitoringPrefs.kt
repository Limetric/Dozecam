package app.dozecam.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The URL the monitoring service is actively watching. Persisted so a
 * START_STICKY restart after a process kill resumes the same camera even if
 * the user has since selected a different one. Cleared on deliberate stop.
 */
class MonitoringPrefs(private val dataStore: DataStore<Preferences>) {

    val activeMonitoringUrl: Flow<String?> =
        dataStore.data.map { it[KEY_ACTIVE_URL] }

    suspend fun setActiveMonitoringUrl(url: String) {
        dataStore.edit { it[KEY_ACTIVE_URL] = url }
    }

    suspend fun clearActiveMonitoringUrl() {
        dataStore.edit { it.remove(KEY_ACTIVE_URL) }
    }

    private companion object {
        val KEY_ACTIVE_URL = stringPreferencesKey("active_monitoring_url")
    }
}

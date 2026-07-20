package app.dozecam.data

import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The URL the monitoring service is actively watching. Persisted (encrypted,
 * alongside the camera list — it embeds the same stream token) so a
 * START_STICKY restart after a process kill resumes the same camera even if
 * the user has since selected a different one. Cleared on deliberate stop.
 */
class MonitoringPrefs(private val securePrefs: SharedPreferences) {

    fun activeMonitoringUrl(): String? = securePrefs.getString(KEY_ACTIVE_URL, null)

    /** Durable write off the caller's thread: this value exists precisely to survive process death. */
    suspend fun setActiveMonitoringUrl(url: String) {
        withContext(Dispatchers.IO) {
            securePrefs.edit().putString(KEY_ACTIVE_URL, url).commit()
        }
    }

    /**
     * Async on purpose (callable from onDestroy): if the process dies before
     * this lands, the stale value is harmless — deliberate stops do not
     * trigger sticky restarts, and user-initiated starts always carry the
     * URL in the intent.
     */
    fun clearActiveMonitoringUrl() {
        securePrefs.edit().remove(KEY_ACTIVE_URL).apply()
    }

    private companion object {
        const val KEY_ACTIVE_URL = "active_monitoring_url"
    }
}

package app.dozecam.data

import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The console-side identity of a camera onboarded through Protect. Present
 * only for cameras Protect discovered: a manually entered stream URL has no
 * console behind it, so it can only ever be played over RTSP.
 */
@Serializable
data class ProtectStream(
    val cameraId: String,
    /** Quality channel on the console; 1 is Medium, the nursery default. */
    val channel: Int,
    /**
     * The console that issued [cameraId]. Only one console's credentials are
     * stored at a time, so onboarding a second one leaves the first one's
     * cameras behind: without this, their ids would be negotiated against the
     * new console, which knows nothing about them. Null for cameras stored
     * before this was recorded.
     */
    val consoleHost: String? = null,
)

@Serializable
data class Camera(
    val id: String,
    val name: String,
    val url: String,
    /**
     * Absent for manually added cameras, and for anything onboarded before
     * livestream support existed — both correctly fall back to RTSP.
     */
    val protect: ProtectStream? = null,
    /**
     * Whether this camera takes part at all: enabled cameras are the ones the
     * viewer shows and the ones [app.dozecam.monitoring.MonitoringService]
     * listens to. Defaults true so cameras stored before the flag existed —
     * and every fresh Protect import — stay live across the upgrade.
     */
    val enabled: Boolean = true,
)

interface CameraStore {
    val cameras: Flow<List<Camera>>

    /** The enabled subset, in list order: what the viewer shows and the monitor listens to. */
    val enabledCameras: Flow<List<Camera>>

    suspend fun upsert(camera: Camera)
    suspend fun remove(id: String)
    suspend fun setEnabled(id: String, enabled: Boolean)
}

/**
 * Camera list at rest in [securePrefs] — encrypted in production, because the
 * RTSP URLs embed bearer-style Protect stream tokens. Selection lives in the
 * plain DataStore (an id is not a secret). Data written by v0.1–v0.4 (plain
 * DataStore) is migrated into [securePrefs] on first read and scrubbed.
 */
class CameraRepository(
    private val securePrefs: SharedPreferences,
    private val dataStore: DataStore<Preferences>,
) : CameraStore {

    private val state = MutableStateFlow<List<Camera>>(emptyList())
    private val migration = Mutex()
    private var loaded = false

    override val cameras: Flow<List<Camera>> = state.onStart { ensureLoaded() }

    override val enabledCameras: Flow<List<Camera>> =
        cameras.map { list -> list.filter { it.enabled } }


    override suspend fun upsert(camera: Camera) {
        mutate { current ->
            val index = current.indexOfFirst { it.id == camera.id }
            if (index >= 0) {
                current.toMutableList().also { it[index] = camera }
            } else {
                current + camera
            }
        }
    }

    override suspend fun remove(id: String) {
        mutate { current -> current.filterNot { it.id == id } }
    }

    override suspend fun setEnabled(id: String, enabled: Boolean) {
        mutate { current ->
            current.map { if (it.id == id) it.copy(enabled = enabled) else it }
        }
    }

    private suspend fun mutate(transform: (List<Camera>) -> List<Camera>) {
        ensureLoaded()
        migration.withLock {
            val next = transform(state.value)
            // Durable before returning, matching the DataStore semantics this
            // storage replaced; the fsync happens off the caller's thread.
            withContext(Dispatchers.IO) {
                securePrefs.edit().putString(PREF_CAMERAS, Json.encodeToString(next)).commit()
            }
            state.value = next
        }
    }

    private suspend fun ensureLoaded() {
        if (loaded) return
        migration.withLock {
            if (loaded) return
            val secureJson = securePrefs.getString(PREF_CAMERAS, null)
            state.value = if (secureJson != null) {
                decode(secureJson)
            } else {
                migrateFromDataStore()
            }
            loaded = true
        }
    }

    /** v0.1–v0.4 kept cameras (or a single URL) in the plain DataStore. */
    private suspend fun migrateFromDataStore(): List<Camera> {
        val prefs = dataStore.data.first()
        val migrated = prefs[KEY_LEGACY_CAMERAS]?.let(::decode)
            ?: prefs[KEY_LEGACY_STREAM_URL]?.takeIf { it.isNotBlank() }
                ?.let { listOf(Camera(LEGACY_ID, "Camera 1", it)) }
            ?: emptyList()
        // Nothing to migrate → write nothing. An empty marker written while
        // running on the degraded plain fallback must never later masquerade
        // as "the user has zero cameras" and clobber healthy encrypted data.
        if (migrated.isEmpty() && prefs[KEY_LEGACY_ACTIVE_URL] == null) {
            return emptyList()
        }
        // Synchronous commit: the destination must be durable before the only
        // other copy is scrubbed, or a crash in between loses every camera.
        // The commit (encryption + fsync) runs on IO — first collection may
        // come from the main dispatcher.
        val editor = securePrefs.edit()
            .putString(PREF_CAMERAS, Json.encodeToString(migrated))
        // The legacy active monitoring URL carries the same stream token, so it
        // is lifted out of the plain DataStore rather than left behind. Nothing
        // reads it any more — which camera is monitored is now Camera.enabled —
        // but scrubbing the plaintext copy still matters.
        prefs[KEY_LEGACY_ACTIVE_URL]?.let { editor.putString(PREF_ACTIVE_URL, it) }
        if (withContext(Dispatchers.IO) { editor.commit() }) {
            dataStore.edit {
                it.remove(KEY_LEGACY_CAMERAS)
                it.remove(KEY_LEGACY_STREAM_URL)
                it.remove(KEY_LEGACY_ACTIVE_URL)
            }
        }
        return migrated
    }

    private fun decode(json: String): List<Camera> =
        runCatching { Json.decodeFromString<List<Camera>>(json) }.getOrElse { emptyList() }

    companion object {
        const val LEGACY_ID = "legacy"
        private const val PREF_CAMERAS = "cameras"
        private const val PREF_ACTIVE_URL = "active_monitoring_url"
        private val KEY_LEGACY_CAMERAS = stringPreferencesKey("cameras")
        private val KEY_LEGACY_STREAM_URL = stringPreferencesKey("stream_url")
        private val KEY_LEGACY_ACTIVE_URL = stringPreferencesKey("active_monitoring_url")
    }
}

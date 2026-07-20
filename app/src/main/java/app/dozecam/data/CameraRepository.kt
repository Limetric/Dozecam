package app.dozecam.data

import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class Camera(
    val id: String,
    val name: String,
    val url: String,
)

interface CameraStore {
    val cameras: Flow<List<Camera>>

    /** Falls back to the first camera when nothing (or a removed id) is selected. */
    val selectedCamera: Flow<Camera?>
    suspend fun upsert(camera: Camera)
    suspend fun remove(id: String)
    suspend fun select(id: String)
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

    override val selectedCamera: Flow<Camera?> = combine(
        cameras,
        dataStore.data.map { it[KEY_SELECTED_ID] },
    ) { list, selectedId ->
        list.firstOrNull { it.id == selectedId } ?: list.firstOrNull()
    }

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
        dataStore.edit { prefs ->
            if (prefs[KEY_SELECTED_ID] == id) prefs.remove(KEY_SELECTED_ID)
        }
    }

    override suspend fun select(id: String) {
        dataStore.edit { it[KEY_SELECTED_ID] = id }
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
        // Synchronous commit: the destination must be durable before the only
        // other copy is scrubbed, or a crash in between loses every camera.
        // The commit (encryption + fsync) runs on IO — first collection may
        // come from the main dispatcher.
        val editor = securePrefs.edit()
            .putString(PREF_CAMERAS, Json.encodeToString(migrated))
        // The active monitoring URL carries the same stream token and moved
        // out of the plain DataStore at the same time; migrate it together.
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
        private val KEY_SELECTED_ID = stringPreferencesKey("selected_camera_id")
    }
}

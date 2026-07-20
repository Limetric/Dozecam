package app.dozecam.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
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

class CameraRepository(private val dataStore: DataStore<Preferences>) : CameraStore {

    override val cameras: Flow<List<Camera>> =
        dataStore.data.map { prefs -> camerasFrom(prefs) }

    override val selectedCamera: Flow<Camera?> = combine(
        cameras,
        dataStore.data.map { it[KEY_SELECTED_ID] },
    ) { list, selectedId ->
        list.firstOrNull { it.id == selectedId } ?: list.firstOrNull()
    }

    override suspend fun upsert(camera: Camera) {
        dataStore.edit { prefs ->
            val current = camerasFrom(prefs)
            val index = current.indexOfFirst { it.id == camera.id }
            val next = if (index >= 0) {
                current.toMutableList().also { it[index] = camera }
            } else {
                current + camera
            }
            prefs.writeCameras(next)
        }
    }

    override suspend fun remove(id: String) {
        dataStore.edit { prefs ->
            prefs.writeCameras(camerasFrom(prefs).filterNot { it.id == id })
            if (prefs[KEY_SELECTED_ID] == id) {
                prefs.remove(KEY_SELECTED_ID)
            }
        }
    }

    override suspend fun select(id: String) {
        dataStore.edit { it[KEY_SELECTED_ID] = id }
    }

    private fun camerasFrom(prefs: Preferences): List<Camera> {
        val json = prefs[KEY_CAMERAS]
        if (json != null) {
            return runCatching { Json.decodeFromString<List<Camera>>(json) }
                .getOrElse { emptyList() }
        }
        // v0.1–v0.3 stored a single URL; surface it as the first camera until
        // any camera write persists the list form.
        val legacyUrl = prefs[KEY_LEGACY_STREAM_URL]?.takeIf { it.isNotBlank() }
        return if (legacyUrl != null) listOf(Camera(LEGACY_ID, "Camera 1", legacyUrl)) else emptyList()
    }

    private fun MutablePreferences.writeCameras(cameras: List<Camera>) {
        this[KEY_CAMERAS] = Json.encodeToString(cameras)
    }

    companion object {
        const val LEGACY_ID = "legacy"
        private val KEY_CAMERAS = stringPreferencesKey("cameras")
        private val KEY_SELECTED_ID = stringPreferencesKey("selected_camera_id")
        private val KEY_LEGACY_STREAM_URL = stringPreferencesKey("stream_url")
    }
}

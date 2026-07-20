package app.dozecam.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

interface StreamSettings {
    val streamUrl: Flow<String>
    suspend fun setStreamUrl(url: String)
}

class StreamSettingsRepository(private val dataStore: DataStore<Preferences>) : StreamSettings {
    override val streamUrl: Flow<String> =
        dataStore.data.map { it[KEY_STREAM_URL] ?: "" }

    override suspend fun setStreamUrl(url: String) {
        dataStore.edit { it[KEY_STREAM_URL] = url }
    }

    private companion object {
        val KEY_STREAM_URL = stringPreferencesKey("stream_url")
    }
}

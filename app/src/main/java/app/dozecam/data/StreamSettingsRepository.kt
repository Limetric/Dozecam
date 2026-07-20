package app.dozecam.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

interface StreamSettings {
    val streamUrl: Flow<String>
    suspend fun setStreamUrl(url: String)
}

class StreamSettingsRepository(private val context: Context) : StreamSettings {
    override val streamUrl: Flow<String> =
        context.dataStore.data.map { it[KEY_STREAM_URL] ?: "" }

    override suspend fun setStreamUrl(url: String) {
        context.dataStore.edit { it[KEY_STREAM_URL] = url }
    }

    private companion object {
        val KEY_STREAM_URL = stringPreferencesKey("stream_url")
    }
}

package app.dozecam.protect

import android.content.Context
import android.content.SharedPreferences

data class ProtectCredentials(
    val host: String,
    val username: String,
    val password: String,
)

interface CredentialsStore {
    fun save(credentials: ProtectCredentials)
    fun load(): ProtectCredentials?
    fun clear()
}

/**
 * Console credentials at rest, encrypted with an Android Keystore master key.
 * They are sent only to the user's own console over the LAN.
 */
class EncryptedCredentialsStore(context: Context) : CredentialsStore {

    private val prefs: SharedPreferences by lazy {
        securePreferences(context, "protect_credentials")
    }

    override fun save(credentials: ProtectCredentials) {
        prefs.edit()
            .putString(KEY_HOST, credentials.host)
            .putString(KEY_USERNAME, credentials.username)
            .putString(KEY_PASSWORD, credentials.password)
            .apply()
    }

    override fun load(): ProtectCredentials? {
        val host = prefs.getString(KEY_HOST, null) ?: return null
        val username = prefs.getString(KEY_USERNAME, null) ?: return null
        val password = prefs.getString(KEY_PASSWORD, null) ?: return null
        return ProtectCredentials(host, username, password)
    }

    override fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val KEY_HOST = "host"
        const val KEY_USERNAME = "username"
        const val KEY_PASSWORD = "password"
    }
}

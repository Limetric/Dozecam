package app.dozecam.protect

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Encrypted SharedPreferences with a plain-file fallback when the Android
 * Keystore is unavailable or corrupted (it happens in the wild). App data
 * stays app-private and unbackedup either way; encryption is defense in depth.
 */
fun securePreferences(context: Context, name: String): SharedPreferences =
    try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        val encrypted = EncryptedSharedPreferences.create(
            context,
            name,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
        // A previous run may have degraded to the plain fallback (transient
        // Keystore failure); fold anything written there back in so those
        // changes do not silently vanish once encryption recovers.
        reconcileFallback(encrypted, context.getSharedPreferences(fallbackName(name), Context.MODE_PRIVATE))
        encrypted
    } catch (e: Exception) {
        Log.w("Dozecam", "Encrypted preferences unavailable, falling back to plain", e)
        context.getSharedPreferences(fallbackName(name), Context.MODE_PRIVATE)
    }

private fun fallbackName(name: String) = "${name}_plain"

/**
 * Fallback entries win conflicts: the fallback file only receives writes
 * while encryption is down, and it is emptied on every healthy startup, so
 * anything found in it is strictly newer than its encrypted counterpart.
 * Copy-then-clear is idempotent — if the clear fails, the next startup
 * re-copies identical values and retries, so plaintext never lingers.
 */
internal fun reconcileFallback(target: SharedPreferences, fallback: SharedPreferences) {
    val entries = fallback.all
    if (entries.isEmpty()) return
    val editor = target.edit()
    for ((key, value) in entries) {
        when (value) {
            is String -> editor.putString(key, value)
            is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
            is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
        }
    }
    // Durable copy before clearing the only other copy.
    if (editor.commit()) {
        fallback.edit().clear().commit()
    }
}

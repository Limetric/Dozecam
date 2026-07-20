package app.dozecam.protect

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import okhttp3.OkHttpClient

/**
 * Thrown on first contact with a console whose certificate is not yet
 * pinned; carries the fingerprint for the user to confirm.
 */
class UntrustedCertificateException(val fingerprint: String) :
    CertificateException("Unpinned certificate: $fingerprint")

/** SHA-256 fingerprint, uppercase hex pairs joined with ':'. */
fun X509Certificate.sha256Fingerprint(): String =
    MessageDigest.getInstance("SHA-256")
        .digest(encoded)
        .joinToString(":") { "%02X".format(it) }

/**
 * Trust-on-first-use for self-signed Protect consoles: the server identity is
 * the certificate fingerprint the user confirmed, not a CA chain or hostname.
 */
class TofuTrustManager(private val pinnedFingerprint: String?) : X509TrustManager {

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
        throw CertificateException("Client certificates are not supported")
    }

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        val fingerprint = chain.firstOrNull()?.sha256Fingerprint()
            ?: throw CertificateException("Empty certificate chain")
        when (pinnedFingerprint) {
            null -> throw UntrustedCertificateException(fingerprint)
            fingerprint -> Unit
            else -> throw CertificateException(
                "Certificate changed: pinned $pinnedFingerprint, presented $fingerprint",
            )
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/** Stores the confirmed certificate fingerprint per console host. */
class TofuTrustStore(private val dataStore: DataStore<Preferences>) {

    fun fingerprintFor(host: String): Flow<String?> =
        dataStore.data.map { it[keyFor(host)] }

    suspend fun pin(host: String, fingerprint: String) {
        dataStore.edit { it[keyFor(host)] = fingerprint }
    }

    private fun keyFor(host: String) = stringPreferencesKey("tofu_fingerprint_$host")
}

/**
 * HTTPS client for one console. Hostname verification is intentionally
 * disabled: self-signed console certificates never match their LAN IP, and
 * server identity is established by the pinned fingerprint instead.
 */
fun protectHttpClient(pinnedFingerprint: String?): OkHttpClient {
    val trustManager = TofuTrustManager(pinnedFingerprint)
    val sslContext = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf(trustManager), null)
    }
    return OkHttpClient.Builder()
        .sslSocketFactory(sslContext.socketFactory, trustManager)
        .hostnameVerifier { _, _ -> true }
        .build()
}

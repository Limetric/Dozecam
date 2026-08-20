package app.dozecam.protect

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runInterruptible
import okhttp3.OkHttpClient

/**
 * Thrown on first contact with a console whose certificate is not yet
 * pinned; carries the fingerprint for the user to confirm.
 */
class UntrustedCertificateException(val fingerprint: String) :
    CertificateException("Unpinned certificate: $fingerprint")

/**
 * Thrown when an endpoint presents a certificate other than the pinned one.
 *
 * Carries both fingerprints because the user has to be able to tell the two
 * apart: a console reissues its certificate for ordinary reasons — a firmware
 * update, a factory reset, remote access handing it a real certificate — and
 * an impostor on the network looks exactly the same from here.
 */
class ChangedCertificateException(
    val pinnedFingerprint: String,
    val fingerprint: String,
) : CertificateException("Certificate changed: pinned $pinnedFingerprint, presented $fingerprint")

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
            else -> throw ChangedCertificateException(pinnedFingerprint, fingerprint)
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/**
 * Stores the confirmed certificate fingerprint per endpoint.
 *
 * The key is an endpoint, not a host, because a UniFi console is not one TLS
 * server: the UniFi OS UI on 443 and the Protect media ports (7441/7443)
 * present *different* self-signed certificates. Pinning per host would judge
 * the media endpoint against the UI's certificate and reject every stream.
 */
class TofuTrustStore(private val dataStore: DataStore<Preferences>) {

    fun fingerprintFor(endpoint: String): Flow<String?> =
        dataStore.data.map { it[keyFor(endpoint)] }

    suspend fun pin(endpoint: String, fingerprint: String) {
        dataStore.edit { it[keyFor(endpoint)] = fingerprint }
    }

    /**
     * Forgets the media endpoints learned on [host], leaving the console's own
     * pin — the one the user confirmed — alone.
     *
     * Those pins were never shown to anyone: they were learned silently on the
     * strength of a console connection that had already been verified. So the
     * moment to learn them again is a sign-in that verifies the console again.
     * Without this, a media port that reissues its certificate refuses every
     * stream for good, and no screen in the app can clear it.
     */
    suspend fun forgetLearnedEndpoints(host: String) {
        val prefix = "$KEY_PREFIX$host:"
        dataStore.edit { preferences ->
            preferences.asMap().keys
                .filter { it.name.startsWith(prefix) }
                .forEach { preferences -= it }
        }
    }

    private fun keyFor(endpoint: String) = stringPreferencesKey("$KEY_PREFIX$endpoint")

    private companion object {
        const val KEY_PREFIX = "tofu_fingerprint_"
    }
}

/** `host:port`, the key identifying one TLS endpoint on a console. */
internal fun endpointKey(host: String, port: Int): String = "$host:$port"

/**
 * Reads the certificate a console endpoint presents, trusting nothing.
 *
 * Used to learn a media port's certificate the first time a stream is opened.
 * That first sight is only reached by way of a URL the *already-pinned*
 * console minted over its own verified connection, so the console we trust is
 * what vouches for the endpoint; every later connection is pinned to the
 * fingerprint learned here and a change is refused like any other.
 */
internal suspend fun probeCertificateFingerprint(host: String, port: Int): String =
    runInterruptible(Dispatchers.IO) {
        val context = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(ProbeTrustManager()), null)
        }
        (context.socketFactory.createSocket() as SSLSocket).use { socket ->
            socket.connect(InetSocketAddress(host, port), PROBE_TIMEOUT_MS)
            socket.soTimeout = PROBE_TIMEOUT_MS
            socket.startHandshake()
            val certificate = socket.session.peerCertificates.firstOrNull() as? X509Certificate
                ?: throw CertificateException("Endpoint $host:$port presented no certificate")
            certificate.sha256Fingerprint()
        }
    }

private const val PROBE_TIMEOUT_MS = 10_000

/** Accepts any certificate; only ever used to *read* one, never to exchange data. */
private class ProbeTrustManager : X509TrustManager {
    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
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

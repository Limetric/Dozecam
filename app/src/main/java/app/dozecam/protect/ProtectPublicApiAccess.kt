package app.dozecam.protect

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/**
 * The signed-in console's public Integration API, assembled on demand.
 *
 * Everything needed to talk to it — the address, the pinned certificate and the
 * API key — is stored rather than held, and any of the three can change while
 * the viewer is open: re-onboarding rewrites the credentials underneath us, and
 * a key minted for the previous console must never be replayed at the new one.
 * So the client is built per call rather than cached, which costs an encrypted
 * preferences read and buys never being wrong about whose console this is.
 */
class ProtectPublicApiAccess(
    private val credentials: CredentialsStore,
    private val trustStore: TofuTrustStore,
    private val clientFactory: (String?) -> OkHttpClient = ::protectHttpClient,
) {
    /**
     * Runs [block] against the console, or returns null when there is no
     * console signed in, no API key, or an address that cannot be parsed.
     */
    suspend fun <T> withClient(
        block: suspend (api: ProtectPublicApiClient, apiKey: String) -> T,
    ): T? {
        val saved = withContext(Dispatchers.IO) { credentials.load() } ?: return null
        val apiKey = saved.apiKey ?: return null
        val baseUrl = ProtectApiClient.baseUrlFor(saved.host) ?: return null
        val fingerprint = trustStore.fingerprintFor(baseUrl.host).first()
        return block(ProtectPublicApiClient(baseUrl, clientFactory(fingerprint)), apiKey)
    }

    /** The console this would talk to, or null when none is signed in. */
    suspend fun consoleHost(): String? =
        withContext(Dispatchers.IO) { credentials.load()?.host }

    suspend fun hasApiKey(): Boolean =
        withContext(Dispatchers.IO) { credentials.load()?.apiKey != null }
}

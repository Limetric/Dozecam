package app.dozecam.protect

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

/**
 * Turns a stored console login into a ready-to-open livestream WebSocket.
 *
 * The console mints a single-use token per negotiation, so every connection —
 * including each reconnect — comes back through here. The login session behind
 * it is reused until the console rejects it, mirroring the onboarding flow's
 * re-authenticate-once-on-401 behaviour rather than logging in per attempt.
 */
class ProtectLivestreamProvider(
    private val credentials: CredentialsStore,
    private val trustStore: TofuTrustStore,
    private val clientFactory: (String?) -> OkHttpClient = ::protectHttpClient,
) {
    /** A negotiated socket URL and the TOFU-pinned client that must open it. */
    data class Connection(val url: String, val client: OkHttpClient)

    private val mutex = Mutex()
    private var session: ProtectSession? = null

    /**
     * Whose session [session] is. Re-onboarding to another console or account
     * rewrites the stored credentials underneath us, and a cookie minted for
     * the previous console must never be replayed at the new one.
     */
    private var sessionOwner: Pair<String, String>? = null

    suspend fun connect(cameraId: String, channel: Int): Connection = mutex.withLock {
        val saved = credentials.load()
            ?: throw ProtectApiException("This camera needs a Protect console sign-in")
        val baseUrl = ProtectApiClient.baseUrlFor(saved.host)
            ?: throw ProtectApiException("Stored console address ${saved.host} is unusable")
        val owner = saved.host to saved.username
        if (owner != sessionOwner) {
            session = null
            sessionOwner = owner
        }
        val fingerprint = trustStore.fingerprintFor(baseUrl.host).first()
        val client = clientFactory(fingerprint)
        val api = ProtectApiClient(baseUrl, client)

        val url = try {
            api.livestreamUrl(currentSession(api, saved), cameraId, channel)
        } catch (e: ProtectApiException) {
            if (e.statusCode != 401) throw e
            // The session aged out while the monitor sat open; one fresh login
            // beats stranding the user on an error that retrying cannot clear.
            session = null
            api.livestreamUrl(currentSession(api, saved), cameraId, channel)
        }
        // The socket lands on a media port with its own certificate, so it
        // needs its own pin — [client] is pinned to the UniFi OS UI's.
        Connection(url, clientFactory(mediaFingerprintFor(url)))
    }

    /**
     * The pinned fingerprint for the media endpoint the negotiated URL points
     * at, learning it on first use. Reaching this endpoint at all required the
     * pinned console to mint the URL, so the console vouches for it; from then
     * on it is pinned like any other.
     */
    private suspend fun mediaFingerprintFor(url: String): String {
        // HttpUrl parses only http(s); map the WebSocket schemes the way
        // OkHttp itself does when it opens the upgrade request.
        val parsed = url
            .replaceFirst(Regex("^wss://", RegexOption.IGNORE_CASE), "https://")
            .replaceFirst(Regex("^ws://", RegexOption.IGNORE_CASE), "http://")
            .toHttpUrlOrNull()
            ?: throw ProtectApiException("Console returned an unusable livestream URL")
        val endpoint = endpointKey(parsed.host, parsed.port)
        trustStore.fingerprintFor(endpoint).first()?.let { return it }
        val learned = probeCertificateFingerprint(parsed.host, parsed.port)
        trustStore.pin(endpoint, learned)
        return learned
    }

    /** Drops the cached session; the next connect logs in again. */
    fun invalidate() {
        session = null
        sessionOwner = null
    }

    private suspend fun currentSession(
        api: ProtectApiClient,
        saved: ProtectCredentials,
    ): ProtectSession = session ?: api.login(saved.username, saved.password).also { session = it }
}

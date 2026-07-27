package app.dozecam.protect

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private class FakeCredentialsStore(private var credentials: ProtectCredentials?) :
    CredentialsStore {
    override fun save(credentials: ProtectCredentials) {
        this.credentials = credentials
    }

    override fun load(): ProtectCredentials? = credentials

    override fun clear() {
        credentials = null
    }
}

class ProtectLivestreamProviderTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var heldCertificate: HeldCertificate

    @Before
    fun setUp() {
        heldCertificate = HeldCertificate.Builder()
            .commonName("console")
            .addSubjectAlternativeName("127.0.0.1")
            .build()
        server = MockWebServer()
        server.useHttps(
            HandshakeCertificates.Builder().heldCertificate(heldCertificate).build()
                .sslSocketFactory(),
        )
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun host() = "127.0.0.1:${server.port}"

    /**
     * A store that already trusts both endpoints, so a test about sessions and
     * negotiation is not also a test of first-use certificate learning.
     */
    private suspend fun TestScope.pinnedStore(): TofuTrustStore = trustStore().also {
        it.pin("127.0.0.1", heldCertificate.certificate.sha256Fingerprint())
        it.pin("127.0.0.1:7443", "MEDIA:PIN")
    }

    private fun TestScope.trustStore(): TofuTrustStore = TofuTrustStore(
        PreferenceDataStoreFactory.create(scope = this) {
            File(temporaryFolder.newFolder(), "trust.preferences_pb")
        },
    )

    private fun provider(
        trustStore: TofuTrustStore,
        credentials: ProtectCredentials? = ProtectCredentials(host(), "user", "pass"),
    ) = ProtectLivestreamProvider(
        credentials = FakeCredentialsStore(credentials),
        trustStore = trustStore,
        clientFactory = { protectHttpClient(heldCertificate.certificate.sha256Fingerprint()) },
    )

    private fun loginResponse() = MockResponse.Builder()
        .code(200)
        .addHeader("Set-Cookie", "TOKEN=abc123; Path=/; HttpOnly")
        .addHeader("X-CSRF-Token", "csrf-1")
        .body("{}")
        .build()

    private fun livestreamResponse() = MockResponse.Builder()
        .code(200)
        .body("""{"url":"wss://unifi.internal:7443/ws/livestream?token=t1"}""")
        .build()

    @Test
    fun `negotiates a livestream url pointed at the console that answered`() = runTest {
        server.enqueue(loginResponse())
        server.enqueue(livestreamResponse())

        val connection = provider(pinnedStore()).connect("cam-1", channel = 1)

        assertEquals("wss://127.0.0.1:7443/ws/livestream?token=t1", connection.url)
    }

    @Test
    fun `reuses the session across connections instead of logging in again`() = runTest {
        server.enqueue(loginResponse())
        server.enqueue(livestreamResponse())
        server.enqueue(livestreamResponse())
        val provider = provider(pinnedStore())

        provider.connect("cam-1", channel = 1)
        provider.connect("cam-1", channel = 1)

        // login, negotiate, negotiate — no second login.
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `re-authenticates once when the console expires the session`() = runTest {
        server.enqueue(loginResponse())
        server.enqueue(MockResponse.Builder().code(401).body("expired").build())
        server.enqueue(loginResponse())
        server.enqueue(livestreamResponse())

        val connection = provider(pinnedStore()).connect("cam-1", channel = 1)

        assertEquals("wss://127.0.0.1:7443/ws/livestream?token=t1", connection.url)
        assertEquals(4, server.requestCount)
    }

    @Test
    fun `a second consecutive 401 is surfaced rather than retried forever`() = runTest {
        server.enqueue(loginResponse())
        server.enqueue(MockResponse.Builder().code(401).body("expired").build())
        server.enqueue(loginResponse())
        server.enqueue(MockResponse.Builder().code(401).body("expired").build())

        // Stay inside the test coroutine: the trust store's DataStore runs on
        // this scope's virtual clock, so blocking the test thread would deadlock.
        val failure = runCatching {
            provider(pinnedStore()).connect("cam-1", channel = 1)
        }.exceptionOrNull()

        assertEquals(401, (failure as ProtectApiException).statusCode)
    }

    @Test
    fun `a camera with no stored console sign-in is refused`() = runTest {
        val failure = runCatching {
            provider(trustStore(), credentials = null).connect("cam-1", channel = 1)
        }.exceptionOrNull()

        assertEquals(null, (failure as ProtectApiException).statusCode)
    }

    @Test
    fun `does not replay a session at a console the user re-onboarded to`() = runTest {
        server.enqueue(loginResponse())
        server.enqueue(livestreamResponse())
        val credentials = FakeCredentialsStore(ProtectCredentials(host(), "user", "pass"))
        val provider = ProtectLivestreamProvider(
            credentials = credentials,
            trustStore = pinnedStore(),
            clientFactory = { protectHttpClient(heldCertificate.certificate.sha256Fingerprint()) },
        )
        provider.connect("cam-1", channel = 1)

        // The user signs into a different account; the cookie minted for the
        // previous one must not be sent on its behalf.
        credentials.save(ProtectCredentials(host(), "other-user", "other-pass"))
        server.enqueue(loginResponse())
        server.enqueue(livestreamResponse())
        provider.connect("cam-1", channel = 1)

        val logins = (0 until server.requestCount).map { server.takeRequest() }
            .filter { it.target == "/api/auth/login" }
        assertEquals(2, logins.size)
        assertTrue(logins.last().body!!.utf8().contains("other-user"))
    }

    @Test
    fun `pins the media endpoint separately from the console UI`() = runTest {
        // A UniFi console is not one TLS server: the UI port and the media
        // ports present different self-signed certificates. Judging the media
        // socket against the UI's pin rejects every stream.
        val mediaCertificate = HeldCertificate.Builder()
            .commonName("protect-media")
            .addSubjectAlternativeName("127.0.0.1")
            .build()
        val mediaServer = MockWebServer()
        mediaServer.useHttps(
            HandshakeCertificates.Builder().heldCertificate(mediaCertificate).build()
                .sslSocketFactory(),
        )
        mediaServer.start()
        try {
            val store = trustStore()
            store.pin("127.0.0.1", heldCertificate.certificate.sha256Fingerprint())
            server.enqueue(loginResponse())
            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .body(
                        """{"url":"wss://unifi.internal:${mediaServer.port}/ws?token=t"}""",
                    )
                    .build(),
            )
            val provider = ProtectLivestreamProvider(
                credentials = FakeCredentialsStore(ProtectCredentials(host(), "user", "pass")),
                trustStore = store,
                clientFactory = ::protectHttpClient,
            )

            provider.connect("cam-1", channel = 1)

            val pinnedMedia = store.fingerprintFor("127.0.0.1:${mediaServer.port}").first()
            assertEquals(mediaCertificate.certificate.sha256Fingerprint(), pinnedMedia)
            // And it is genuinely a different pin from the console's.
            assertTrue(pinnedMedia != heldCertificate.certificate.sha256Fingerprint())
        } finally {
            mediaServer.close()
        }
    }

    @Test
    fun `reuses an already-pinned media fingerprint`() = runTest {
        val store = trustStore()
        store.pin("127.0.0.1", heldCertificate.certificate.sha256Fingerprint())
        store.pin("127.0.0.1:7443", "AA:BB")
        server.enqueue(loginResponse())
        server.enqueue(livestreamResponse())
        var requested: String? = null
        val provider = ProtectLivestreamProvider(
            credentials = FakeCredentialsStore(ProtectCredentials(host(), "user", "pass")),
            trustStore = store,
            clientFactory = { fingerprint ->
                // The console call comes first; capture the media one.
                if (fingerprint == "AA:BB") requested = fingerprint
                protectHttpClient(heldCertificate.certificate.sha256Fingerprint())
            },
        )

        provider.connect("cam-1", channel = 1)

        // A stored pin is used as-is; no probe, so an offline endpoint cannot
        // silently re-learn a substituted certificate.
        assertEquals("AA:BB", requested)
    }
}

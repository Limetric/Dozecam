package app.dozecam.protect

import app.dozecam.testing.Fixtures
import javax.net.ssl.SSLHandshakeException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ProtectApiClientTest {

    /**
     * `shared/fixtures/protect-api/cameras.expected.json`, which the public
     * client's test reads too: both clients must yield the same camera ids.
     */
    @Serializable
    private data class CamerasExpected(
        val name: String,
        val responses: Responses,
        val cameras: List<Camera>,
    ) {
        @Serializable
        data class Responses(val publicApi: String, val legacyApi: String)

        @Serializable
        data class Camera(val id: String, val publicApi: PublicApi, val legacyApi: LegacyApi)

        @Serializable
        data class PublicApi(val name: String?, val hasSpeaker: Boolean)

        @Serializable
        data class LegacyApi(val name: String, val preferredChannel: Channel?)

        @Serializable
        data class Channel(val name: String, val rtspAlias: String?)
    }

    /** `shared/fixtures/protect-api/legacy/expected.json`. */
    @Serializable
    private data class Expected(
        val livestream: Livestream,
        val rtspEnabled: RtspEnabled,
        val apiKey: ApiKey,
    ) {
        @Serializable
        data class Livestream(val name: String, val response: String, val url: String)

        @Serializable
        data class RtspEnabled(val name: String, val response: String, val rtspAlias: String)

        @Serializable
        data class ApiKey(val name: String, val response: String, val apiKey: String)
    }

    private val camerasExpected =
        Fixtures.decode<CamerasExpected>("protect-api/cameras.expected.json")
    private val expected = Fixtures.decode<Expected>("protect-api/legacy/expected.json")

    private fun jsonResponse(body: String): MockResponse = MockResponse.Builder()
        .code(200)
        .body(body)
        .build()

    private fun response(file: String): String = Fixtures.text("protect-api/legacy/$file")

    private lateinit var server: MockWebServer
    private lateinit var heldCertificate: HeldCertificate

    @Before
    fun setUp() {
        heldCertificate = HeldCertificate.Builder()
            .commonName("console")
            .addSubjectAlternativeName("localhost")
            .addSubjectAlternativeName("127.0.0.1")
            .build()
        val serverCertificates = HandshakeCertificates.Builder()
            .heldCertificate(heldCertificate)
            .build()
        server = MockWebServer()
        server.useHttps(serverCertificates.sslSocketFactory())
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    // 127.0.0.1 rather than localhost: dual-stack localhost lets OkHttp retry
    // the IPv6 address after a TLS failure and mask it as a ConnectException.
    private fun baseUrl() = server.url("/").newBuilder().host("127.0.0.1").build()

    private fun client(): ProtectApiClient = ProtectApiClient(
        baseUrl = baseUrl(),
        client = protectHttpClient(heldCertificate.certificate.sha256Fingerprint()),
    )

    private fun loginResponse(): MockResponse = MockResponse.Builder()
        .code(200)
        .addHeader("Set-Cookie", "TOKEN=abc123; Path=/; HttpOnly")
        .addHeader("X-CSRF-Token", "csrf-token-1")
        .body("{}")
        .build()

    @Test
    fun `livestream url is negotiated and re-pointed at the console address`() = runTest {
        server.enqueue(loginResponse())
        server.enqueue(jsonResponse(response(expected.livestream.response)))
        val api = client()
        val session = api.login("user", "pass")

        val url = api.livestreamUrl(session, "cam-1", channel = 1)

        assertEquals(expected.livestream.name, expected.livestream.url, url)
        server.takeRequest()
        val request = server.takeRequest()
        val target = request.target
        assertTrue(target.startsWith("/proxy/protect/api/ws/livestream?"))
        assertTrue(target.contains("camera=cam-1"))
        assertTrue(target.contains("channel=1"))
        assertTrue(target.contains("type=fmp4"))
        assertEquals("TOKEN=abc123", request.headers["Cookie"])
    }

    @Test
    fun `livestream negotiation failure names the console`() = runTest {
        server.enqueue(loginResponse())
        server.enqueue(MockResponse.Builder().code(404).body("nope").build())
        val api = client()
        val session = api.login("user", "pass")

        val failure = runCatching { api.livestreamUrl(session, "cam-1", channel = 1) }
            .exceptionOrNull()

        assertEquals(404, (failure as ProtectApiException).statusCode)
    }

    @Test
    fun `livestream response without a url is rejected`() = runTest {
        server.enqueue(loginResponse())
        server.enqueue(MockResponse.Builder().code(200).body("{}").build())
        val api = client()
        val session = api.login("user", "pass")

        val failure = runCatching { api.livestreamUrl(session, "cam-1", channel = 1) }
            .exceptionOrNull()

        assertTrue(failure is ProtectApiException)
    }

    @Test
    fun `login extracts the session cookie and csrf token`() = runTest {
        server.enqueue(loginResponse())

        val session = client().login("babycam", "secret")

        assertEquals("TOKEN=abc123", session.cookie)
        assertEquals("csrf-token-1", session.csrfToken)
        val request = server.takeRequest()
        assertEquals("/api/auth/login", request.url.encodedPath)
        assertTrue(request.body!!.utf8().contains("\"username\":\"babycam\""))
    }

    @Test
    fun `failed login surfaces an actionable error with the status code`() = runTest {
        server.enqueue(MockResponse.Builder().code(401).body("{}").build())

        try {
            client().login("babycam", "wrong")
            throw AssertionError("expected ProtectApiException")
        } catch (e: ProtectApiException) {
            assertEquals(401, e.statusCode)
        }
    }

    @Test
    fun `bootstrap parses cameras and sends the session headers`() = runTest {
        server.enqueue(loginResponse())
        val bootstrapResponse = Fixtures.text("protect-api/${camerasExpected.responses.legacyApi}")
        server.enqueue(jsonResponse(bootstrapResponse))

        val api = client()
        val session = api.login("babycam", "secret")
        val bootstrap = api.bootstrap(session)

        server.takeRequest() // login
        val request = server.takeRequest()
        assertEquals("TOKEN=abc123", request.headers["Cookie"])
        assertEquals("csrf-token-1", request.headers["X-CSRF-Token"])

        val case = camerasExpected
        // The same ids the public client reads from its camera list.
        assertEquals(case.name, case.cameras.map { it.id }, bootstrap.cameras.map { it.id })
        assertEquals(
            case.name,
            case.cameras.map { it.legacyApi.name },
            bootstrap.cameras.map { it.name },
        )
        assertEquals(
            case.name,
            case.cameras.map { it.legacyApi.preferredChannel?.name },
            bootstrap.cameras.map { it.preferredChannel?.name },
        )
        assertEquals(
            case.name,
            case.cameras.map { it.legacyApi.preferredChannel?.rtspAlias },
            bootstrap.cameras.map { it.preferredChannel?.rtspAlias },
        )
    }

    @Test
    fun `enableRtsp patches the channel and returns the updated camera`() = runTest {
        server.enqueue(loginResponse())
        server.enqueue(jsonResponse(response(expected.rtspEnabled.response)))

        val api = client()
        val session = api.login("babycam", "secret")
        val updated = api.enableRtsp(session, "cam1", 1)

        server.takeRequest() // login
        val request = server.takeRequest()
        assertEquals("PATCH", request.method)
        assertEquals("/proxy/protect/api/cameras/cam1", request.url.encodedPath)
        assertTrue(request.body!!.utf8().contains("\"isRtspEnabled\":true"))
        assertEquals(
            expected.rtspEnabled.name,
            expected.rtspEnabled.rtspAlias,
            updated.channels.first().rtspAlias,
        )
    }

    @Test
    fun `createApiKey posts the key name and unwraps the minted key`() = runTest {
        server.enqueue(loginResponse())
        server.enqueue(jsonResponse(response(expected.apiKey.response)))

        val api = client()
        val session = api.login("babycam", "secret")
        val key = api.createApiKey(session, "Dozecam")

        server.takeRequest() // login
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/proxy/users/api/v2/user/self/keys", request.url.encodedPath)
        assertEquals("TOKEN=abc123", request.headers["Cookie"])
        assertTrue(request.body!!.utf8().contains("\"name\":\"Dozecam\""))
        assertEquals(expected.apiKey.name, expected.apiKey.apiKey, key)
    }

    /** Pre-5.3 consoles have no such endpoint; non-owner accounts are refused. */
    @Test
    fun `createApiKey surfaces a console that will not issue one`() = runTest {
        server.enqueue(loginResponse())
        server.enqueue(MockResponse.Builder().code(403).body("{}").build())

        val api = client()
        val session = api.login("babycam", "secret")

        try {
            api.createApiKey(session, "Dozecam")
            throw AssertionError("expected ProtectApiException")
        } catch (e: ProtectApiException) {
            assertEquals(403, e.statusCode)
        }
    }

    @Test
    fun `rtsp urls target the console host on port 7447`() {
        assertEquals(
            "rtsp://127.0.0.1:7447/aliasM",
            client().rtspUrlFor("aliasM"),
        )
    }

    @Test
    fun `unpinned console fails with the fingerprint for the user to confirm`() = runTest {
        server.enqueue(loginResponse())
        val api = ProtectApiClient(baseUrl(), protectHttpClient(pinnedFingerprint = null))

        try {
            api.login("babycam", "secret")
            throw AssertionError("expected SSLHandshakeException")
        } catch (e: SSLHandshakeException) {
            val untrusted = generateSequence<Throwable>(e) { it.cause }
                .filterIsInstance<UntrustedCertificateException>()
                .firstOrNull()
            assertNotNull("cause chain should carry the fingerprint", untrusted)
            assertEquals(
                heldCertificate.certificate.sha256Fingerprint(),
                untrusted!!.fingerprint,
            )
        }
    }

    @Test
    fun `rtsp urls bracket ipv6 console hosts`() {
        val api = ProtectApiClient(
            baseUrl = ProtectApiClient.baseUrlFor("[2001:db8::1]")!!,
            client = protectHttpClient(null),
        )

        assertEquals("rtsp://[2001:db8::1]:7447/alias", api.rtspUrlFor("alias"))
    }

    @Test
    fun `baseUrlFor normalizes bare hosts and rejects garbage`() {
        assertEquals(
            "https://192.168.1.1/",
            ProtectApiClient.baseUrlFor("192.168.1.1").toString(),
        )
        assertEquals(
            "https://console.local:8443/",
            ProtectApiClient.baseUrlFor(" console.local:8443/ ").toString(),
        )
        assertEquals(null, ProtectApiClient.baseUrlFor(""))
        assertEquals(null, ProtectApiClient.baseUrlFor("not a host"))
        // Credentials must never bypass the TOFU TLS flow.
        assertEquals(null, ProtectApiClient.baseUrlFor("http://console.local"))
        // Bare IPv6 literals gain brackets.
        assertEquals(
            "https://[2001:db8::1]/",
            ProtectApiClient.baseUrlFor("2001:db8::1").toString(),
        )
    }
}

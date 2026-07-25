package app.dozecam.protect

import javax.net.ssl.SSLHandshakeException
import kotlinx.coroutines.test.runTest
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
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(
                    """
                    {
                      "unknownTopLevel": {"x": 1},
                      "cameras": [
                        {
                          "id": "cam1",
                          "name": "Nursery",
                          "unknownField": true,
                          "channels": [
                            {"id": 0, "name": "High", "isRtspEnabled": false, "rtspAlias": null},
                            {"id": 1, "name": "Medium", "isRtspEnabled": true, "rtspAlias": "aliasM"}
                          ]
                        }
                      ]
                    }
                    """.trimIndent(),
                )
                .build(),
        )

        val api = client()
        val session = api.login("babycam", "secret")
        val bootstrap = api.bootstrap(session)

        server.takeRequest() // login
        val request = server.takeRequest()
        assertEquals("TOKEN=abc123", request.headers["Cookie"])
        assertEquals("csrf-token-1", request.headers["X-CSRF-Token"])

        assertEquals(1, bootstrap.cameras.size)
        val camera = bootstrap.cameras.first()
        assertEquals("Nursery", camera.name)
        assertEquals("Medium", camera.preferredChannel?.name)
        assertEquals("aliasM", camera.preferredChannel?.rtspAlias)
    }

    @Test
    fun `enableRtsp patches the channel and returns the updated camera`() = runTest {
        server.enqueue(loginResponse())
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body(
                    """
                    {
                      "id": "cam1",
                      "name": "Nursery",
                      "channels": [
                        {"id": 1, "name": "Medium", "isRtspEnabled": true, "rtspAlias": "newAlias"}
                      ]
                    }
                    """.trimIndent(),
                )
                .build(),
        )

        val api = client()
        val session = api.login("babycam", "secret")
        val updated = api.enableRtsp(session, "cam1", 1)

        server.takeRequest() // login
        val request = server.takeRequest()
        assertEquals("PATCH", request.method)
        assertEquals("/proxy/protect/api/cameras/cam1", request.url.encodedPath)
        assertTrue(request.body!!.utf8().contains("\"isRtspEnabled\":true"))
        assertEquals("newAlias", updated.channels.first().rtspAlias)
    }

    @Test
    fun `createApiKey posts the key name and unwraps the minted key`() = runTest {
        server.enqueue(loginResponse())
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("""{"data": {"full_api_key": "abcdef123456", "id": "k1"}}""")
                .build(),
        )

        val api = client()
        val session = api.login("babycam", "secret")
        val key = api.createApiKey(session, "Dozecam")

        server.takeRequest() // login
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/proxy/users/api/v2/user/self/keys", request.url.encodedPath)
        assertEquals("TOKEN=abc123", request.headers["Cookie"])
        assertTrue(request.body!!.utf8().contains("\"name\":\"Dozecam\""))
        assertEquals("abcdef123456", key)
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

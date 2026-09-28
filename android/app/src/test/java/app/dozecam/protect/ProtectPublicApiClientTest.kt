package app.dozecam.protect

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ProtectPublicApiClientTest {

    private lateinit var server: MockWebServer
    private lateinit var heldCertificate: HeldCertificate

    @Before
    fun setUp() {
        heldCertificate = HeldCertificate.Builder()
            .commonName("console")
            .addSubjectAlternativeName("localhost")
            .addSubjectAlternativeName("127.0.0.1")
            .build()
        server = MockWebServer()
        server.useHttps(
            HandshakeCertificates.Builder()
                .heldCertificate(heldCertificate)
                .build()
                .sslSocketFactory(),
        )
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun baseUrl() = server.url("/").newBuilder().host("127.0.0.1").build()

    private fun client(): ProtectPublicApiClient = ProtectPublicApiClient(
        baseUrl = baseUrl(),
        client = protectHttpClient(heldCertificate.certificate.sha256Fingerprint()),
    )

    private fun jsonResponse(body: String): MockResponse = MockResponse.Builder()
        .code(200)
        .body(body)
        .build()

    @Test
    fun `cameras are read from the integration endpoint with the api key`() = runTest {
        server.enqueue(
            jsonResponse(
                """
                [
                  {"id": "cam1", "name": "Nursery", "unknownField": true},
                  {"id": "cam2", "name": null}
                ]
                """.trimIndent(),
            ),
        )

        val cameras = client().cameras("key-1")

        val request = server.takeRequest()
        assertEquals("/proxy/protect/integration/v1/cameras", request.url.encodedPath)
        assertEquals("key-1", request.headers["X-API-KEY"])
        assertEquals(listOf("cam1", "cam2"), cameras.map { it.id })
        assertEquals("Nursery", cameras.first().name)
        assertNull(cameras.last().name)
    }

    @Test
    fun `active streams are returned by quality and inactive ones dropped`() = runTest {
        server.enqueue(
            jsonResponse(
                """
                {
                  "high": "rtsps://192.168.1.1:7441/aliasH?enableSrtp",
                  "medium": "rtsps://192.168.1.1:7441/aliasM?enableSrtp",
                  "low": null
                }
                """.trimIndent(),
            ),
        )

        val streams = client().rtspsStreams("key-1", "cam1")

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals(
            "/proxy/protect/integration/v1/cameras/cam1/rtsps-stream",
            request.url.encodedPath,
        )
        assertEquals(setOf("high", "medium"), streams.keys)
    }

    @Test
    fun `creating a stream posts the requested qualities`() = runTest {
        server.enqueue(jsonResponse("""{"medium": "rtsps://192.168.1.1:7441/aliasM?enableSrtp"}"""))

        val streams = client().createRtspsStreams("key-1", "cam1", listOf("medium"))

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals(
            "/proxy/protect/integration/v1/cameras/cam1/rtsps-stream",
            request.url.encodedPath,
        )
        assertTrue(request.body!!.utf8().contains("\"qualities\":[\"medium\"]"))
        assertEquals("rtsps://192.168.1.1:7441/aliasM?enableSrtp", streams["medium"])
    }

    @Test
    fun `cameras report whether they carry a speaker`() = runTest {
        server.enqueue(
            jsonResponse(
                """
                [
                  {"id": "cam1", "featureFlags": {"hasSpeaker": true, "hasMic": true}},
                  {"id": "cam2", "featureFlags": {"hasSpeaker": false}},
                  {"id": "cam3"}
                ]
                """.trimIndent(),
            ),
        )

        val cameras = client().cameras("key-1")

        // A camera whose flags never arrived is treated as having no speaker:
        // offering talk-back and failing is worse than not offering it.
        assertEquals(listOf(true, false, false), cameras.map { it.hasSpeaker })
    }

    @Test
    fun `a talkback session is posted without a body and parsed`() = runTest {
        server.enqueue(
            jsonResponse(
                """
                {
                  "url": "rtp://192.168.1.12:7004",
                  "codec": "opus",
                  "samplingRate": 24000,
                  "bitsPerSample": 16
                }
                """.trimIndent(),
            ),
        )

        val session = client().talkbackSession("key-1", "cam1")

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals(
            "/proxy/protect/integration/v1/cameras/cam1/talkback-session",
            request.url.encodedPath,
        )
        assertEquals("key-1", request.headers["X-API-KEY"])
        assertEquals("", request.body?.utf8() ?: "")
        assertEquals("opus", session.codec)
        assertEquals(24000, session.samplingRate)
        assertEquals(16, session.bitsPerSample)
    }

    /**
     * The audio goes to the camera, not the console that described it, so the
     * address in the URL is the only thing that says where.
     */
    @Test
    fun `a talkback session exposes the camera's own address`() {
        val session = TalkbackSession(
            url = "rtp://192.168.1.12:7004",
            codec = "opus",
            samplingRate = 24000,
            bitsPerSample = 16,
        )

        assertEquals("192.168.1.12", session.host)
        assertEquals(7004, session.port)
    }

    @Test
    fun `a talkback url without a port falls back to 7004, and rubbish has no host`() {
        val portless = TalkbackSession("rtp://192.168.1.12", "opus", 24000, 16)
        assertEquals("192.168.1.12", portless.host)
        assertEquals(7004, portless.port)

        val rubbish = TalkbackSession("not a url", "opus", 24000, 16)
        assertNull(rubbish.host)
    }

    @Test
    fun `a camera without a speaker surfaces the console's refusal`() = runTest {
        server.enqueue(MockResponse.Builder().code(404).body("{}").build())

        try {
            client().talkbackSession("key-1", "cam-without-speaker")
            throw AssertionError("expected ProtectApiException")
        } catch (e: ProtectApiException) {
            assertEquals(404, e.statusCode)
        }
    }

    @Test
    fun `a rejected api key surfaces the status code`() = runTest {
        server.enqueue(MockResponse.Builder().code(401).body("{}").build())

        try {
            client().cameras("stale-key")
            throw AssertionError("expected ProtectApiException")
        } catch (e: ProtectApiException) {
            assertEquals(401, e.statusCode)
        }
    }

    /**
     * The console advertises its own host and the SRTP-flavoured RTSPS port;
     * neither survives the trip to the player, only the alias does.
     */
    @Test
    fun `stream urls keep the alias but re-point at the reachable console`() {
        val api = ProtectPublicApiClient(
            baseUrl = ProtectApiClient.baseUrlFor("192.168.1.50")!!,
            client = protectHttpClient(null),
        )

        assertEquals(
            "rtsp://192.168.1.50:7447/aliasM",
            api.streamUrlFor("rtsps://10.0.0.1:7441/aliasM?enableSrtp"),
        )
    }

    @Test
    fun `stream urls bracket ipv6 console hosts and reject unparseable input`() {
        val api = ProtectPublicApiClient(
            baseUrl = ProtectApiClient.baseUrlFor("[2001:db8::1]")!!,
            client = protectHttpClient(null),
        )

        assertEquals(
            "rtsp://[2001:db8::1]:7447/aliasM",
            api.streamUrlFor("rtsps://10.0.0.1:7441/aliasM"),
        )
        assertNull(api.streamUrlFor("rtsps://10.0.0.1:7441/"))
        assertNull(api.streamUrlFor("not a url"))
    }
}

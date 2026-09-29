package app.dozecam.protect

import app.dozecam.testing.Fixtures
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
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

    /**
     * `shared/fixtures/protect-api/cameras.expected.json`, which the legacy
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

    /** `shared/fixtures/protect-api/public/expected.json`. */
    @Serializable
    private data class Expected(
        val rtspsStream: Streams,
        val rtspsStreamCreated: Streams,
        val talkbackSession: Talkback,
    ) {
        @Serializable
        data class Streams(val name: String, val response: String, val streams: Map<String, String>)

        @Serializable
        data class Talkback(
            val name: String,
            val response: String,
            val url: String,
            val codec: String,
            val samplingRate: Int,
            val bitsPerSample: Int,
        )
    }

    private val camerasExpected =
        Fixtures.decode<CamerasExpected>("protect-api/cameras.expected.json")
    private val expected = Fixtures.decode<Expected>("protect-api/public/expected.json")

    private fun camerasResponse(): String =
        Fixtures.text("protect-api/${camerasExpected.responses.publicApi}")

    private fun response(file: String): String = Fixtures.text("protect-api/public/$file")

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
        server.enqueue(jsonResponse(camerasResponse()))

        val cameras = client().cameras("key-1")

        val request = server.takeRequest()
        assertEquals("/proxy/protect/integration/v1/cameras", request.url.encodedPath)
        assertEquals("key-1", request.headers["X-API-KEY"])
        // The same ids the legacy client reads from its bootstrap.
        val case = camerasExpected
        assertEquals(case.name, case.cameras.map { it.id }, cameras.map { it.id })
        assertEquals(case.name, case.cameras.map { it.publicApi.name }, cameras.map { it.name })
    }

    @Test
    fun `active streams are returned by quality and inactive ones dropped`() = runTest {
        server.enqueue(jsonResponse(response(expected.rtspsStream.response)))

        val streams = client().rtspsStreams("key-1", "cam1")

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals(
            "/proxy/protect/integration/v1/cameras/cam1/rtsps-stream",
            request.url.encodedPath,
        )
        assertEquals(expected.rtspsStream.name, expected.rtspsStream.streams, streams)
    }

    @Test
    fun `creating a stream posts the requested qualities`() = runTest {
        server.enqueue(jsonResponse(response(expected.rtspsStreamCreated.response)))

        val streams = client().createRtspsStreams("key-1", "cam1", listOf("medium"))

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals(
            "/proxy/protect/integration/v1/cameras/cam1/rtsps-stream",
            request.url.encodedPath,
        )
        assertTrue(request.body!!.utf8().contains("\"qualities\":[\"medium\"]"))
        assertEquals(expected.rtspsStreamCreated.name, expected.rtspsStreamCreated.streams, streams)
    }

    @Test
    fun `cameras report whether they carry a speaker`() = runTest {
        server.enqueue(jsonResponse(camerasResponse()))

        val cameras = client().cameras("key-1")

        // A camera whose flags never arrived (cam3) is treated as having no
        // speaker: offering talk-back and failing is worse than not offering it.
        val case = camerasExpected
        assertEquals(
            case.name,
            case.cameras.map { it.publicApi.hasSpeaker },
            cameras.map { it.hasSpeaker },
        )
    }

    @Test
    fun `a talkback session is posted without a body and parsed`() = runTest {
        server.enqueue(jsonResponse(response(expected.talkbackSession.response)))

        val session = client().talkbackSession("key-1", "cam1")

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals(
            "/proxy/protect/integration/v1/cameras/cam1/talkback-session",
            request.url.encodedPath,
        )
        assertEquals("key-1", request.headers["X-API-KEY"])
        assertEquals("", request.body?.utf8() ?: "")
        val case = expected.talkbackSession
        assertEquals(case.name, case.url, session.url)
        assertEquals(case.name, case.codec, session.codec)
        assertEquals(case.name, case.samplingRate, session.samplingRate)
        assertEquals(case.name, case.bitsPerSample, session.bitsPerSample)
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

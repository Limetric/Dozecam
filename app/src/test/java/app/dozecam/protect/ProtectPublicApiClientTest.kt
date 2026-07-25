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

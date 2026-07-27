package app.dozecam.ui.onboarding

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.dozecam.MainDispatcherRule
import app.dozecam.data.Camera
import app.dozecam.data.CameraStore
import app.dozecam.data.ProtectStream
import app.dozecam.protect.CredentialsStore
import app.dozecam.protect.ProtectCredentials
import app.dozecam.protect.TofuTrustStore
import app.dozecam.protect.protectHttpClient
import app.dozecam.protect.sha256Fingerprint
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

private class FakeCameraStore : CameraStore {
    val stored = MutableStateFlow<List<Camera>>(emptyList())
    override val cameras: Flow<List<Camera>> = stored
    override val enabledCameras: Flow<List<Camera>> = stored.map { it.filter(Camera::enabled) }

    override suspend fun upsert(camera: Camera) {
        stored.value = stored.value.filterNot { it.id == camera.id } + camera
    }

    override suspend fun remove(id: String) {
        stored.value = stored.value.filterNot { it.id == id }
    }


    override suspend fun setEnabled(id: String, enabled: Boolean) {
        stored.value = stored.value.map { if (it.id == id) it.copy(enabled = enabled) else it }
    }
}

private class FakeCredentialsStore(var saved: ProtectCredentials? = null) : CredentialsStore {
    override fun save(credentials: ProtectCredentials) {
        saved = credentials
    }

    override fun load(): ProtectCredentials? = saved
    override fun clear() {
        saved = null
    }
}

/**
 * Routes by path rather than by enqueue order: onboarding's call sequence
 * depends on what the console supports, and a queue would couple every test to
 * one particular path through it.
 */
private class ConsoleDispatcher : Dispatcher() {
    /** Path → handler; the first matching prefix wins. */
    val routes = linkedMapOf<String, (RecordedRequest) -> MockResponse>()
    val requests = mutableListOf<RecordedRequest>()

    override fun dispatch(request: RecordedRequest): MockResponse {
        requests += request
        val path = request.url.encodedPath
        val handler = routes.entries.firstOrNull { path == it.key }?.value
        return handler?.invoke(request) ?: notFound
    }

    fun pathsFor(method: String): List<String> =
        requests.filter { it.method == method }.map { it.url.encodedPath }

    private val notFound = MockResponse.Builder().code(404).body("{}").build()
}

class OnboardingViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var heldCertificate: HeldCertificate
    private lateinit var console: ConsoleDispatcher

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
        console = ConsoleDispatcher()
        console.routes[LOGIN] = { loginResponse() }
        server.dispatcher = console
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun TestScope.trustStore(): TofuTrustStore = TofuTrustStore(
        PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { File(tmp.root, "trust.preferences_pb") },
        ),
    )

    private fun loginResponse(): MockResponse = MockResponse.Builder()
        .code(200)
        .addHeader("Set-Cookie", "TOKEN=abc; Path=/")
        .addHeader("X-CSRF-Token", "csrf")
        .body("{}")
        .build()

    private fun json(body: String): MockResponse = MockResponse.Builder()
        .code(200)
        .body(body.trimIndent())
        .build()

    private fun status(code: Int): MockResponse =
        MockResponse.Builder().code(code).body("{}").build()

    /** A console that issues API keys and serves the public Integration API. */
    private fun publicConsole(streams: String = """{"low": null}""") {
        console.routes[API_KEYS] = { json("""{"data": {"full_api_key": "key-1"}}""") }
        console.routes[PUBLIC_CAMERAS] = { json("""[{"id": "cam1", "name": "Nursery"}]""") }
        console.routes[PUBLIC_STREAM] = { request ->
            if (request.method == "POST") {
                json("""{"medium": "rtsps://10.9.9.9:7441/aliasM?enableSrtp"}""")
            } else {
                json(streams)
            }
        }
    }

    /** A console too old (or an account too limited) to issue an API key. */
    private fun legacyConsole() {
        console.routes[API_KEYS] = { status(403) }
        console.routes[BOOTSTRAP] = {
            json(
                """
                {"cameras":[{"id":"cam1","name":"Nursery","channels":[
                  {"id":1,"name":"Medium","isRtspEnabled":false,"rtspAlias":null}
                ]}]}
                """,
            )
        }
        console.routes[PRIVATE_CAMERA] = {
            json(
                """
                {"id":"cam1","name":"Nursery","channels":[
                  {"id":1,"name":"Medium","isRtspEnabled":true,"rtspAlias":"aliasM"}
                ]}
                """,
            )
        }
    }

    private fun viewModel(
        cameraStore: FakeCameraStore,
        trustStore: TofuTrustStore,
        credentials: FakeCredentialsStore = FakeCredentialsStore(),
        localNetworkGranted: Boolean = true,
    ): OnboardingViewModel {
        val viewModel = OnboardingViewModel(
            cameraStore = cameraStore,
            trustStore = trustStore,
            credentialsStore = credentials,
            clientFactory = { fingerprint -> protectHttpClient(fingerprint) },
            localNetworkGranted = { localNetworkGranted },
        )
        viewModel.onHost("127.0.0.1:${server.port}")
        viewModel.onUsername("babycam")
        viewModel.onPassword("secret")
        return viewModel
    }

    private suspend fun TestScope.pinnedTrustStore(): TofuTrustStore = trustStore().also {
        it.pin("127.0.0.1", heldCertificate.certificate.sha256Fingerprint())
    }

    @Test
    fun `first connect prompts for the certificate fingerprint`() = runTest {
        val viewModel = viewModel(FakeCameraStore(), trustStore())

        viewModel.connect()

        val step = viewModel.state
            .first { it.step is OnboardingStep.ConfirmFingerprint } // suspends until IO completes
            .step as OnboardingStep.ConfirmFingerprint
        assertEquals(heldCertificate.certificate.sha256Fingerprint(), step.fingerprint)
    }

    @Test
    fun `confirming the fingerprint pins it and discovers cameras`() = runTest {
        publicConsole()
        val trustStore = trustStore()
        val credentials = FakeCredentialsStore()
        val viewModel = viewModel(FakeCameraStore(), trustStore, credentials)

        viewModel.connect()
        val prompt = viewModel.state
            .first { it.step is OnboardingStep.ConfirmFingerprint }
            .step as OnboardingStep.ConfirmFingerprint
        viewModel.confirmFingerprint(prompt.fingerprint)

        val picking = viewModel.state
            .first { it.step is OnboardingStep.PickCameras }
            .step as OnboardingStep.PickCameras
        assertEquals(listOf("Nursery"), picking.cameras.map { it.name })
        assertEquals(prompt.fingerprint, trustStore.fingerprintFor("127.0.0.1").first())
        assertEquals("babycam", credentials.saved?.username)
        // All discovered cameras are pre-selected.
        assertEquals(setOf("cam1"), viewModel.state.value.selectedCameraIds)
    }

    @Test
    fun `discovery mints an api key and reads the public integration api`() = runTest {
        publicConsole()
        val credentials = FakeCredentialsStore()
        val viewModel = viewModel(FakeCameraStore(), pinnedTrustStore(), credentials)

        viewModel.connect()
        viewModel.state.first { it.step is OnboardingStep.PickCameras }

        assertTrue(API_KEYS in console.pathsFor("POST"))
        assertTrue(PUBLIC_CAMERAS in console.pathsFor("GET"))
        // The private bootstrap is not touched when the public API answers.
        assertTrue(BOOTSTRAP !in console.pathsFor("GET"))
        // The minted key is kept so the next run does not create another.
        assertEquals("key-1", credentials.saved?.apiKey)
    }

    @Test
    fun `import enables a stream and stores the alias on the reachable host`() = runTest {
        publicConsole()
        val cameraStore = FakeCameraStore()
        val viewModel = viewModel(cameraStore, pinnedTrustStore())

        viewModel.connect()
        viewModel.state.first { it.step is OnboardingStep.PickCameras }
        viewModel.import()

        val done = viewModel.state
            .first { it.step is OnboardingStep.Done }
            .step as OnboardingStep.Done
        assertEquals(1, done.importedCount)
        val imported = cameraStore.stored.value.single()
        assertEquals("Nursery", imported.name)
        // The console advertised 10.9.9.9 over RTSPS; only the alias survives.
        assertEquals("rtsp://127.0.0.1:7447/aliasM", imported.url)
        // Same id the private path produces, so re-onboarding updates in place.
        assertEquals("protect-cam1-1", imported.id)
        // Carries the console identity, without which the monitor cannot pick
        // the livestream transport and an AV1 camera stays black — including
        // which console issued it, so a later re-onboard cannot mis-route it.
        assertEquals(
            ProtectStream("cam1", 1, consoleHost = "127.0.0.1:${server.port}"),
            imported.protect,
        )
    }

    @Test
    fun `re-importing a switched-off camera leaves it switched off`() = runTest {
        publicConsole()
        val cameraStore = FakeCameraStore()
        cameraStore.stored.value = listOf(
            Camera(
                id = "protect-cam1-1",
                name = "Nursery",
                url = "rtsp://old:7447/stale",
                enabled = false,
            ),
        )
        val viewModel = viewModel(cameraStore, pinnedTrustStore())

        viewModel.connect()
        viewModel.state.first { it.step is OnboardingStep.PickCameras }
        viewModel.import()
        viewModel.state.first { it.step is OnboardingStep.Done }

        val reimported = cameraStore.stored.value.single()
        // The URL refreshes, but whether the camera is on is the user's call —
        // silently re-enabling it would put it back in the viewer and restart
        // monitoring it.
        assertEquals("rtsp://127.0.0.1:7447/aliasM", reimported.url)
        assertEquals(false, reimported.enabled)
    }

    @Test
    fun `a newly imported camera arrives switched on`() = runTest {
        publicConsole()
        val cameraStore = FakeCameraStore()
        val viewModel = viewModel(cameraStore, pinnedTrustStore())

        viewModel.connect()
        viewModel.state.first { it.step is OnboardingStep.PickCameras }
        viewModel.import()
        viewModel.state.first { it.step is OnboardingStep.Done }

        assertTrue(cameraStore.stored.value.single().enabled)
    }

    @Test
    fun `an already-active stream is reused instead of re-enabled`() = runTest {
        publicConsole(streams = """{"medium": "rtsps://10.9.9.9:7441/existingAlias?enableSrtp"}""")
        val cameraStore = FakeCameraStore()
        val viewModel = viewModel(cameraStore, pinnedTrustStore())

        viewModel.connect()
        viewModel.state.first { it.step is OnboardingStep.PickCameras }
        viewModel.import()

        viewModel.state.first { it.step is OnboardingStep.Done }
        assertEquals(
            "rtsp://127.0.0.1:7447/existingAlias",
            cameraStore.stored.value.single().url,
        )
        assertTrue(PUBLIC_STREAM !in console.pathsFor("POST"))
    }

    @Test
    fun `a stored api key is reused without minting another`() = runTest {
        publicConsole()
        val credentials = FakeCredentialsStore(
            ProtectCredentials("127.0.0.1:${server.port}", "babycam", "secret", "stored-key"),
        )
        val viewModel = viewModel(FakeCameraStore(), pinnedTrustStore(), credentials)

        viewModel.connect()
        viewModel.state.first { it.step is OnboardingStep.PickCameras }

        assertTrue(API_KEYS !in console.pathsFor("POST"))
        assertEquals("stored-key", credentials.saved?.apiKey)
    }

    @Test
    fun `a revoked stored api key is replaced by a fresh one`() = runTest {
        publicConsole()
        console.routes[PUBLIC_CAMERAS] = { request ->
            if (request.headers["X-API-KEY"] == "key-1") {
                json("""[{"id": "cam1", "name": "Nursery"}]""")
            } else {
                status(401)
            }
        }
        val credentials = FakeCredentialsStore(
            ProtectCredentials("127.0.0.1:${server.port}", "babycam", "secret", "revoked-key"),
        )
        val viewModel = viewModel(FakeCameraStore(), pinnedTrustStore(), credentials)

        viewModel.connect()
        val picking = viewModel.state
            .first { it.step is OnboardingStep.PickCameras }
            .step as OnboardingStep.PickCameras

        assertEquals(listOf("Nursery"), picking.cameras.map { it.name })
        assertEquals("key-1", credentials.saved?.apiKey)
    }

    /** Pre-5.3 consoles, and accounts that cannot mint a key, still onboard. */
    @Test
    fun `a console without the public api falls back to the private one`() = runTest {
        legacyConsole()
        val credentials = FakeCredentialsStore()
        val cameraStore = FakeCameraStore()
        val viewModel = viewModel(cameraStore, pinnedTrustStore(), credentials)

        viewModel.connect()
        viewModel.state.first { it.step is OnboardingStep.PickCameras }
        viewModel.import()

        val done = viewModel.state
            .first { it.step is OnboardingStep.Done }
            .step as OnboardingStep.Done
        assertEquals(1, done.importedCount)
        val imported = cameraStore.stored.value.single()
        assertEquals("rtsp://127.0.0.1:7447/aliasM", imported.url)
        assertEquals("protect-cam1-1", imported.id)
        assertNull(credentials.saved?.apiKey)
    }

    @Test
    fun `bad credentials return to the form with an error`() = runTest {
        console.routes[LOGIN] = { status(401) }
        val viewModel = viewModel(FakeCameraStore(), pinnedTrustStore())

        viewModel.connect()

        val state = viewModel.state.first {
            it.step == OnboardingStep.Form && it.error != null
        }
        assertTrue(state.error!!.contains("401"))
    }

    /**
     * Android 17 drops LAN traffic from an app without the permission, so the
     * failure arrives as a bare socket timeout. Name the real cause instead.
     */
    @Test
    fun `a missing local network permission is named rather than shown as a timeout`() = runTest {
        console.routes[LOGIN] = { status(500) }
        val viewModel = viewModel(
            FakeCameraStore(),
            pinnedTrustStore(),
            localNetworkGranted = false,
        )

        viewModel.connect()

        val state = viewModel.state.first {
            it.step == OnboardingStep.Form && it.error != null
        }
        assertTrue(state.error!!.contains("local network access"))
    }

    @Test
    fun `an expired session during a private import re-authenticates once`() = runTest {
        legacyConsole()
        var patches = 0
        val enabled = console.routes.getValue(PRIVATE_CAMERA)
        console.routes[PRIVATE_CAMERA] = { request ->
            if (patches++ == 0) status(401) else enabled(request)
        }
        val cameraStore = FakeCameraStore()
        val viewModel = viewModel(cameraStore, pinnedTrustStore())

        viewModel.connect()
        viewModel.state.first { it.step is OnboardingStep.PickCameras }
        viewModel.import()

        val done = viewModel.state
            .first { it.step is OnboardingStep.Done }
            .step as OnboardingStep.Done
        assertEquals(1, done.importedCount)
        assertEquals("rtsp://127.0.0.1:7447/aliasM", cameraStore.stored.value.single().url)
        assertEquals(
            ProtectStream("cam1", 1, consoleHost = "127.0.0.1:${server.port}"),
            cameraStore.stored.value.single().protect,
        )
        assertEquals(2, console.pathsFor("POST").count { it == LOGIN })
    }

    @Test
    fun `deselected cameras are not imported`() = runTest {
        publicConsole()
        val cameraStore = FakeCameraStore()
        val viewModel = viewModel(cameraStore, pinnedTrustStore())

        viewModel.connect()
        viewModel.state.first { it.step is OnboardingStep.PickCameras }
        viewModel.toggleCamera("cam1")
        viewModel.import()

        val done = viewModel.state
            .first { it.step is OnboardingStep.Done }
            .step as OnboardingStep.Done
        assertEquals(0, done.importedCount)
        assertTrue(cameraStore.stored.value.isEmpty())
    }

    private companion object {
        const val LOGIN = "/api/auth/login"
        const val API_KEYS = "/proxy/users/api/v2/user/self/keys"
        const val PUBLIC_CAMERAS = "/proxy/protect/integration/v1/cameras"
        const val PUBLIC_STREAM = "/proxy/protect/integration/v1/cameras/cam1/rtsps-stream"
        const val BOOTSTRAP = "/proxy/protect/api/bootstrap"
        const val PRIVATE_CAMERA = "/proxy/protect/api/cameras/cam1"
    }
}

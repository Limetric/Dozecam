package app.dozecam.ui.onboarding

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.dozecam.MainDispatcherRule
import app.dozecam.data.Camera
import app.dozecam.data.CameraStore
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

private class FakeCameraStore : CameraStore {
    val stored = MutableStateFlow<List<Camera>>(emptyList())
    val selectedId = MutableStateFlow<String?>(null)
    override val cameras: Flow<List<Camera>> = stored
    override val selectedCamera: Flow<Camera?> =
        combine(stored, selectedId) { list, id ->
            list.firstOrNull { it.id == id } ?: list.firstOrNull()
        }

    override suspend fun upsert(camera: Camera) {
        stored.value = stored.value.filterNot { it.id == camera.id } + camera
    }

    override suspend fun remove(id: String) {
        stored.value = stored.value.filterNot { it.id == id }
    }

    override suspend fun select(id: String) {
        selectedId.value = id
    }
}

private class FakeCredentialsStore : CredentialsStore {
    var saved: ProtectCredentials? = null
    override fun save(credentials: ProtectCredentials) {
        saved = credentials
    }

    override fun load(): ProtectCredentials? = saved
    override fun clear() {
        saved = null
    }
}

class OnboardingViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val tmp = TemporaryFolder()

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

    private fun bootstrapResponse(): MockResponse = MockResponse.Builder()
        .code(200)
        .body(
            """
            {"cameras":[{"id":"cam1","name":"Nursery","channels":[
              {"id":1,"name":"Medium","isRtspEnabled":false,"rtspAlias":null}
            ]}]}
            """.trimIndent(),
        )
        .build()

    private fun enableRtspResponse(): MockResponse = MockResponse.Builder()
        .code(200)
        .body(
            """
            {"id":"cam1","name":"Nursery","channels":[
              {"id":1,"name":"Medium","isRtspEnabled":true,"rtspAlias":"aliasM"}
            ]}
            """.trimIndent(),
        )
        .build()

    private fun viewModel(
        cameraStore: FakeCameraStore,
        trustStore: TofuTrustStore,
        credentials: FakeCredentialsStore = FakeCredentialsStore(),
    ): OnboardingViewModel {
        val viewModel = OnboardingViewModel(
            cameraStore = cameraStore,
            trustStore = trustStore,
            credentialsStore = credentials,
            clientFactory = { fingerprint -> protectHttpClient(fingerprint) },
        )
        viewModel.onHost("127.0.0.1:${server.port}")
        viewModel.onUsername("babycam")
        viewModel.onPassword("secret")
        return viewModel
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
        server.enqueue(loginResponse())
        server.enqueue(bootstrapResponse())
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
    fun `import enables rtsp and adds the camera with the derived url`() = runTest {
        server.enqueue(loginResponse())
        server.enqueue(bootstrapResponse())
        server.enqueue(enableRtspResponse())
        val trustStore = trustStore()
        trustStore.pin("127.0.0.1", heldCertificate.certificate.sha256Fingerprint())
        val cameraStore = FakeCameraStore()
        val viewModel = viewModel(cameraStore, trustStore)

        viewModel.connect()
        viewModel.state.first { it.step is OnboardingStep.PickCameras }
        viewModel.import()

        val done = viewModel.state
            .first { it.step is OnboardingStep.Done }
            .step as OnboardingStep.Done
        assertEquals(1, done.importedCount)
        val imported = cameraStore.stored.value.single()
        assertEquals("Nursery", imported.name)
        assertEquals("rtsp://127.0.0.1:7447/aliasM", imported.url)
        assertEquals("protect-cam1-1", imported.id)
    }

    @Test
    fun `bad credentials return to the form with an error`() = runTest {
        server.enqueue(MockResponse.Builder().code(401).body("{}").build())
        val trustStore = trustStore()
        trustStore.pin("127.0.0.1", heldCertificate.certificate.sha256Fingerprint())
        val viewModel = viewModel(FakeCameraStore(), trustStore)

        viewModel.connect()

        val state = viewModel.state.first {
            it.step == OnboardingStep.Form && it.error != null
        }
        assertTrue(state.error!!.contains("401"))
    }

    @Test
    fun `an expired session during import re-authenticates once and succeeds`() = runTest {
        server.enqueue(loginResponse())
        server.enqueue(bootstrapResponse())
        server.enqueue(MockResponse.Builder().code(401).body("{}").build()) // expired PATCH
        server.enqueue(loginResponse()) // re-auth
        server.enqueue(enableRtspResponse()) // retried PATCH
        val trustStore = trustStore()
        trustStore.pin("127.0.0.1", heldCertificate.certificate.sha256Fingerprint())
        val cameraStore = FakeCameraStore()
        val viewModel = viewModel(cameraStore, trustStore)

        viewModel.connect()
        viewModel.state.first { it.step is OnboardingStep.PickCameras }
        viewModel.import()

        val done = viewModel.state
            .first { it.step is OnboardingStep.Done }
            .step as OnboardingStep.Done
        assertEquals(1, done.importedCount)
        assertEquals("rtsp://127.0.0.1:7447/aliasM", cameraStore.stored.value.single().url)
    }

    @Test
    fun `deselected cameras are not imported`() = runTest {
        server.enqueue(loginResponse())
        server.enqueue(bootstrapResponse())
        val trustStore = trustStore()
        trustStore.pin("127.0.0.1", heldCertificate.certificate.sha256Fingerprint())
        val cameraStore = FakeCameraStore()
        val viewModel = viewModel(cameraStore, trustStore)

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
}

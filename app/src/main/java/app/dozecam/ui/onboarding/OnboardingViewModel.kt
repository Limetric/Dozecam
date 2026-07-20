package app.dozecam.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.dozecam.data.Camera
import app.dozecam.data.CameraStore
import app.dozecam.protect.CredentialsStore
import app.dozecam.protect.ProtectApiClient
import app.dozecam.protect.ProtectApiException
import app.dozecam.protect.ProtectCamera
import app.dozecam.protect.ProtectCredentials
import app.dozecam.protect.ProtectSession
import app.dozecam.protect.TofuTrustStore
import app.dozecam.protect.UntrustedCertificateException
import app.dozecam.protect.protectHttpClient
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.HttpUrl
import okhttp3.OkHttpClient

sealed interface OnboardingStep {
    data object Form : OnboardingStep
    data object Connecting : OnboardingStep
    data class ConfirmFingerprint(val fingerprint: String) : OnboardingStep
    data class PickCameras(val cameras: List<ProtectCamera>) : OnboardingStep
    data object Importing : OnboardingStep
    data class Done(val importedCount: Int) : OnboardingStep
}

data class OnboardingUiState(
    val host: String = "",
    val username: String = "",
    val password: String = "",
    val step: OnboardingStep = OnboardingStep.Form,
    val error: String? = null,
    val selectedCameraIds: Set<String> = emptySet(),
) {
    val canConnect: Boolean
        get() = ProtectApiClient.baseUrlFor(host) != null &&
            username.isNotBlank() && password.isNotBlank()
}

class OnboardingViewModel(
    private val cameraStore: CameraStore,
    private val trustStore: TofuTrustStore,
    private val credentialsStore: CredentialsStore,
    private val clientFactory: (fingerprint: String?) -> OkHttpClient = ::protectHttpClient,
) : ViewModel() {

    private val _state = MutableStateFlow(OnboardingUiState())
    val state: StateFlow<OnboardingUiState> = _state

    private var session: ProtectSession? = null
    private var api: ProtectApiClient? = null

    init {
        credentialsStore.load()?.let { saved ->
            _state.value = _state.value.copy(
                host = saved.host,
                username = saved.username,
                password = saved.password,
            )
        }
    }

    fun onHost(value: String) {
        _state.value = _state.value.copy(host = value)
    }

    fun onUsername(value: String) {
        _state.value = _state.value.copy(username = value)
    }

    fun onPassword(value: String) {
        _state.value = _state.value.copy(password = value)
    }

    fun connect() {
        val current = _state.value
        val baseUrl = ProtectApiClient.baseUrlFor(current.host) ?: return
        _state.value = current.copy(step = OnboardingStep.Connecting, error = null)
        viewModelScope.launch {
            val fingerprint = trustStore.fingerprintFor(baseUrl.host).first()
            runCatching {
                signInAndDiscover(baseUrl, fingerprint)
            }.onFailure { failure -> handleConnectFailure(failure) }
        }
    }

    /** User confirmed the console certificate fingerprint; pin it and retry. */
    fun confirmFingerprint(fingerprint: String) {
        val baseUrl = ProtectApiClient.baseUrlFor(_state.value.host) ?: return
        _state.value = _state.value.copy(step = OnboardingStep.Connecting, error = null)
        viewModelScope.launch {
            trustStore.pin(baseUrl.host, fingerprint)
            runCatching {
                signInAndDiscover(baseUrl, fingerprint)
            }.onFailure { failure -> handleConnectFailure(failure) }
        }
    }

    fun toggleCamera(id: String) {
        val current = _state.value
        val selected = current.selectedCameraIds
        _state.value = current.copy(
            selectedCameraIds = if (id in selected) selected - id else selected + id,
        )
    }

    fun import() {
        val current = _state.value
        val picking = current.step as? OnboardingStep.PickCameras ?: return
        val api = api ?: return
        if (session == null) return
        _state.value = current.copy(step = OnboardingStep.Importing, error = null)
        viewModelScope.launch {
            runCatching {
                var imported = 0
                for (camera in picking.cameras) {
                    if (camera.id !in current.selectedCameraIds) continue
                    val channel = camera.preferredChannel ?: continue
                    val alias = if (channel.isRtspEnabled && channel.rtspAlias != null) {
                        channel.rtspAlias
                    } else {
                        val updated = withFreshSessionOn401(api) { activeSession ->
                            api.enableRtsp(activeSession, camera.id, channel.id)
                        }
                        updated.channels.firstOrNull { it.id == channel.id }?.rtspAlias
                            ?: throw IllegalStateException(
                                "Console did not return an RTSP alias for ${camera.name}",
                            )
                    }
                    cameraStore.upsert(
                        Camera(
                            id = "protect-${camera.id}-${channel.id}",
                            name = camera.name.ifBlank { "Camera" },
                            url = api.rtspUrlFor(alias),
                        ),
                    )
                    imported++
                }
                imported
            }.onSuccess { imported ->
                _state.value = _state.value.copy(step = OnboardingStep.Done(imported))
            }.onFailure { failure ->
                _state.value = _state.value.copy(
                    step = OnboardingStep.PickCameras(picking.cameras),
                    error = failure.message ?: "Import failed",
                )
            }
        }
    }

    /**
     * The Protect session can expire while the camera picker sits open;
     * re-authenticate once with the stored credentials instead of stranding
     * the user on an error that retrying cannot clear.
     */
    private suspend fun <T> withFreshSessionOn401(
        api: ProtectApiClient,
        block: suspend (ProtectSession) -> T,
    ): T = try {
        block(checkNotNull(session))
    } catch (e: ProtectApiException) {
        if (e.statusCode != 401) throw e
        val current = _state.value
        val renewed = api.login(current.username, current.password)
        session = renewed
        block(renewed)
    }

    private suspend fun signInAndDiscover(baseUrl: HttpUrl, fingerprint: String?) {
        val client = ProtectApiClient(baseUrl, clientFactory(fingerprint))
        val current = _state.value
        val newSession = client.login(current.username, current.password)
        credentialsStore.save(
            ProtectCredentials(current.host.trim(), current.username, current.password),
        )
        val bootstrap = client.bootstrap(newSession)
        api = client
        session = newSession
        _state.value = _state.value.copy(
            step = OnboardingStep.PickCameras(bootstrap.cameras),
            selectedCameraIds = bootstrap.cameras.map { it.id }.toSet(),
        )
    }

    private fun handleConnectFailure(failure: Throwable) {
        val untrusted = generateSequence(failure) { it.cause }
            .filterIsInstance<UntrustedCertificateException>()
            .firstOrNull()
        _state.value = when {
            untrusted != null -> _state.value.copy(
                step = OnboardingStep.ConfirmFingerprint(untrusted.fingerprint),
            )
            failure is SSLHandshakeException || failure is SSLException ->
                _state.value.copy(
                    step = OnboardingStep.Form,
                    error = failure.message ?: "Secure connection failed",
                )
            else -> _state.value.copy(
                step = OnboardingStep.Form,
                error = failure.message ?: "Connection failed",
            )
        }
    }

    companion object {
        fun factory(
            cameraStore: CameraStore,
            trustStore: TofuTrustStore,
            credentialsStore: CredentialsStore,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { OnboardingViewModel(cameraStore, trustStore, credentialsStore) }
        }
    }
}

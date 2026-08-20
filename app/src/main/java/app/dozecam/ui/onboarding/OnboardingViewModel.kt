package app.dozecam.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.dozecam.data.Camera
import app.dozecam.data.CameraStore
import app.dozecam.data.ProtectStream
import app.dozecam.protect.ChangedCertificateException
import app.dozecam.protect.CredentialsStore
import app.dozecam.protect.ProtectApiClient
import app.dozecam.protect.ProtectApiException
import app.dozecam.protect.ProtectCamera
import app.dozecam.protect.ProtectCredentials
import app.dozecam.protect.ProtectPublicApiClient
import app.dozecam.protect.ProtectSession
import app.dozecam.protect.PublicCamera
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

/** A camera offered in the picker, from whichever API discovered it. */
data class DiscoveredCamera(
    val id: String,
    val name: String,
    val detail: String,
)

sealed interface OnboardingStep {
    data object Form : OnboardingStep
    data object Connecting : OnboardingStep

    /**
     * The console's certificate, waiting on the user's word. [replacing] is
     * null on first contact and carries the pinned fingerprint when the console
     * has since presented a different one.
     */
    data class ConfirmFingerprint(
        val fingerprint: String,
        val replacing: String? = null,
    ) : OnboardingStep

    data class PickCameras(val cameras: List<DiscoveredCamera>) : OnboardingStep
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
    private val localNetworkGranted: () -> Boolean = { true },
    /** Announces that the console we are signed in to has changed. */
    private val onConsoleChanged: () -> Unit = {},
) : ViewModel() {

    /**
     * What the picker's selection resolves to at import time. Cameras come from
     * the documented public Integration API when the console can issue an API
     * key, and from the legacy private API otherwise.
     */
    private sealed interface Discovery {
        data class Public(
            val api: ProtectPublicApiClient,
            val apiKey: String,
            val cameras: List<PublicCamera>,
        ) : Discovery

        data class Private(
            val api: ProtectApiClient,
            val cameras: List<ProtectCamera>,
        ) : Discovery
    }

    private val _state = MutableStateFlow(OnboardingUiState())
    val state: StateFlow<OnboardingUiState> = _state

    private var session: ProtectSession? = null
    private var discovery: Discovery? = null

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

    /**
     * User confirmed the console certificate fingerprint; pin it and retry.
     * Pinning overwrites whatever was there, which is what makes a console that
     * has reissued its certificate reachable again once the user says so.
     */
    fun confirmFingerprint(fingerprint: String) {
        val baseUrl = ProtectApiClient.baseUrlFor(_state.value.host) ?: return
        val replacedCertificate =
            (_state.value.step as? OnboardingStep.ConfirmFingerprint)?.replacing != null
        _state.value = _state.value.copy(step = OnboardingStep.Connecting, error = null)
        viewModelScope.launch {
            trustStore.pin(baseUrl.host, fingerprint)
            runCatching {
                signInAndDiscover(baseUrl, fingerprint, replacedCertificate)
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
        val source = discovery ?: return
        _state.value = current.copy(step = OnboardingStep.Importing, error = null)
        viewModelScope.launch {
            runCatching {
                when (source) {
                    is Discovery.Public -> importPublic(source, current.selectedCameraIds)
                    is Discovery.Private -> importPrivate(source, current.selectedCameraIds)
                }
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
     * The console these cameras belong to, in the same form [saveCredentials]
     * stores, so playback can tell whether the signed-in console is the one
     * that issued a camera.
     */
    private val consoleHost: String
        get() = _state.value.host.trim()

    /**
     * Whether a camera should come back switched on. Re-running onboarding over
     * a camera the user had switched off must not switch it back on: enabled is
     * the user's decision, not the console's. Unknown ids are new, so they
     * arrive enabled.
     */
    private suspend fun enabledStates(): Map<String, Boolean> =
        cameraStore.cameras.first().associate { it.id to it.enabled }

    private suspend fun importPublic(source: Discovery.Public, selected: Set<String>): Int {
        val wasEnabled = enabledStates()
        var imported = 0
        for (camera in source.cameras) {
            if (camera.id !in selected) continue
            val quality = ProtectPublicApiClient.QUALITY_MEDIUM
            // Reuse a stream the console already serves; only enable one when
            // the camera has none, so onboarding does not churn the console's
            // stream settings on every re-run.
            val existing = source.api.rtspsStreams(source.apiKey, camera.id)[quality]
            val rtsps = existing
                ?: source.api.createRtspsStreams(source.apiKey, camera.id, listOf(quality))[quality]
                ?: throw IllegalStateException(
                    "Console did not return a $quality stream for ${camera.displayName}",
                )
            val url = source.api.streamUrlFor(rtsps)
                ?: throw IllegalStateException(
                    "Could not read the stream alias for ${camera.displayName}",
                )
            cameraStore.upsert(
                Camera(
                    id = "protect-${camera.id}-$MEDIUM_CHANNEL_ID",
                    name = camera.displayName,
                    url = url,
                    protect = ProtectStream(camera.id, MEDIUM_CHANNEL_ID, consoleHost),
                    enabled = wasEnabled["protect-${camera.id}-$MEDIUM_CHANNEL_ID"] ?: true,
                ),
            )
            imported++
        }
        return imported
    }

    private suspend fun importPrivate(source: Discovery.Private, selected: Set<String>): Int {
        val wasEnabled = enabledStates()
        var imported = 0
        for (camera in source.cameras) {
            if (camera.id !in selected) continue
            val channel = camera.preferredChannel ?: continue
            val alias = if (channel.isRtspEnabled && channel.rtspAlias != null) {
                channel.rtspAlias
            } else {
                val updated = withFreshSessionOn401(source.api) { activeSession ->
                    source.api.enableRtsp(activeSession, camera.id, channel.id)
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
                    url = source.api.rtspUrlFor(alias),
                    protect = ProtectStream(camera.id, channel.id, consoleHost),
                    enabled = wasEnabled["protect-${camera.id}-${channel.id}"] ?: true,
                ),
            )
            imported++
        }
        return imported
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

    private suspend fun signInAndDiscover(
        baseUrl: HttpUrl,
        fingerprint: String?,
        replacedCertificate: Boolean = false,
    ) {
        val http = clientFactory(fingerprint)
        val api = ProtectApiClient(baseUrl, http)
        val current = _state.value
        val newSession = api.login(current.username, current.password)
        session = newSession
        // A console that reissued the certificate the user just re-confirmed
        // has almost certainly reissued its media ports' too, and those pins
        // would otherwise refuse every stream with nothing to press. Only then:
        // an ordinary sign-in keeps them, so re-onboarding does not quietly
        // reopen the first-use window on a port whose certificate is intact.
        // Waiting until the login succeeds means a console that cannot be
        // signed in to has nothing forgotten on its behalf.
        if (replacedCertificate) trustStore.forgetLearnedEndpoints(baseUrl.host)

        val found = discoverPublicly(baseUrl, http, api, newSession)
            ?: Discovery.Private(api, api.bootstrap(newSession).cameras)
        discovery = found
        val cameras = when (found) {
            is Discovery.Public -> found.cameras.map {
                DiscoveredCamera(it.id, it.displayName, MEDIUM_LABEL)
            }
            is Discovery.Private -> found.cameras.map {
                DiscoveredCamera(
                    id = it.id,
                    name = it.name.ifBlank { "Camera" },
                    detail = it.preferredChannel?.name.orEmpty(),
                )
            }
        }
        _state.value = _state.value.copy(
            step = OnboardingStep.PickCameras(cameras),
            selectedCameraIds = cameras.map { it.id }.toSet(),
        )
    }

    /**
     * Discovery over the public Integration API, or null when this console
     * cannot serve it — pre-5.3 firmware has no such endpoints, and minting a
     * key needs owner rights. Neither is fatal: the caller falls back to the
     * private API, which every Protect version still answers.
     */
    private suspend fun discoverPublicly(
        baseUrl: HttpUrl,
        http: OkHttpClient,
        api: ProtectApiClient,
        session: ProtectSession,
    ): Discovery.Public? {
        val public = ProtectPublicApiClient(baseUrl, http)
        val host = _state.value.host.trim()
        val stored = credentialsStore.load()
            ?.takeIf { it.host == host && it.username == _state.value.username }
            ?.apiKey
        // Try the stored key first, and only ask for a new one if there is none
        // or the console has since revoked it — minting unconditionally would
        // leave a dead key behind on every run.
        stored?.let { key -> discoverWith(public, key)?.let { return it } }
        val minted = mintApiKey(api, session)
        if (minted != null && minted != stored) {
            discoverWith(public, minted)?.let { return it }
        }
        saveCredentials(apiKey = null)
        return null
    }

    private suspend fun discoverWith(
        public: ProtectPublicApiClient,
        apiKey: String,
    ): Discovery.Public? {
        val cameras = runCatching { public.cameras(apiKey) }.getOrNull() ?: return null
        saveCredentials(apiKey)
        return Discovery.Public(public, apiKey, cameras)
    }

    private suspend fun mintApiKey(api: ProtectApiClient, session: ProtectSession): String? =
        runCatching { api.createApiKey(session, API_KEY_NAME) }.getOrNull()

    private fun saveCredentials(apiKey: String?) {
        val current = _state.value
        credentialsStore.save(
            ProtectCredentials(current.host.trim(), current.username, current.password, apiKey),
        )
        // Said here rather than left for a screen to notice on resume: signing
        // in is the moment the answer changes, onboarding can be reached from
        // either the viewer or settings, and it can be left at this point
        // without importing a single camera.
        onConsoleChanged()
    }

    private fun handleConnectFailure(failure: Throwable) {
        val certificate = generateSequence(failure) { it.cause }
            .firstNotNullOfOrNull { cause ->
                when (cause) {
                    is UntrustedCertificateException ->
                        OnboardingStep.ConfirmFingerprint(cause.fingerprint)
                    // A changed certificate asks the same question as a first
                    // sighting, with more to weigh: refusing outright would be
                    // safe and unusable, since the console would then be
                    // unreachable from every screen in the app.
                    is ChangedCertificateException -> OnboardingStep.ConfirmFingerprint(
                        fingerprint = cause.fingerprint,
                        replacing = cause.pinnedFingerprint,
                    )
                    else -> null
                }
            }
        _state.value = when {
            // Checked first: without the permission the connection is dropped
            // before any TLS happens, and the socket timeout that surfaces
            // says nothing about the real cause.
            !localNetworkGranted() -> _state.value.copy(
                step = OnboardingStep.Form,
                error = "Dozecam needs local network access to reach the console. " +
                    "Grant it under Permissions → Nearby devices in the app's system settings.",
            )
            certificate != null -> _state.value.copy(step = certificate)
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

    private val PublicCamera.displayName: String
        get() = name.orEmpty().ifBlank { "Camera" }

    companion object {
        private const val API_KEY_NAME = "Dozecam"
        private const val MEDIUM_LABEL = "Medium"

        /**
         * Protect numbers a camera's channels High/Medium/Low, so the medium
         * quality the public API names is channel 1 on the private one. Keeping
         * that in the camera id means a console that switches APIs between runs
         * updates its existing entry instead of adding a duplicate.
         */
        private const val MEDIUM_CHANNEL_ID = 1

        fun factory(
            cameraStore: CameraStore,
            trustStore: TofuTrustStore,
            credentialsStore: CredentialsStore,
            localNetworkGranted: () -> Boolean = { true },
            onConsoleChanged: () -> Unit = {},
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                OnboardingViewModel(
                    cameraStore = cameraStore,
                    trustStore = trustStore,
                    credentialsStore = credentialsStore,
                    localNetworkGranted = localNetworkGranted,
                    onConsoleChanged = onConsoleChanged,
                )
            }
        }
    }
}

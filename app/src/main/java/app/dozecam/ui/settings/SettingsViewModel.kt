package app.dozecam.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.dozecam.data.AppSettings
import app.dozecam.data.AppSettingsStore
import app.dozecam.data.Camera
import app.dozecam.data.CameraStore
import app.dozecam.data.DetectorSettings
import app.dozecam.data.DetectorSettingsStore
import app.dozecam.data.StreamUrlValidator
import app.dozecam.monitoring.MonitoringState
import app.dozecam.monitoring.ReadinessFinding
import app.dozecam.monitoring.ReadinessPrompt
import app.dozecam.monitoring.monitorable
import app.dozecam.protect.CredentialsStore
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class CameraFormState(
    val name: String = "",
    val url: String = "",
    val editingId: String? = null,
) {
    val canSave: Boolean
        get() = name.isNotBlank() && StreamUrlValidator.isValid(url)
}

/**
 * Everything about how Dozecam is set up: which cameras exist, which are
 * switched on, how sensitive the detector is, and how alerts behave. The
 * viewer deliberately owns none of this — it shows cameras and arms the
 * monitor, nothing more.
 */
class SettingsViewModel(
    private val store: AppSettingsStore,
    private val cameraStore: CameraStore,
    private val detectorSettings: DetectorSettingsStore,
    private val monitoringState: MonitoringState,
    private val credentials: CredentialsStore,
    readinessFindings: Flow<List<ReadinessFinding>>,
    /** Where the credentials read happens; injectable so tests stay deterministic. */
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    val monitoringRunning: StateFlow<Boolean> = monitoringState.serviceRunning

    /**
     * The bedtime check, live while this screen is open — and only while it is.
     *
     * The probe re-reads the device on a timer, because a permission, a ringer
     * and a Do Not Disturb profile announce nothing when they change. That is
     * worth doing behind a screen someone is reading and worth nothing behind
     * one they have left, so it follows its subscribers rather than the
     * ViewModel. The grace is what carries it across the trip out to an Android
     * settings screen a remedy sent them to, and back.
     */
    val readiness: StateFlow<List<ReadinessFinding>> = readinessFindings
        // Upstream of the sharing, deliberately. Forgetting a check that has
        // started passing again belongs wherever the checklist is being
        // watched, and this is the screen a prompt sends people to and the
        // screen they fix things on — but a collector of its own would be a
        // permanent subscriber, and would hold the probe polling every two
        // seconds behind a settings screen left on the back stack all night.
        // As a side effect of the shared flow it runs exactly when the screen
        // is looking, which is exactly when it is worth running.
        .onEach(::forgetRecoveredChecks)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_GRACE_MS), emptyList())

    /**
     * Drops the record of any bedtime failure the user has been told about that
     * is no longer failing, so the next time it breaks it is worth saying
     * again. Left alone if nothing moved: this runs several times a second and
     * a preference edit is a disk write.
     */
    private suspend fun forgetRecoveredChecks(findings: List<ReadinessFinding>) {
        if (findings.isEmpty()) return
        val stored = store.settings.first().acknowledgedReadinessChecks
        if (ReadinessPrompt.remembered(findings, stored) == stored) return
        // Recomputed inside the transform rather than written from the snapshot
        // above: an acknowledgement can commit between the two, and writing the
        // older set back would spend the one interruption it had just recorded.
        store.update {
            it.copy(
                acknowledgedReadinessChecks = ReadinessPrompt.remembered(
                    findings,
                    it.acknowledgedReadinessChecks,
                ),
            )
        }
    }

    /** Loudest level across every monitored camera: what the meter shows. */
    val audioLevel: StateFlow<Float> = monitoringState.cameras
        .map { states -> states.values.maxOfOrNull { it.level ?: 0f } ?: 0f }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0f)

    /**
     * Whether monitoring has anything to do — some camera that can be heard,
     * or a service already running. The hub's status row reads it to say why
     * a monitor is idle rather than merely that it is.
     */
    val canMonitor: StateFlow<Boolean> = combine(
        cameraStore.enabledCameras,
        monitoringRunning,
        // Signing in to a console from here changes the answer without touching
        // either of the others, and leaves the switch stuck on what was true
        // for the console before it.
        monitoringState.consoleGeneration,
    ) { enabled, running, _ ->
        // Asked of the same rule the service uses, so the switch is never
        // greyed out over a camera the service would happily listen to.
        running || monitorable(enabled, credentials, ioDispatcher).isNotEmpty()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val settings: StateFlow<AppSettings> = store.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    val cameras: StateFlow<List<Camera>> = cameraStore.cameras
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val detector: StateFlow<DetectorSettings> = detectorSettings.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, DetectorSettings())

    private val _form = MutableStateFlow(CameraFormState())
    val form: StateFlow<CameraFormState> = _form

    fun update(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { store.update(transform) }
    }

    fun onDetectorChange(transform: (DetectorSettings) -> DetectorSettings) {
        viewModelScope.launch { detectorSettings.update(transform) }
    }

    fun setCameraEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch { cameraStore.setEnabled(id, enabled) }
    }

    fun onFormName(name: String) {
        _form.value = _form.value.copy(name = name)
    }

    fun onFormUrl(url: String) {
        _form.value = _form.value.copy(url = url)
    }

    fun startEdit(camera: Camera) {
        _form.value = CameraFormState(
            name = camera.name,
            url = camera.url,
            editingId = camera.id,
        )
    }

    fun cancelEdit() {
        _form.value = CameraFormState()
    }

    fun saveCamera() {
        val state = _form.value
        if (!state.canSave) return
        // An edit changes only the two fields this form owns: rebuilding the
        // camera from scratch would drop its Protect identity (sending an AV1
        // camera back to RTSP and a black screen) and silently re-enable it.
        val existing = cameras.value.firstOrNull { it.id == state.editingId }
        val name = state.name.trim()
        val url = StreamUrlValidator.normalize(state.url)
        val camera = existing?.copy(name = name, url = url)
            ?: Camera(id = UUID.randomUUID().toString(), name = name, url = url)
        viewModelScope.launch { cameraStore.upsert(camera) }
        _form.value = CameraFormState()
    }

    fun deleteCamera(id: String) {
        viewModelScope.launch { cameraStore.remove(id) }
        if (_form.value.editingId == id) {
            _form.value = CameraFormState()
        }
    }

    companion object {
        /** Long enough to ride out a configuration change, short enough to stop for the night. */
        private const val SUBSCRIPTION_GRACE_MS = 5_000L

        fun factory(
            store: AppSettingsStore,
            cameraStore: CameraStore,
            detectorSettings: DetectorSettingsStore,
            monitoringState: MonitoringState,
            credentials: CredentialsStore,
            readinessFindings: Flow<List<ReadinessFinding>>,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                SettingsViewModel(
                    store,
                    cameraStore,
                    detectorSettings,
                    monitoringState,
                    credentials,
                    readinessFindings,
                )
            }
        }
    }
}

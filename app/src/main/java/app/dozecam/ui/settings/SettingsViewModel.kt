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
import app.dozecam.monitoring.monitorable
import app.dozecam.protect.CredentialsStore
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
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
    /** Where the credentials read happens; injectable so tests stay deterministic. */
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    val monitoringRunning: StateFlow<Boolean> = monitoringState.serviceRunning

    /** Loudest level across every monitored camera: what the meter shows. */
    val audioLevel: StateFlow<Float> = monitoringState.cameras
        .map { states -> states.values.maxOfOrNull { it.level ?: 0f } ?: 0f }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0f)

    /**
     * Whether the monitoring switch can do anything. A switch that starts a
     * service which immediately stops itself is worse than a disabled one; a
     * running service can always be switched off.
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

    /**
     * Records the user's intent alongside the service call: a deliberate stop
     * must survive the activity being recreated, or coming back to the viewer
     * would re-arm what was just switched off.
     */
    fun onMonitoringIntent(enabled: Boolean) {
        monitoringState.userStopped.value = !enabled
    }

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
        fun factory(
            store: AppSettingsStore,
            cameraStore: CameraStore,
            detectorSettings: DetectorSettingsStore,
            monitoringState: MonitoringState,
            credentials: CredentialsStore,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                SettingsViewModel(
                    store,
                    cameraStore,
                    detectorSettings,
                    monitoringState,
                    credentials,
                )
            }
        }
    }
}

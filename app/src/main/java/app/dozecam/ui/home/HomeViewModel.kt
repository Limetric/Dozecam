package app.dozecam.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.dozecam.audio.SoundDetector
import app.dozecam.data.Camera
import app.dozecam.data.CameraStore
import app.dozecam.data.DetectorSettings
import app.dozecam.data.DetectorSettingsStore
import app.dozecam.data.StreamUrlValidator
import app.dozecam.monitoring.MonitoringState
import app.dozecam.player.ConnectionState
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
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

class HomeViewModel(
    private val cameraStore: CameraStore,
    private val detectorSettings: DetectorSettingsStore,
    monitoringState: MonitoringState,
) : ViewModel() {

    val cameras: StateFlow<List<Camera>> = cameraStore.cameras
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val selectedCamera: StateFlow<Camera?> = cameraStore.selectedCamera
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _form = MutableStateFlow(CameraFormState())
    val form: StateFlow<CameraFormState> = _form

    val detector: StateFlow<DetectorSettings> = detectorSettings.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, DetectorSettings())

    val monitoringRunning: StateFlow<Boolean> = monitoringState.serviceRunning
    val audioLevel: StateFlow<Float> = monitoringState.audioLevel
    val detectorPhase: StateFlow<SoundDetector.Phase> = monitoringState.detectorPhase
    val monitoringConnection: StateFlow<ConnectionState> = monitoringState.connection

    val canMonitor: StateFlow<Boolean> =
        combine(selectedCamera, monitoringRunning) { camera, running ->
            // A stale pre-normalization rtsps camera can't be monitored
            // (Media3 has no TLS); a running service can always be switched off.
            (camera != null && StreamUrlValidator.isMonitorable(camera.url)) || running
        }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

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
        val camera = Camera(
            id = state.editingId ?: UUID.randomUUID().toString(),
            name = state.name.trim(),
            url = StreamUrlValidator.normalize(state.url),
        )
        viewModelScope.launch { cameraStore.upsert(camera) }
        _form.value = CameraFormState()
    }

    fun deleteCamera(id: String) {
        viewModelScope.launch { cameraStore.remove(id) }
        if (_form.value.editingId == id) {
            _form.value = CameraFormState()
        }
    }

    fun selectCamera(id: String) {
        viewModelScope.launch { cameraStore.select(id) }
    }

    fun onDetectorChange(transform: (DetectorSettings) -> DetectorSettings) {
        viewModelScope.launch { detectorSettings.update(transform) }
    }

    companion object {
        fun factory(
            cameraStore: CameraStore,
            detectorSettings: DetectorSettingsStore,
            monitoringState: MonitoringState,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { HomeViewModel(cameraStore, detectorSettings, monitoringState) }
        }
    }
}

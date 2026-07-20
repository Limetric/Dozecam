package app.dozecam.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.dozecam.audio.SoundDetector
import app.dozecam.data.DetectorSettings
import app.dozecam.data.DetectorSettingsStore
import app.dozecam.data.StreamSettings
import app.dozecam.data.StreamUrlValidator
import app.dozecam.monitoring.MonitoringState
import app.dozecam.player.ConnectionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HomeViewModel(
    private val settings: StreamSettings,
    private val detectorSettings: DetectorSettingsStore,
    monitoringState: MonitoringState,
) : ViewModel() {

    private val _urlInput = MutableStateFlow("")
    val urlInput: StateFlow<String> = _urlInput

    val canWatch: StateFlow<Boolean> = _urlInput
        .map { StreamUrlValidator.isValid(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val detector: StateFlow<DetectorSettings> = detectorSettings.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, DetectorSettings())

    val monitoringRunning: StateFlow<Boolean> = monitoringState.serviceRunning
    val audioLevel: StateFlow<Float> = monitoringState.audioLevel
    val detectorPhase: StateFlow<SoundDetector.Phase> = monitoringState.detectorPhase
    val monitoringConnection: StateFlow<ConnectionState> = monitoringState.connection

    init {
        viewModelScope.launch {
            val saved = settings.streamUrl.first()
            if (_urlInput.value.isEmpty()) {
                _urlInput.value = saved
            }
        }
    }

    fun onUrlChange(value: String) {
        _urlInput.value = value
    }

    /** Persists the trimmed URL and returns it for immediate playback. */
    fun commitUrl(): String {
        val url = _urlInput.value.trim()
        viewModelScope.launch { settings.setStreamUrl(url) }
        return url
    }

    fun onDetectorChange(settings: DetectorSettings) {
        viewModelScope.launch { detectorSettings.update(settings) }
    }

    companion object {
        fun factory(
            settings: StreamSettings,
            detectorSettings: DetectorSettingsStore,
            monitoringState: MonitoringState,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer { HomeViewModel(settings, detectorSettings, monitoringState) }
        }
    }
}

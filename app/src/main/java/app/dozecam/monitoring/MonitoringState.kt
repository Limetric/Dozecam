package app.dozecam.monitoring

import app.dozecam.audio.SoundDetector
import app.dozecam.player.ConnectionState
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Live monitoring facts shared between [MonitoringService] (writer) and the
 * UI (reader). Owned by the app container so both sides outlive each other
 * safely.
 */
class MonitoringState {
    val serviceRunning = MutableStateFlow(false)
    val audioLevel = MutableStateFlow(0f)
    val detectorPhase = MutableStateFlow(SoundDetector.Phase.ARMED)
    val connection = MutableStateFlow<ConnectionState>(ConnectionState.Connecting)
    val lastAlertAtMs = MutableStateFlow<Long?>(null)
}

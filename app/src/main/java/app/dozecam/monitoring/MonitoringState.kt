package app.dozecam.monitoring

import app.dozecam.audio.SoundDetector
import app.dozecam.player.ConnectionState
import kotlinx.coroutines.flow.MutableStateFlow

/** What the monitor knows about one camera it is listening to. */
data class CameraMonitorState(
    val cameraId: String,
    val name: String,
    val level: Float = 0f,
    val phase: SoundDetector.Phase = SoundDetector.Phase.ARMED,
    val connection: ConnectionState = ConnectionState.Connecting,
)

/**
 * Live monitoring facts shared between [MonitoringService] (writer) and the
 * UI (reader). Owned by the app container so both sides outlive each other
 * safely.
 *
 * Every enabled camera is monitored independently, so everything the service
 * reports is keyed by camera id: one flaky camera reconnecting must never be
 * readable as "monitoring is down". Derived views (peak level, aggregate
 * status) belong to the consumer, which has a scope to derive them in.
 */
class MonitoringState {

    val serviceRunning = MutableStateFlow(false)

    /** Per-camera state, keyed by camera id. */
    val cameras = MutableStateFlow<Map<String, CameraMonitorState>>(emptyMap())

    val lastAlertAtMs = MutableStateFlow<Long?>(null)

    /** The camera whose sound fired the most recent alert. */
    val lastAlertCameraId = MutableStateFlow<String?>(null)

    /**
     * Set when the user deliberately switches monitoring off, cleared when they
     * switch it back on or finish onboarding. Deliberately in memory only: it
     * suppresses the viewer's auto-arm for the rest of this process, so a
     * rotation cannot silently re-arm what the user just turned off, while a
     * cold start still comes up armed.
     */
    val userStopped = MutableStateFlow(false)

    /**
     * The "always armed" rule, in one place: the viewer arms monitoring when it
     * comes to the front unless there is nothing to listen to, it is already
     * running, or the user switched it off during this process's lifetime.
     */
    fun shouldAutoArm(enabledCameraCount: Int): Boolean =
        enabledCameraCount > 0 && !serviceRunning.value && !userStopped.value

    fun put(state: CameraMonitorState) {
        cameras.value = cameras.value + (state.cameraId to state)
    }

    /** No-op for a camera that is no longer monitored, so a late event cannot resurrect it. */
    fun update(cameraId: String, transform: (CameraMonitorState) -> CameraMonitorState) {
        val current = cameras.value[cameraId] ?: return
        cameras.value = cameras.value + (cameraId to transform(current))
    }

    fun remove(cameraId: String) {
        cameras.value = cameras.value - cameraId
    }

    fun clear() {
        cameras.value = emptyMap()
    }
}

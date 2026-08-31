package app.dozecam.monitoring

import app.dozecam.audio.SoundDetector
import app.dozecam.player.ConnectionState
import kotlinx.coroutines.flow.MutableStateFlow

/** What the monitor knows about one camera it is listening to. */
data class CameraMonitorState(
    val cameraId: String,
    val name: String,
    /**
     * The last RMS decoded on the *current* connection, or null before any
     * has been. Nullable rather than zero because the two mean opposite
     * things: 0.0 is a measured silence, null is "nobody has heard this
     * stream yet" — and a meter shown for the latter would be lying.
     */
    val level: Float? = null,
    val phase: SoundDetector.Phase = SoundDetector.Phase.ARMED,
    val connection: ConnectionState = ConnectionState.Connecting,
) {
    /**
     * The one way a connection change is applied, so the rule travels with it:
     * a level measured on a stream that has since dropped is evidence about
     * the past, and it must not survive into a connection that has not yet
     * decoded anything — a player can reach Live off its clock alone, before
     * (or without ever) producing a PCM buffer.
     */
    fun withConnection(connection: ConnectionState): CameraMonitorState = copy(
        connection = connection,
        level = if (connection == ConnectionState.Live) level else null,
    )
}

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
     * Bumped whenever the signed-in console changes, which rewrites which
     * cameras have a livestream to listen over — and can happen without the
     * camera list moving at all: sign in, then leave onboarding without
     * importing anything. Everything that decides what can be monitored reads
     * this, because nothing else would tell them.
     */
    val consoleGeneration = MutableStateFlow(0)

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

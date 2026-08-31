package app.dozecam.monitoring

import android.content.Context
import app.dozecam.R
import app.dozecam.audio.SoundDetector
import app.dozecam.player.ConnectionState

/**
 * One line for the whole nursery. A triggered camera outranks everything,
 * then the worst connection state wins — a status that said "listening"
 * while a camera was actually offline would be a lie in the one direction
 * that matters.
 */
internal object MonitoringStatus {

    /**
     * The status line, plus — on the healthy listening branch only — the live
     * level that proves it.
     */
    data class Status(val text: String, val level: Float?)

    fun of(
        context: Context,
        anyMonitors: Boolean,
        states: Collection<CameraMonitorState>,
        enabledCount: Int,
    ): Status {
        if (!anyMonitors) return Status(context.getString(R.string.monitoring_status_nothing), null)
        if (states.isEmpty()) {
            return Status(context.getString(R.string.monitoring_status_starting), null)
        }
        states.firstOrNull { it.phase == SoundDetector.Phase.TRIGGERED }?.let {
            return Status(context.getString(R.string.monitoring_status_alerting, it.name), null)
        }
        val offline = states.filter { it.connection is ConnectionState.Offline }
        if (offline.isNotEmpty()) {
            return Status(context.getString(R.string.monitoring_status_offline), null)
        }
        val reconnecting = states.filter { it.connection is ConnectionState.Reconnecting }
        if (reconnecting.isNotEmpty()) {
            return Status(
                context.resources.getQuantityString(
                    R.plurals.monitoring_status_reconnecting_cameras,
                    reconnecting.size,
                    reconnecting.size,
                ),
                null,
            )
        }
        if (states.any { it.connection is ConnectionState.Connecting }) {
            return Status(context.getString(R.string.monitoring_status_starting), null)
        }
        val text = context.resources.getQuantityString(
            R.plurals.monitoring_status_listening_cameras,
            states.size,
            states.size,
        ).let { listening ->
            // A camera that is enabled but not monitorable is silently absent
            // from the listening count; say so rather than overstate coverage.
            if (enabledCount > states.size) {
                context.getString(
                    R.string.monitoring_status_partial,
                    listening,
                    enabledCount - states.size,
                )
            } else {
                listening
            }
        }
        // Only the healthy listening line carries a level: the loudest camera,
        // matching the in-app meter, decoded moments ago — the one state whose
        // steadiness could otherwise be mistaken for staleness.
        return Status(text, states.maxOf { it.level })
    }
}

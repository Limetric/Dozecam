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
        /** The cameras listen mode is playing out of the speaker, if any. */
        aloudCameraIds: Set<String> = emptySet(),
        /** Whether a loud room reaches anyone at all. */
        alertsEnabled: Boolean = true,
        /** Every way the monitor is currently failing, oldest first. */
        failures: List<MonitoringFailure> = emptyList(),
        /** The last failure to have cleared, for the note that it happened. */
        recovered: RecoveredFailure? = null,
    ): Status = discloseAlertsOff(
        context = context,
        alertsEnabled = alertsEnabled,
        status = disclose(
            context = context,
            states = states,
            aloudCameraIds = aloudCameraIds,
            status = noteRecovered(
                context = context,
                recovered = recovered,
                status = failing(context, failures)
                    ?: listening(context, anyMonitors, states, enabledCount),
            ),
        ),
    )

    /**
     * A failure past its grace period outranks every other line: the
     * "reconnecting" and "offline" lines describe a monitor that expects to
     * be back, and this is the line for one that has been gone too long to
     * say so. It takes precedence over a triggered camera too — that room
     * has its own card and its own alarm, and the ongoing line is where a
     * failure is kept for as long as it lasts.
     */
    private fun failing(context: Context, failures: List<MonitoringFailure>): Status? {
        val first = failures.firstOrNull() ?: return null
        val line = context.getString(
            R.string.monitoring_status_failing,
            FailureWording.title(context, first.reason),
            FailureWording.time(context, first.sinceMs),
        )
        val text = if (failures.size > 1) {
            context.getString(R.string.monitoring_status_failing_more, line, failures.size - 1)
        } else {
            line
        }
        return Status(text, null)
    }

    /**
     * A failure that has cleared leaves a note behind it. Honesty is only
     * useful to someone looking, and nobody was at 3am — so the morning's
     * glance at the shade has to be able to learn that the nursery was
     * unreachable for twenty minutes, even though it is back.
     */
    private fun noteRecovered(
        context: Context,
        recovered: RecoveredFailure?,
        status: Status,
    ): Status {
        if (recovered == null) return status
        return status.copy(
            text = context.getString(
                R.string.monitoring_status_recovered,
                status.text,
                FailureWording.title(context, recovered.reason),
                FailureWording.time(context, recovered.clearedAtMs),
            ),
        )
    }

    /**
     * A monitor that will not wake anyone has to say so where it is seen:
     * "watching for sound" over a phone that has been asked to keep quiet
     * about it is the sort of reassurance this app exists to refuse.
     */
    private fun discloseAlertsOff(
        context: Context,
        alertsEnabled: Boolean,
        status: Status,
    ): Status = if (alertsEnabled) {
        status
    } else {
        status.copy(text = context.getString(R.string.monitoring_status_alerts_off, status.text))
    }

    /**
     * A phone broadcasting a bedroom says so, in front of whatever else it has
     * to report. Not instead of it: an offline camera is still the more urgent
     * half of the line, and a speaker that is on does not make it less true.
     *
     * Reads the cameras that are *actually* audible rather than the switch, so
     * this can only ever understate what the phone is doing. One room is named;
     * more than one is counted, because the line has room for a name or a
     * number and a list of bedrooms cut off mid-word would say less than
     * either.
     */
    private fun disclose(
        context: Context,
        states: Collection<CameraMonitorState>,
        aloudCameraIds: Set<String>,
        status: Status,
    ): Status {
        val aloud = states.filter { it.cameraId in aloudCameraIds }
        val what = when (aloud.size) {
            0 -> return status
            1 -> aloud.single().name
            else -> context.resources.getQuantityString(
                R.plurals.monitoring_status_aloud_rooms,
                aloud.size,
                aloud.size,
            )
        }
        return status.copy(
            text = context.getString(R.string.monitoring_status_aloud, what, status.text),
        )
    }

    private fun listening(
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
        return Status(text, states.mapNotNull { it.level }.maxOrNull())
    }
}

package app.dozecam.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.dozecam.R
import app.dozecam.monitoring.ReadinessCheck
import app.dozecam.monitoring.ReadinessFinding
import app.dozecam.monitoring.ReadinessRemedy
import app.dozecam.monitoring.ReadinessState
import app.dozecam.monitoring.problems
import app.dozecam.monitoring.worstState

/**
 * What the bedtime check *says*. Shared by the full card in settings and the
 * compact one in the viewer so the two can never describe the same failure
 * differently — the whole value of this feature is that there is one answer to
 * "will this wake me?", and two wordings for it would be two answers.
 *
 * Every check has a sentence for passing and a sentence for failing rather than
 * one label with a tick beside it: a row that reads "Alerts cannot wake the
 * screen" has already told a half-asleep parent what is wrong, where "Wake
 * screen ✗" makes them work it out.
 */
@Composable
fun readinessSentence(finding: ReadinessFinding): String {
    val passed = finding.state == ReadinessState.PASS
    return stringResource(
        when (finding.check) {
            ReadinessCheck.NOTIFICATIONS ->
                if (passed) R.string.readiness_notifications_pass
                else R.string.readiness_notifications_fail
            ReadinessCheck.ALERT_CHANNEL ->
                if (passed) R.string.readiness_channel_pass else R.string.readiness_channel_fail
            ReadinessCheck.ALERT_CHANNEL_PRIORITY ->
                if (passed) R.string.readiness_channel_priority_pass
                else R.string.readiness_channel_priority_fail
            ReadinessCheck.WAKE_SCREEN ->
                if (passed) R.string.readiness_wake_screen_pass
                else R.string.readiness_wake_screen_fail
            ReadinessCheck.ALERTS_ON ->
                if (passed) R.string.readiness_alerts_on_pass else R.string.readiness_alerts_on_fail
            ReadinessCheck.ALARM_VOLUME ->
                if (passed) R.string.readiness_alarm_volume_pass
                else R.string.readiness_alarm_volume_fail
            ReadinessCheck.DO_NOT_DISTURB ->
                if (passed) R.string.readiness_dnd_pass else R.string.readiness_dnd_fail
            ReadinessCheck.ALERT_SIGNAL ->
                if (passed) R.string.readiness_signal_pass else R.string.readiness_signal_fail
            ReadinessCheck.MONITORING ->
                if (passed) R.string.readiness_monitoring_pass
                else R.string.readiness_monitoring_fail
            ReadinessCheck.BATTERY_OPTIMISATION ->
                if (passed) R.string.readiness_battery_pass else R.string.readiness_battery_fail
            ReadinessCheck.POWER ->
                if (passed) R.string.readiness_power_pass else R.string.readiness_power_fail
            ReadinessCheck.LOCAL_NETWORK ->
                if (passed) R.string.readiness_local_network_pass
                else R.string.readiness_local_network_fail
            // The one check with three outcomes rather than two: passing,
            // failing, and having nothing to say yet because nothing is
            // listening. Reporting that last one as a failure would blame the
            // cameras for the monitor being off.
            ReadinessCheck.CAMERAS_HEARD -> when {
                passed -> R.string.readiness_cameras_pass
                finding.state == ReadinessState.WARN -> R.string.readiness_cameras_unknown
                finding.cameras.isEmpty() -> R.string.readiness_cameras_none
                else -> R.string.readiness_cameras_fail
            }
        },
    )
}

/** Why it matters, and what to do about it. Only ever shown for a check that is not passing. */
@Composable
fun readinessReason(finding: ReadinessFinding): String? {
    if (finding.state == ReadinessState.PASS) return null
    return when (finding.check) {
        ReadinessCheck.NOTIFICATIONS -> stringResource(R.string.readiness_notifications_why)
        ReadinessCheck.ALERT_CHANNEL -> stringResource(R.string.readiness_channel_why)
        ReadinessCheck.ALERT_CHANNEL_PRIORITY ->
            stringResource(R.string.readiness_channel_priority_why)
        ReadinessCheck.WAKE_SCREEN -> stringResource(R.string.readiness_wake_screen_why)
        ReadinessCheck.ALERTS_ON -> stringResource(R.string.readiness_alerts_on_why)
        ReadinessCheck.ALARM_VOLUME -> stringResource(R.string.readiness_alarm_volume_why)
        ReadinessCheck.DO_NOT_DISTURB -> stringResource(R.string.readiness_dnd_why)
        ReadinessCheck.ALERT_SIGNAL -> stringResource(R.string.readiness_signal_why)
        ReadinessCheck.MONITORING -> stringResource(R.string.readiness_monitoring_why)
        ReadinessCheck.BATTERY_OPTIMISATION -> stringResource(R.string.readiness_battery_why)
        ReadinessCheck.POWER -> stringResource(R.string.readiness_power_why)
        ReadinessCheck.LOCAL_NETWORK -> stringResource(R.string.readiness_local_network_why)
        ReadinessCheck.CAMERAS_HEARD -> when {
            finding.state == ReadinessState.WARN ->
                stringResource(R.string.readiness_cameras_unknown_why)
            finding.cameras.isEmpty() -> stringResource(R.string.readiness_cameras_none_why)
            // Named rather than counted: "two cameras" sends someone hunting,
            // and the monitor already knows which rooms they are.
            else -> stringResource(
                R.string.readiness_cameras_why,
                finding.cameras.joinToString(", ") { it.name },
            )
        }
    }
}

/** What the button does, said as the action rather than as the problem. */
@Composable
fun readinessRemedyLabel(remedy: ReadinessRemedy): String? = when (remedy) {
    ReadinessRemedy.NONE -> null
    ReadinessRemedy.REQUEST_NOTIFICATIONS,
    ReadinessRemedy.FULL_SCREEN_INTENT_SETTINGS,
    -> stringResource(R.string.readiness_remedy_allow)
    ReadinessRemedy.TURN_ALERTS_ON,
    ReadinessRemedy.TURN_CHIME_ON,
    -> stringResource(R.string.readiness_remedy_turn_on)
    ReadinessRemedy.NOTIFICATION_SETTINGS,
    ReadinessRemedy.BATTERY_SETTINGS,
    -> stringResource(R.string.readiness_remedy_open_settings)
    ReadinessRemedy.SOUND_SETTINGS -> stringResource(R.string.readiness_remedy_sound)
    ReadinessRemedy.DO_NOT_DISTURB_SETTINGS -> stringResource(R.string.readiness_remedy_dnd)
    ReadinessRemedy.START_MONITORING -> stringResource(R.string.readiness_remedy_start)
    ReadinessRemedy.GRANT_LOCAL_NETWORK -> stringResource(R.string.readiness_remedy_allow)
    ReadinessRemedy.CAMERA_SETTINGS -> stringResource(R.string.readiness_remedy_cameras)
}

/**
 * The whole night in one line. A count rather than a list, because the count is
 * the only part that has to be legible at a glance; the rows underneath are
 * where the detail lives.
 */
@Composable
fun readinessHeadline(findings: List<ReadinessFinding>): String {
    val problems = findings.problems()
    return when {
        problems.isEmpty() -> stringResource(R.string.readiness_ready)
        findings.worstState() == ReadinessState.WARN -> stringResource(R.string.readiness_warnings)
        else -> pluralStringResource(R.plurals.readiness_problems, problems.size, problems.size)
    }
}

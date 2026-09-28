package app.dozecam.monitoring

import android.content.Context
import android.text.format.DateFormat
import app.dozecam.R
import java.util.Date

/**
 * How a failure is named, in one place, so the alert card, the ongoing
 * notification and the viewer's notice all say the same thing about it.
 */
object FailureWording {

    /** Short enough for a status line or a pill: what is wrong, and where. */
    fun title(context: Context, reason: FailureReason): String = when (reason) {
        is FailureReason.CameraUnreachable -> context.getString(
            if (reason.networkDown) R.string.failure_network_down_title
            else R.string.failure_camera_unreachable_title,
            reason.name,
        )
        is FailureReason.LowBattery ->
            context.getString(R.string.failure_battery_low_title, reason.percent)
        FailureReason.NotificationsBlocked ->
            context.getString(R.string.failure_notifications_blocked_title)
        FailureReason.ScreenWakeBlocked ->
            context.getString(R.string.failure_screen_wake_blocked_title)
    }

    /** A sentence for the card: why it matters, and what to do. */
    fun detail(context: Context, failure: MonitoringFailure): String = when (val reason = failure.reason) {
        is FailureReason.CameraUnreachable -> context.getString(
            if (reason.networkDown) R.string.failure_network_down_detail
            else R.string.failure_camera_unreachable_detail,
            time(context, failure.sinceMs),
        )
        is FailureReason.LowBattery -> context.getString(R.string.failure_battery_low_detail)
        FailureReason.NotificationsBlocked ->
            context.getString(R.string.failure_notifications_blocked_detail)
        FailureReason.ScreenWakeBlocked ->
            context.getString(R.string.failure_screen_wake_blocked_detail)
    }

    fun time(context: Context, atMs: Long): String =
        DateFormat.getTimeFormat(context).format(Date(atMs))
}

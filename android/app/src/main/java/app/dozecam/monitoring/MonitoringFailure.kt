package app.dozecam.monitoring

import android.content.Intent
import android.os.BatteryManager
import app.dozecam.player.ConnectionState

/**
 * One way Dozecam can stop being a baby monitor while it is armed.
 *
 * Each reason is keyed so it can be told apart from the last time it happened:
 * a camera that drops, comes back, and drops again is two failures, and the
 * second one is announced afresh.
 */
sealed interface FailureReason {
    /** What identifies this failure across evaluations. */
    val key: String

    /**
     * A monitored camera the monitor has not heard from — not live, whatever
     * the watchdog is doing about it. [networkDown] says why, when the phone
     * itself has no network: the camera is not the thing that failed.
     */
    data class CameraUnreachable(
        val cameraId: String,
        val name: String,
        val networkDown: Boolean,
    ) : FailureReason {
        override val key: String get() = "camera:$cameraId"
    }

    /** The phone is running down with nothing to charge it. */
    data class LowBattery(val percent: Int) : FailureReason {
        override val key: String get() = "battery"
    }

    /** Notifications are turned off for the app, so no alert card can be shown. */
    data object NotificationsBlocked : FailureReason {
        override val key: String get() = "notifications"
    }

    /** Full-screen intent access is withdrawn, so no alert can wake the screen. */
    data object ScreenWakeBlocked : FailureReason {
        override val key: String get() = "screen-wake"
    }
}

/**
 * A failure that has outlasted its grace period: what is wrong and since when.
 * [sinceMs] is wall-clock time, for telling the user; every deadline is
 * measured on the monotonic clock inside [FailureLedger].
 */
data class MonitoringFailure(
    val reason: FailureReason,
    val sinceMs: Long,
)

/** A failure that has cleared, left as a note that it happened. */
data class RecoveredFailure(
    val reason: FailureReason,
    val sinceMs: Long,
    val clearedAtMs: Long,
)

/** The phone's battery as last reported. */
data class BatteryStatus(val percent: Int, val plugged: Boolean) {

    /**
     * Whether the battery is the problem. With hysteresis: once low, it stays
     * low until it has climbed clear of the line or a charger is connected,
     * so a reading that hovers on the threshold cannot raise the same alarm
     * over and over.
     */
    fun isLow(wasLow: Boolean): Boolean = when {
        plugged -> false
        wasLow -> percent < LOW_PERCENT + CLEAR_MARGIN
        else -> percent <= LOW_PERCENT
    }

    companion object {
        /**
         * Well above the point where Android starts shutting things down: a
         * monitor that dies at 20% having warned at 25% is a monitor that
         * worked.
         */
        const val LOW_PERCENT = 25

        const val CLEAR_MARGIN = 5

        /** Reads an [Intent.ACTION_BATTERY_CHANGED] broadcast, or null if it says nothing usable. */
        fun of(intent: Intent?): BatteryStatus? {
            if (intent == null) return null
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level < 0 || scale <= 0) return null
            return BatteryStatus(
                percent = (level * 100 / scale).coerceIn(0, 100),
                plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0,
            )
        }
    }
}

/** Everything the ledger judges the monitor by, as of one moment. */
data class MonitoringHealth(
    val cameras: Collection<CameraMonitorState>,
    val networkOnline: Boolean,
    /** Null until the first battery reading arrives. */
    val battery: BatteryStatus?,
    val notificationsAllowed: Boolean,
    val screenWakeAllowed: Boolean,
)

/**
 * Keeps the book on what is wrong, and decides when to say so.
 *
 * Two rules are the whole design. A failure has to last the grace period
 * before it counts at all — so an ordinary reconnect never fires, and
 * neither does a permission the user is a screen away from granting — and it
 * is announced exactly once for as long as it lasts, however many times the
 * ledger is asked. An alarm for every brief reconnect would train people to
 * ignore it, which is worse than no alarm.
 *
 * Pure apart from the clocks it is given, so the timings can be checked
 * without a device.
 */
class FailureLedger(
    /** Monotonic: deadlines must not move when the clock is set. */
    private val monotonicClock: () -> Long,
    /** Wall clock, only for the "since" the user is shown. */
    private val wallClock: () -> Long,
) {

    /** What one evaluation changed. */
    data class Update(
        /** Every failure past its grace period, oldest first. */
        val active: List<MonitoringFailure>,
        /** The failures that crossed into [active] on this evaluation — say these out loud. */
        val announce: List<MonitoringFailure>,
        /** Failures that were active and are no longer. */
        val recovered: List<RecoveredFailure>,
        /** The charger was pulled since the last evaluation, with the monitor armed. */
        val unplugged: Boolean,
    )

    private class Entry(
        var reason: FailureReason,
        val sinceMonotonicMs: Long,
        val sinceMs: Long,
        var announced: Boolean = false,
    )

    private val entries = linkedMapOf<String, Entry>()
    private var batteryLow = false
    private var plugged: Boolean? = null

    /** Judges [health] now, against what was true last time. */
    fun evaluate(health: MonitoringHealth, graceMs: Long): Update {
        val now = monotonicClock()
        val wallNow = wallClock()
        val current = reasons(health).associateBy { it.key }

        val recovered = mutableListOf<RecoveredFailure>()
        entries.keys.filter { it !in current }.forEach { key ->
            val entry = entries.remove(key) ?: return@forEach
            // Only a failure that was ever counted leaves a note; a flap
            // inside the grace period never happened, as far as anyone is told.
            if (entry.announced) {
                recovered += RecoveredFailure(entry.reason, entry.sinceMs, wallNow)
            }
        }

        current.values.forEach { reason ->
            val entry = entries[reason.key]
            // The name follows renames and the battery reading follows the
            // battery; the start of the failure does not move.
            if (entry != null) entry.reason = reason else entries[reason.key] = Entry(reason, now, wallNow)
        }

        val announce = mutableListOf<MonitoringFailure>()
        val active = entries.values.mapNotNull { entry ->
            if (now - entry.sinceMonotonicMs < graceMs) return@mapNotNull null
            val failure = MonitoringFailure(entry.reason, entry.sinceMs)
            if (!entry.announced) {
                entry.announced = true
                announce += failure
            }
            failure
        }

        val wasPlugged = plugged
        val nowPlugged = health.battery?.plugged
        if (nowPlugged != null) plugged = nowPlugged
        val unplugged = wasPlugged == true && nowPlugged == false

        return Update(active, announce, recovered, unplugged)
    }

    private fun reasons(health: MonitoringHealth): List<FailureReason> = buildList {
        health.cameras
            .filter { it.connection != ConnectionState.Live }
            .forEach {
                add(FailureReason.CameraUnreachable(it.cameraId, it.name, !health.networkOnline))
            }
        health.battery?.let { battery ->
            batteryLow = battery.isLow(batteryLow)
            if (batteryLow) add(FailureReason.LowBattery(battery.percent))
        }
        if (!health.notificationsAllowed) add(FailureReason.NotificationsBlocked)
        if (!health.screenWakeAllowed) add(FailureReason.ScreenWakeBlocked)
    }

    companion object {
        const val MIN_GRACE_MS = 30_000L
        const val MAX_GRACE_MS = 5 * 60_000L
    }
}

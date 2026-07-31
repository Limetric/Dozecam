package app.dozecam.monitoring

/**
 * The shape of an alert: how loud it is at a given moment, when the next burst
 * is due, and when it gives up.
 *
 * Pure on purpose. This is the part of the alarm most likely to be wrong at 3am
 * and the hardest to check on a device, so it is decided here and the player
 * only carries it out.
 */
data class AlarmSchedule(
    /** Climb from a gentle first note rather than starting at full volume. */
    val ramp: Boolean = true,
    val rampMs: Long = DEFAULT_RAMP_MS,
    val repeatIntervalMs: Long = DEFAULT_REPEAT_INTERVAL_MS,
    /** How long an unacknowledged alarm keeps trying before it gives up. */
    val maxDurationMs: Long = DEFAULT_MAX_DURATION_MS,
    /**
     * Ceiling as a fraction of the phone's own alarm volume. The ramp climbs to
     * this and never past it: Dozecam plays on the alarm stream but never
     * rewrites what the user set there.
     */
    val ceiling: Float = 1f,
) {

    /** Volume [elapsedMs] into the alarm, as a fraction of the alarm stream. */
    fun volumeAt(elapsedMs: Long): Float {
        val climbed = when {
            !ramp || rampMs <= 0L -> 1f
            elapsedMs <= 0L -> RAMP_START
            elapsedMs >= rampMs -> 1f
            else -> RAMP_START + (1f - RAMP_START) * (elapsedMs.toFloat() / rampMs)
        }
        return (ceiling.coerceIn(0f, 1f) * climbed).coerceIn(0f, 1f)
    }

    /**
     * Whether a tick carrying the alarm from [fromMs] to [toMs] crossed the
     * start of a repeat. Derived from elapsed time rather than counted, so a
     * tick the system delayed cannot lose a burst or fire two at once.
     */
    fun burstDue(fromMs: Long, toMs: Long): Boolean =
        repeatIntervalMs > 0L && toMs > fromMs && toMs / repeatIntervalMs > fromMs / repeatIntervalMs

    /**
     * Measured from the most recent trigger, not from the first: a room that is
     * still going off half an hour later has earned another five minutes.
     */
    fun expired(sinceLastTriggerMs: Long): Boolean = sinceLastTriggerMs >= maxDurationMs

    companion object {
        /** Quiet enough to surface a sleeper without launching them out of bed. */
        const val RAMP_START = 0.15f

        const val DEFAULT_RAMP_MS = 5_000L
        const val DEFAULT_REPEAT_INTERVAL_MS = 8_000L
        const val DEFAULT_MAX_DURATION_MS = 5 * 60_000L

        const val MIN_REPEAT_INTERVAL_MS = 3_000L
        const val MAX_REPEAT_INTERVAL_MS = 30_000L
    }
}

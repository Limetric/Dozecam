package app.dozecam.monitoring

import android.os.SystemClock
import kotlin.math.roundToInt

/**
 * Decides when the ongoing status notification is worth reposting.
 *
 * The problem it solves: a healthy quiet night used to leave "Listening to
 * 2 cameras" frozen in the shade for hours — indistinguishable, to the person
 * glancing at it, from a wedged process whose last posted text happened to say
 * the same thing. The stream watchdog already turns real failures into
 * different text; what was missing was visible proof of life while nothing is
 * wrong.
 *
 * So while the status line is the healthy listening one, each post carries two
 * live facts — the loudest camera's level, coarsely bucketed, and the minute
 * the app last actually posted — and this class meters them: a text change
 * goes out immediately, level motion at most every [minIntervalMs], and a
 * silent room still reposts once a minute as the stamp rolls over. The stamp
 * can only advance because this process really evaluated just now — a wedged
 * process posts nothing and visibly goes stale — while dead streams become
 * different text via the playback watchdog, which takes the heartbeat away.
 *
 * Two clocks on purpose: the throttle runs on monotonic [elapsed] time so a
 * wall clock set backwards cannot freeze the heartbeat for hours, while the
 * displayed stamp stays on [now] wall time, the clock the user reads.
 */
class StatusHeartbeat(
    private val minIntervalMs: Long = MIN_INTERVAL_MS,
    private val now: () -> Long = System::currentTimeMillis,
    private val elapsed: () -> Long = SystemClock::elapsedRealtime,
) {

    /** One posting's worth of shade content; equal displays never repost. */
    data class Display(val text: String, val levelBucket: Int?, val minute: Long?) {
        val checkedAtMs: Long? get() = minute?.let { it * MINUTE_MS }
    }

    private var posted: Display? = null
    private var postedAtElapsedMs = Long.MIN_VALUE / 2

    /**
     * Returns the display to post now, or null when the shade already says
     * everything this would. [level] is the loudest monitored camera's RMS
     * while the healthy listening text is showing, and null for every other
     * state — those keep their text-change-only behavior, because "offline"
     * wearing a fresh timestamp would read as reassurance it has not earned.
     */
    fun offer(text: String, level: Float?): Display? {
        val display = Display(
            text = text,
            levelBucket = level?.let(::bucket),
            minute = if (level != null) now() / MINUTE_MS else null,
        )
        val last = posted
        val elapsedMs = elapsed()
        val textChanged = display.text != last?.text
        if (!textChanged && (display == last || elapsedMs - postedAtElapsedMs < minIntervalMs)) {
            return null
        }
        posted = display
        postedAtElapsedMs = elapsedMs
        return display
    }

    private fun bucket(level: Float): Int =
        (level / LEVEL_SCALE * LEVEL_BUCKETS).roundToInt().coerceIn(0, LEVEL_BUCKETS)

    companion object {
        /**
         * Mirrors [app.dozecam.ui.components.AudioLevelMeter]: the useful RMS
         * range is 0..0.5, so the shade's bar and the in-app meter agree.
         */
        const val LEVEL_SCALE = 0.5f

        /** Coarse on purpose: enough steps to visibly move, few enough to repost rarely. */
        const val LEVEL_BUCKETS = 10

        /** The fastest the shade is allowed to breathe. */
        const val MIN_INTERVAL_MS = 2_500L

        private const val MINUTE_MS = 60_000L
    }
}

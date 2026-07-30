package app.dozecam.monitoring

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import app.dozecam.data.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The alarm, and the whole audible surface of the product: the alert
 * notification channel is deliberately silent, so if this makes no noise then
 * nothing does.
 *
 * It is built for one job — waking an adult who is asleep, from a phone on a
 * nightstand that is very probably on silent:
 *
 * - Alarm usage, so a silent ringer and Do Not Disturb's priority rules do not
 *   apply, and the alert rides alarm volume rather than whatever the viewer is
 *   playing.
 * - A ramp, because a gentle first note that becomes insistent wakes a parent
 *   without launching them out of bed.
 * - Repeats, because one tone is easy to sleep through.
 * - Latched: the trigger starts an alarm that owns its own lifetime. The
 *   detector re-arming when the room goes quiet deliberately does *not* stop it
 *   — a baby who cries for forty seconds and settles is exactly the alert
 *   nobody heard — so only a person, or the give-up cap, ends it.
 *
 * App-scoped rather than owned by [MonitoringService]: the viewer has to be able
 * to acknowledge it, and the process outlives any one screen.
 */
class AlertSignaler(
    private val player: AlarmPlayer,
    private val vibrator: AlarmVibrator,
    private val scope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    /** Monotonic and unaffected by the clock being set; injectable so tests need no real time. */
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val tickMs: Long = TICK_MS,
) {

    constructor(context: Context) : this(
        player = MediaPlayerAlarmPlayer(context.applicationContext),
        vibrator = SystemAlarmVibrator(context.applicationContext),
    )

    private val _alarmingCameraId = MutableStateFlow<String?>(null)

    /** The camera whose sound is currently sounding the alarm, if any. */
    val alarmingCameraId: StateFlow<String?> = _alarmingCameraId.asStateFlow()

    val isAlarming: Boolean
        get() = _alarmingCameraId.value != null

    private var job: Job? = null
    private var previewJob: Job? = null

    /** Distinguishes the run that owns the state from one still unwinding after a cancel. */
    private var generation = 0L

    /** The same guard for previews, which share the one player with the alarm. */
    private var previewGeneration = 0L

    private var lastTriggerAtMs = 0L

    /**
     * Sounds the alarm for [cameraId], or — if one is already sounding — points
     * the existing alarm at the newer camera and gives it a fresh five minutes.
     * Never a second player: two alarms overlapping is noise, not urgency, and
     * the ramp already in progress must not drop back to a whisper.
     */
    fun signal(cameraId: String, settings: AppSettings) {
        lastTriggerAtMs = clock()
        _alarmingCameraId.value = cameraId
        if (job?.isActive == true) return
        // Retired by generation as well as cancelled. Today a cancelled preview
        // always finishes unwinding before the burst below starts, because both
        // go through one FIFO event loop — but nothing states that, and the
        // consequence of it ceasing to hold is a preview's cleanup silencing a
        // live alert. Ownership of the shared player is made explicit instead.
        previewGeneration++
        previewJob?.cancel()
        previewJob = null
        val token = ++generation
        job = scope.launch { run(settings, token) }
    }

    /**
     * A person is here. Called when the viewer sees a genuine touch or key press
     * while the alert is up, and when the alert notification is dismissed.
     */
    fun acknowledge() {
        if (isAlarming) stop()
    }

    /** Ends the alarm outright: monitoring stopping takes its alert with it. */
    fun stop() {
        // Bumped first so the cancelled run cannot undo the teardown below, and
        // torn down here rather than left to that run's own unwinding: a
        // coroutine cancelled before its body ever got a turn would otherwise
        // leave the alarm latched with nothing left to clear it.
        generation++
        job?.cancel()
        job = null
        previewJob?.cancel()
        previewJob = null
        teardown()
    }

    private fun teardown() {
        player.stop()
        vibrator.cancel()
        _alarmingCameraId.value = null
    }

    /**
     * One burst of the chosen sound at full chosen volume, so the choice is made
     * awake and in daylight rather than at 3am. Deliberately skips the ramp:
     * nobody wants to audition a whisper.
     */
    fun preview(settings: AppSettings) {
        if (isAlarming) return
        val token = ++previewGeneration
        previewJob?.cancel()
        previewJob = scope.launch {
            try {
                player.start(AlarmSound.uriFor(settings), settings.alertVolume.coerceIn(0f, 1f))
                delay(PREVIEW_MS)
            } finally {
                // Only the newest preview may silence the shared player; an
                // older one unwinding has no claim on what replaced it.
                if (previewGeneration == token) player.stop()
            }
        }
    }

    /**
     * Ends a preview and nothing else. Leaving settings must not silence a real
     * alarm that started while it was open — only a person can do that.
     */
    fun stopPreview() {
        if (isAlarming) return
        previewGeneration++
        previewJob?.cancel()
        previewJob = null
        player.stop()
    }

    private suspend fun run(settings: AppSettings, token: Long) {
        val schedule = settings.alarmSchedule()
        val uri = AlarmSound.uriFor(settings)
        val startedAtMs = clock()
        try {
            burst(settings, schedule, uri, elapsedMs = 0L)
            var previousMs = 0L
            while (true) {
                delay(tickMs)
                val now = clock()
                // Against the latest trigger, so a room that keeps going off
                // keeps the alarm alive rather than timing out mid-cry.
                if (schedule.expired(now - lastTriggerAtMs)) return
                val elapsedMs = now - startedAtMs
                if (schedule.burstDue(previousMs, elapsedMs)) {
                    burst(settings, schedule, uri, elapsedMs)
                } else if (settings.alertChime) {
                    // The ramp climbs through a burst, not only between them.
                    player.setVolume(schedule.volumeAt(elapsedMs))
                }
                previousMs = elapsedMs
            }
        } finally {
            // A newer signal, or a stop that has already cleaned up, may own the
            // state by now; only the current run may tear it down.
            if (generation == token) teardown()
        }
    }

    private fun burst(
        settings: AppSettings,
        schedule: AlarmSchedule,
        uri: Uri,
        elapsedMs: Long,
    ) {
        if (settings.alertChime) player.start(uri, schedule.volumeAt(elapsedMs))
        if (settings.alertVibrate) vibrator.pulse()
    }

    companion object {
        /** Fine enough for a ramp to sound continuous, coarse enough to cost nothing. */
        const val TICK_MS = 250L

        const val PREVIEW_MS = 4_000L
    }
}

internal fun AppSettings.alarmSchedule(): AlarmSchedule = AlarmSchedule(
    ramp = alertRamp,
    repeatIntervalMs = alertRepeatIntervalMs,
    ceiling = alertVolume,
)

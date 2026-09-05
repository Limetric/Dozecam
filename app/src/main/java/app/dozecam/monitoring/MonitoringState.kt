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
    /**
     * When the last buffer was decoded on the *current* connection
     * ([android.os.SystemClock.elapsedRealtime]), or null before any has been.
     *
     * Kept alongside [level] rather than derived from it because the two answer
     * different questions: the level is how loud the room is, this is whether
     * anyone is still hearing it at all. A room can sit at exactly the same
     * measured silence for an hour and be perfectly monitored, or sit at the
     * last level it ever produced while its stream quietly stopped delivering —
     * and only a timestamp tells those apart. The bedtime check
     * ([Readiness.AUDIO_STALE_MS]) is what reads it.
     *
     * Deliberately coarse — see [MonitoringService] — so that a value which
     * moves with every decoded buffer cannot defeat the conflation the status
     * heartbeat depends on.
     */
    val lastAudioAtMs: Long? = null,
    val phase: SoundDetector.Phase = SoundDetector.Phase.ARMED,
    val connection: ConnectionState = ConnectionState.Connecting,
) {
    /**
     * The one way a connection change is applied, so the rule travels with it:
     * a level measured on a stream that has since dropped is evidence about
     * the past, and it must not survive into a connection that has not yet
     * decoded anything — a player can reach Live off its clock alone, before
     * (or without ever) producing a PCM buffer. The moment that level arrived
     * goes with it, for the same reason and to the same end.
     */
    fun withConnection(connection: ConnectionState): CameraMonitorState = copy(
        connection = connection,
        level = if (connection == ConnectionState.Live) level else null,
        lastAudioAtMs = if (connection == ConnectionState.Live) lastAudioAtMs else null,
    )

    val isLive: Boolean
        get() = connection == ConnectionState.Live

    /**
     * Whether there is audio coming through this camera to turn up: live, and
     * with at least one buffer decoded on the current connection. Both, because
     * a player can reach Live off its clock alone, and a transport that cannot
     * be decoded plays on without ever producing a sample.
     */
    val isAudible: Boolean
        get() = isLive && level != null
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
     * Every way the monitor is currently failing to do its job, oldest first
     * — a camera unreachable past the grace period, a battery running down,
     * an alert that could not be shown. Empty is the healthy state. Written
     * by the service's [FailureLedger]; the viewer and the status line both
     * read it, so a failure is said the same way everywhere it is said.
     */
    val failures = MutableStateFlow<List<MonitoringFailure>>(emptyList())

    /**
     * The most recent failure to have cleared, kept so the ongoing
     * notification can say it happened: a camera that was gone for twenty
     * minutes at 3am is something to know about in the morning, even though
     * it is back.
     */
    val lastRecoveredFailure = MutableStateFlow<RecoveredFailure?>(null)

    /**
     * The user asked, from the ongoing notification, for Dozecam to go away
     * entirely. The receiver that hears it can stop the service but cannot
     * close a screen it does not hold, so it leaves the request here and every
     * activity finishes itself on reading it. In memory only, and reset by the
     * next viewer to open: an exit is a thing that happens once.
     */
    val exitRequested = MutableStateFlow(false)

    /**
     * Whether some screen owes the user an explanation of full-screen-intent
     * access before Android is asked for it.
     *
     * App-scoped rather than held by the [MonitoringStarter] that raised it,
     * because the screen that arms the monitor is not always a screen that
     * stays: onboarding arms and finishes itself in the same breath, and a
     * dialog owned by it would never be seen. The viewer picks it up instead,
     * which is the screen that is there afterwards.
     *
     * In memory only. This is a nudge, not a record — the durable statement is
     * the bedtime check, which goes on saying it for as long as it is true.
     */
    val explainFullScreenIntent = MutableStateFlow(false)

    /**
     * The cameras actually coming out of the speaker right now, or none.
     *
     * Deliberately separate from the ask — [app.dozecam.data.SoundMode.ALL_ALOUD]
     * in the stored settings — because the speaker can be lost to a call,
     * handed to the viewer, or a room's stream can be down. Everything that
     * *tells* the user something is audible — the notification's status line,
     * the offer to stop, the decision whether to light the screen for an alert
     * — reads this one, because it is the only one that is a fact.
     */
    val listeningCameraIds = MutableStateFlow<Set<String>>(emptySet())

    /**
     * Whether the viewer itself is making noise. Written by the activity while
     * it is on screen with its sound on and the speaker granted.
     *
     * Listen mode stands down while it is true. Both would otherwise play the
     * same nursery a second or two apart out of one speaker — and the viewer is
     * the better of the two to hear, because it is the room somebody is looking
     * at.
     */
    val viewerAudible = MutableStateFlow(false)

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
     * comes to the front unless there is nothing to listen to or it is already
     * running. There is no switch to have left off — monitoring ends only when
     * the app is exited, and the next open arms it again.
     *
     * Except while an exit is in flight. Settings re-arms the moment it sees
     * the service go, and an exit from the notification stops the service
     * before the screens have finished themselves — so without this gate the
     * monitor would be back before the task was gone. The next viewer to open
     * clears the request, which is what makes the next open arm again.
     */
    fun shouldAutoArm(enabledCameraCount: Int): Boolean =
        enabledCameraCount > 0 && !serviceRunning.value && !exitRequested.value

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
        failures.value = emptyList()
        lastRecoveredFailure.value = null
    }
}

package app.dozecam.ui.monitor

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.dozecam.R
import app.dozecam.audio.talkback.Talkback
import app.dozecam.audio.talkback.TalkbackAvailability
import app.dozecam.data.Camera
import app.dozecam.data.SoundMode
import app.dozecam.monitoring.FailureWording
import app.dozecam.monitoring.MonitoringFailure
import app.dozecam.monitoring.ReadinessFinding
import app.dozecam.monitoring.ReadinessState
import app.dozecam.monitoring.problems
import app.dozecam.monitoring.worstState
import app.dozecam.network.NetworkReach
import app.dozecam.player.CameraStreams
import kotlinx.coroutines.launch
import app.dozecam.player.StreamSource
import app.dozecam.player.VideoPlayerController
import app.dozecam.player.rememberCameraStreams
import app.dozecam.ui.components.ReadinessIcon
import app.dozecam.ui.components.readinessContainerColor
import app.dozecam.ui.components.readinessHeadline
import app.dozecam.ui.components.readinessSentence
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * How long an explanation stays up. Long enough to read a sentence, short
 * enough that it does not become part of the picture.
 */
private const val TALKBACK_EXPLANATION_MS = 4_000L

/**
 * Below this, a press cannot have carried speech.
 *
 * Every press opens with two hundred milliseconds of silence to cover the
 * camera's jitter buffer starting up, so a tap shorter than this sends that
 * silence, its tail, and nothing of the room in between. The camera makes a
 * faint noise and the speaker hears nothing said — which looks far more like a
 * broken feature than like a button used wrongly.
 */
private const val TALKBACK_MIN_PRESS_MS = 400L

/** Below this there is only room for one camera across; above it, two. */
private val TWO_COLUMN_BREAKPOINT = 600.dp

/** Above this, a third column still leaves each tile big enough to read. */
private val THREE_COLUMN_BREAKPOINT = 1000.dp

/**
 * How long the viewer gives monitoring to start before it says out loud that it
 * has not. Long enough to cover a cold start's permission check, settings read
 * and service launch; short enough that a start which genuinely failed is not
 * a secret for long.
 */
private const val ARMING_GRACE_MS = 3_000L

/** How far in from either side a touch still counts as starting at the edge. */
private val EDGE_SWIPE_EDGE_WIDTH = 32.dp

/** How far inward the finger must travel before the swipe means "leave". */
private val EDGE_SWIPE_TRIGGER_DISTANCE = 48.dp

/**
 * Leaves on a single swipe in from either side of the screen.
 *
 * The viewer hides the system bars, and while they are hidden Android spends
 * the first edge swipe bringing them out — only a second, identical swipe
 * reaches the back gesture. Sticky immersive passes that first swipe through
 * to the app as well, so the screen answers it here directly; the system's own
 * back still works through the [BackHandler] for whoever waits the bars out.
 *
 * [enabled] is consulted as each touch lands rather than keyed on, so a zoom
 * changing does not restart the detector mid-gesture.
 */
private fun Modifier.edgeSwipeToLeave(enabled: () -> Boolean, onLeave: () -> Unit): Modifier =
    pointerInput(onLeave) {
        val edge = EDGE_SWIPE_EDGE_WIDTH.toPx()
        val trigger = EDGE_SWIPE_TRIGGER_DISTANCE.toPx()
        awaitEachGesture {
            // Watched on the initial pass, before the tile underneath gets its
            // turn: the tap and zoom gestures down there claim and consume what
            // they recognise, and a watcher behind them would see a swipe with
            // every move already spent.
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (!enabled()) return@awaitEachGesture
            val fromLeft = down.position.x <= edge
            val fromRight = down.position.x >= size.width - edge
            if (!fromLeft && !fromRight) return@awaitEachGesture
            var travelled = 0f
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                // A second finger means a pinch: the zoom gesture owns it, and
                // a swipe that fired mid-pinch would snatch the room away from
                // someone leaning in for a closer look.
                if (event.changes.count { it.pressed } > 1) return@awaitEachGesture
                val change = event.changes.firstOrNull { it.id == down.id }
                if (change == null || !change.pressed) return@awaitEachGesture
                travelled += change.positionChange().x
                val inward = if (fromLeft) travelled else -travelled
                if (inward >= trigger) {
                    change.consume()
                    onLeave()
                    return@awaitEachGesture
                }
            }
        }
    }

/**
 * The viewer: every enabled camera, live, and nothing else. Arming the monitor
 * and everything about how a camera is set up live in settings, so the only
 * chrome here is the way to get there.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MonitorScreen(
    cameras: List<Camera>,
    sources: Map<String, StreamSource>,
    controllerFactory: (StreamSource) -> VideoPlayerController,
    /**
     * Whether this device is on a network its cameras could be on. The
     * sessions need only the online half of it; the viewer says the rest out
     * loud, because a phone that has wandered onto mobile data looks exactly
     * like a console that has died.
     */
    networkReach: NetworkReach,
    unmonitorable: List<Camera>,
    hasDisabledOnly: Boolean,
    onOpenSettings: () -> Unit,
    onOpenOnboarding: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Whether the monitor is listening. Monitoring runs for as long as the app
     * does, so this is only ever false on the way up — or when the start never
     * landed, which the viewer has to own up to: the grid looks exactly the
     * same either way, and a viewer that stayed silent about it would let a
     * dead monitor pass for a running one all night.
     */
    monitoringRunning: Boolean = false,
    /**
     * Every way the monitor is currently failing to do its job — a camera it
     * has lost for longer than the grace period, a battery running down, an
     * alert it could not show. Said here as well as in the notification and
     * the alarm, because this is the screen the alarm wakes into.
     */
    monitoringFailures: List<MonitoringFailure> = emptyList(),
    /** Whether starting it would achieve anything — some camera can be heard. */
    canMonitor: Boolean = false,
    /** Tries the start again, asking for whatever grant it refused on. */
    onStartMonitoring: () -> Unit = {},
    /** Injectable so tests need not wait out a real arming attempt. */
    armingGraceMs: Long = ARMING_GRACE_MS,
    /**
     * What the speaker does with the cameras. Off until asked for: this screen
     * comes up on its own when a room gets loud, sometimes over a lock screen
     * at 3am, and a viewer that starts talking the moment it appears is a
     * worse surprise than a silent one. Remembered, so it comes back the way
     * it was left — [SoundMode.ALL_ALOUD] included, which is the one the
     * monitoring service carries on with the screen off.
     */
    soundMode: SoundMode = SoundMode.OFF,
    onSoundModeChange: (SoundMode) -> Unit = {},
    /**
     * Whether a loud room reaches anyone. Here rather than only in settings
     * because this is where it is switched at bedtime — and where its being
     * off has to be visible, since everything else on this screen looks the
     * same whether or not the night is being watched.
     */
    alertsEnabled: Boolean = true,
    onAlertsEnabledChange: (Boolean) -> Unit = {},
    /** Ends the app, and the monitor with it. */
    onExit: () -> Unit = {},
    /**
     * Whether the viewer asks the system to hold the display awake. Shown as a
     * switch here because this is where the cost lands: the screen that would
     * otherwise go dark is the one these cameras are on.
     */
    keepScreenOn: Boolean = true,
    onKeepScreenOnChange: (Boolean) -> Unit = {},
    /**
     * Whether the system is letting the viewer make a sound this instant —
     * audio focus held, and nothing else borrowing the speaker.
     *
     * Kept apart from [soundMode] on purpose. The cameras follow this,
     * because playing on without focus is not ours to do; the button keeps
     * showing the setting, because a tap during a passing interruption would
     * otherwise set it to what it already was, and someone reaching to silence
     * the cameras mid-call would instead arm them for when the call ends.
     */
    soundGranted: Boolean = true,
    /**
     * What the monitor is hearing from each camera, keyed by camera id. A
     * camera with no entry gets no meter: the level is the monitor's report,
     * not the player's, so it exists exactly when monitoring does.
     */
    audioLevels: Map<String, Float> = emptyMap(),
    /** The level at which an alert would fire, marked on every meter. */
    audioThreshold: Float = 1f,
    /**
     * How long a press must last to have said anything. Injectable so a test
     * can make every press count, or none.
     */
    talkbackMinPressMs: Long = TALKBACK_MIN_PRESS_MS,
    /** Injectable so tests need not wait out a real turn. */
    soundRotationIntervalMs: Long = SOUND_ROTATION_INTERVAL_MS,
    /** Injectable so tests need not wait out a real minute. */
    inactivityTimeoutMs: Long = FULLSCREEN_INACTIVITY_TIMEOUT_MS,
    alertCameraId: String? = null,
    onAlertConsumed: () -> Unit = {},
    onFullscreenChange: (Boolean) -> Unit = {},
    /**
     * The alerted camera is no longer the only thing on screen — because it was
     * dismissed, or because it was gone before it could be shown. The caller
     * uses this to take back the lock-screen privilege the alert earned.
     */
    onAlertDismissed: () -> Unit = {},
    /**
     * Talking back to the camera on screen. Null where there is nothing to talk
     * through — a build without a console, or a test that does not care.
     */
    talkback: Talkback? = null,
    /**
     * Asks for the microphone. Called on the first press of a control that is
     * otherwise ready, never on the way in.
     */
    onRequestMicrophone: () -> Unit = {},
    /**
     * The bedtime check, for the compact card an empty viewer shows. Empty
     * where it has not been read yet, which renders as nothing at all rather
     * than as a clean bill of health.
     */
    readiness: List<ReadinessFinding> = emptyList(),
    /**
     * The failures worth interrupting for — a check that has just started
     * failing and has not been mentioned yet. Empty the rest of the time, which
     * is almost always: see [app.dozecam.monitoring.ReadinessPrompt].
     */
    readinessPrompt: List<ReadinessFinding> = emptyList(),
    onReadinessPromptOpen: () -> Unit = {},
    onReadinessPromptDismiss: () -> Unit = {},
    /** The screen came on for the bedtime test rather than for a room. */
    testAlertShowing: Boolean = false,
    onTestAlertDismissed: () -> Unit = {},
) {
    val streams = rememberCameraStreams(
        controllerFactory,
        networkOnline = networkReach != NetworkReach.OFFLINE,
    )
    val gridState = rememberLazyGridState()

    /**
     * Only tiles actually on screen take a turn. A camera scrolled out of the
     * grid has no player at all, so its turn would be ten seconds of silence
     * next to a badge nobody can see — the exact "is it broken or is the room
     * quiet?" doubt the badge exists to remove.
     */
    val visibleCameraIds by remember(cameras) {
        derivedStateOf {
            val onScreen = gridState.layoutInfo.visibleItemsInfo.mapNotNull { it.key as? String }
            cameras.map { it.id }.filter { it in onScreen }
        }
    }

    var fullscreenId by rememberSaveable { mutableStateOf<String?>(null) }
    var warmIds by remember { mutableStateOf<Set<String>>(emptySet()) }

    /**
     * Opens one camera, and keeps the rest of the grid connected behind it.
     *
     * The warm set is named here, in the same act that empties the grid, rather
     * than worked out afterwards: by the time the tiles are gone there is
     * nothing left to say which rooms were on screen, and a set named late
     * would arrive after their sessions had already been released.
     */
    fun promote(cameraId: String) {
        val previous = fullscreenId
        val warm = when (previous) {
            // Opening one camera from the grid: every other room on screen
            // keeps its session behind it.
            null -> visibleCameraIds.toSet()
            // Swapping the camera on screen, which is what an alert arriving
            // over an open one does. The room being left is one of the grid's
            // own and joins the rest — without this it would be the single
            // camera the grid had to reconnect on the way back.
            else -> warmIds + previous
        } - cameraId
        // A room switched off or deleted meanwhile is not coming back, and its
        // session is not worth holding open on the chance that it does.
        warmIds = warm.intersect(cameras.mapTo(mutableSetOf()) { it.id })
        streams.keepWarm(warmIds)
        fullscreenId = cameraId
    }
    // Two states, not one: "an alert asked for fullscreen" and "that fullscreen
    // is actually up". Collapsing them lets the first composition — where the
    // request is in but the tile is not yet showing — read as a dismissal.
    var alertPendingShow by rememberSaveable { mutableStateOf(false) }
    var alertShowing by rememberSaveable { mutableStateOf(false) }
    // Counts alerts that land on the camera already on screen. Nothing about
    // the screen changes in that case, so without a signal of its own the new
    // alert would inherit however little was left of the old one's wait.
    var samePlaceAlerts by remember { mutableIntStateOf(0) }
    val fullscreen = cameras.firstOrNull { it.id == fullscreenId }

    // A camera that went away (switched off, deleted) must not strand the
    // viewer on a blank fullscreen with nothing to show.
    LaunchedEffect(cameras, fullscreenId) {
        if (fullscreenId != null && fullscreen == null) fullscreenId = null
    }

    // Nor go on being kept warm. A room switched off in settings while another
    // camera is open is not one the grid will want back, and the warm set is
    // otherwise only worked out at the moment of opening — so without this its
    // session would be held until the viewer left the camera it is behind.
    LaunchedEffect(cameras, fullscreen?.id) {
        if (fullscreen == null) return@LaunchedEffect
        val present = cameras.mapTo(mutableSetOf()) { it.id }
        val kept = warmIds.intersect(present)
        if (kept != warmIds) {
            warmIds = kept
            streams.keepWarm(kept)
        }
    }

    // A wake alert names the camera that got loud; show that one, alone and
    // whole, because that is the entire reason the screen just came on.
    LaunchedEffect(alertCameraId, cameras) {
        val id = alertCameraId ?: return@LaunchedEffect
        if (cameras.any { it.id == id }) {
            if (fullscreenId == id) {
                // Already the camera on screen — locked while watching the very
                // room that then got loud. No id change is coming, so the
                // transition below will not fire; mark it here or leaving
                // fullscreen would never hand the lock screen back.
                alertShowing = true
                samePlaceAlerts++
            } else {
                promote(id)
                alertPendingShow = true
            }
        } else {
            // Named a camera that is no longer here: there is nothing to show,
            // so the alert ends now rather than leaving the grid up.
            onAlertDismissed()
        }
        onAlertConsumed()
    }

    // Keyed on which camera is fullscreen, not merely whether one is: an alert
    // arriving while another camera is already fullscreen swaps the id without
    // changing the boolean, and the alert would never be marked as showing.
    LaunchedEffect(fullscreen?.id) {
        val showing = fullscreen != null
        onFullscreenChange(showing)
        // The grid is back and its tiles have claimed their cameras again —
        // effects run after the composition that re-added them — so nothing
        // needs keeping warm on their behalf any more. Cameras the grid did not
        // ask for this time round are released here rather than left running.
        if (!showing) {
            warmIds = emptySet()
            streams.keepWarm(emptySet())
        }
        when {
            showing && alertPendingShow -> {
                alertPendingShow = false
                alertShowing = true
            }
            // Leaving the single camera an alert opened ends the alert with it.
            // The grid must never become reachable over the keyguard just
            // because Back was pressed on the camera they were woken for.
            !showing && alertShowing -> {
                alertShowing = false
                onAlertDismissed()
            }
        }
    }

    // Leaving one camera by hand, now that a tap on it means "stay". Nothing
    // else is left to go back to from here, so the gesture is free.
    BackHandler(enabled = fullscreen != null) { fullscreenId = null }

    val audible = soundMode != SoundMode.OFF && soundGranted

    // Rotation is a grid problem: one camera on screen alone already has the
    // user's whole attention, so it simply keeps the sound for as long as it
    // is up. And it is only one of the two ways the grid can be audible: with
    // every camera aloud there is no turn to take.
    val rotatingCameraId = rememberAudibleCameraId(
        cameraIds = visibleCameraIds,
        enabled = audible && soundMode == SoundMode.ROTATING && fullscreen == null,
        intervalMs = soundRotationIntervalMs,
    )
    val audibleCameraIds: Set<String> = when {
        !audible || fullscreen != null -> emptySet()
        // Every tile on screen, and only those: a camera scrolled out of the
        // grid has no player, and a mix that claimed it would be claiming a
        // room nobody can hear.
        soundMode == SoundMode.ALL_ALOUD -> visibleCameraIds.toSet()
        else -> setOfNotNull(rotatingCameraId)
    }

    // The toggles are icons that step between states, and no icon says which
    // state a tap just put them in — least of all the screen one, whose
    // "awake" and "asleep" glyphs read alike at a glance. So every press says
    // in words what it did.
    val snackbarHostState = remember { SnackbarHostState() }
    val announce = rememberAnnouncer(snackbarHostState)

    // Turning sound on is a request, not an outcome: the caller still has to
    // win the speaker, and a refusal quietly puts the setting back to off. So
    // a confirmation waits for the state it promises — cameras actually
    // audible in the mode asked for — and a request that came back reverted
    // instead says why nothing changed. Off needs no such caution; letting go
    // of the speaker cannot fail.
    var soundModeRequested by remember { mutableStateOf<SoundMode?>(null) }
    val soundModeAnnounced = { mode: SoundMode ->
        if (mode == SoundMode.OFF) {
            soundModeRequested = null
            announce(R.string.viewer_sound_off_confirmed)
        } else {
            soundModeRequested = mode
        }
        onSoundModeChange(mode)
    }
    LaunchedEffect(audible, soundMode) {
        val requested = soundModeRequested ?: return@LaunchedEffect
        if (audible && soundMode == requested) {
            soundModeRequested = null
            when (requested) {
                SoundMode.ROTATING -> announce(R.string.viewer_sound_on_confirmed)
                // Naming what will keep playing is the point: with the screen
                // about to go off, this is the last chance to say what the
                // phone will be broadcasting — and what it will do to an alert
                // once it is. So only the rooms the monitor is actually hearing
                // right now — a live stream with audio decoded on it, which is
                // exactly what the service turns up when the screen goes off —
                // are promised for the dark. A camera it cannot listen to, one
                // still reconnecting, or a monitor that never started plays on
                // this screen and no further, and the confirmation says so.
                SoundMode.ALL_ALOUD -> {
                    val carried = cameras.filter { it.id in audioLevels }
                    when {
                        carried.size == 1 ->
                            announce(R.string.viewer_listen_on_confirmed, carried.single().name)
                        carried.size > 1 ->
                            announce(R.string.viewer_listen_on_confirmed_rooms, carried.size)
                        cameras.size == 1 ->
                            announce(R.string.viewer_all_aloud_on_screen, cameras.single().name)
                        else -> announce(R.string.viewer_all_aloud_on_screen_rooms, cameras.size)
                    }
                }
                SoundMode.OFF -> Unit
            }
        } else if (soundMode == SoundMode.OFF) {
            // The setting went back off while an "on" was still unconfirmed:
            // the request was refused, not fulfilled.
            soundModeRequested = null
            announce(R.string.viewer_sound_refused)
        }
    }
    val alertsToggleAnnounced = { enabled: Boolean ->
        announce(
            if (enabled) R.string.viewer_alerts_on_confirmed
            else R.string.viewer_alerts_off_confirmed,
        )
        onAlertsEnabledChange(enabled)
    }

    if (fullscreen != null) {
        // Watching one room is a detour the viewer takes itself back from. The
        // alerted camera is no exception: the alert bought a look, and once
        // nobody is looking it ends like any other — handing back the lock
        // screen on the way out, through the same transition Back uses.
        val countdown = rememberInactivityCountdown(
            cameraId = fullscreen.id,
            timeoutMs = inactivityTimeoutMs,
            // A room that got loud again while its own camera was up is the
            // newest reason there is to be looking at it, and nothing on screen
            // changed to say so — without this, an alert that woke the phone at
            // 3am could hand the lock screen back seconds later.
            restartOn = samePlaceAlerts,
            onExpired = { fullscreenId = null },
        )
        // Keyed on the camera: an alert swapping which room fills the screen
        // must not carry the old room's framing onto the new one — the first
        // look at the room that got loud has to be the whole room.
        val zoom = remember(fullscreen.id) { PinchZoomState() }
        // Talk-back is a single-camera feature: the grid rotates its sound
        // between rooms, and a microphone aimed at a tile nobody is listening to
        // has no meaning. So the console is only ever asked about the camera
        // actually on screen alone.
        DisposableEffect(talkback, fullscreen.id) {
            talkback?.watch(fullscreen)
            // Leaving the camera ends the conversation with it. Without this a
            // held button that survived a Back press would keep a microphone
            // open against a room nobody is looking at.
            onDispose { talkback?.watch(null) }
        }
        val talkbackAvailability by (talkback?.availability
            ?: remember { MutableStateFlow(TalkbackAvailability.Resolving) }).collectAsState()
        val talking by (talkback?.talking
            ?: remember { MutableStateFlow(false) }).collectAsState()
        var explaining by remember(fullscreen.id) { mutableStateOf(false) }

        // A press that could not open a microphone, an encoder or a socket ends
        // with nothing said and nothing on screen to say so. The control cannot
        // carry it — by the time this arrives the button is no longer held —
        // so it goes where the other one-off outcomes go.
        LaunchedEffect(talkback) {
            talkback?.failures?.collect { announce(R.string.talkback_failed) }
        }

        // Shown on request, and taken away again: an explanation that stayed
        // would become part of the picture.
        LaunchedEffect(explaining, talkbackAvailability) {
            if (explaining) {
                delay(TALKBACK_EXPLANATION_MS)
                explaining = false
            }
        }

        Box(
            modifier = modifier
                .fillMaxSize()
                // The immersive viewer swallows the system's first back swipe
                // (it only brings the bars out), so the screen answers an edge
                // swipe itself rather than making the gesture be done twice.
                .edgeSwipeToLeave(
                    // A zoomed picture pans with one finger, and a pan that
                    // began near the edge is aiming the view, not leaving it.
                    enabled = { zoom.scale <= 1f },
                ) { fullscreenId = null }
                // Presence is a hand on this screen, not a hand on the picture.
                // The chrome sits beside the tile rather than inside it, and
                // each pill is a surface that takes its own touches — so a tap
                // on the countdown's own sentence, the one thing a hand reaches
                // for while reading how long is left, would otherwise reach
                // nothing and let the grid take the room back anyway.
                //
                // Watched rather than handled: this claims no gesture and
                // consumes nothing, so the tile's tap, the pinch, the edge
                // swipe and every button underneath behave exactly as before.
                .pointerInput(countdown) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        countdown.reset()
                    }
                },
        ) {
            CameraTile(
                camera = fullscreen,
                source = sources[fullscreen.id],
                streams = streams,
                // The picture and nothing on it: this screen draws the
                // camera's state and meter itself, in a line with its own
                // buttons, so nothing the tile says can end up under one.
                chrome = false,
                // The one camera on screen alone is the one worth hearing; this
                // is the "listen to the room" case the viewer exists for, so it
                // needs nothing beyond sound being switched on.
                //
                // Except while somebody is talking. The phone's speaker and the
                // camera's microphone are in the same room, so playing the room
                // back during a press closes the loop that half duplex exists to
                // open.
                audible = audible && !talking,
                // A tap now means "I am still here" rather than "take me back":
                // leaving is Back, and a countdown with no way to answer it
                // would cap every look at one room to a flat minute.
                onClick = countdown::reset,
                zoom = zoom,
                onZoomGesture = countdown::reset,
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("fullscreen-tile"),
            )
            // Everything along the top is one column: the draining bar at the
            // very edge, then one row of chrome at one height — the way back,
            // the camera's state beside it, and at the far end the controls
            // a single camera keeps — and under that row whatever the mic
            // button has to explain. Stacked rather than each picking its own
            // corner, so nothing here can land on anything else.
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .safeDrawingPadding(),
            ) {
                InactivityBar(countdown = countdown)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(OverlayChrome.Gap),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(OverlayChrome.Margin),
                ) {
                    // The start of the row takes whatever the buttons at the
                    // end leave, so a long status — reconnecting, with the
                    // age of the last frame — is cut to fit rather than
                    // pushing the sound button off the screen.
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(OverlayChrome.Gap),
                        modifier = Modifier.weight(1f),
                    ) {
                        // The visible way back, in the corner back buttons
                        // live in. Back and the edge swipe do the same, but
                        // nothing on a screen that is all picture says so; the
                        // alerted camera keeps it too, because the transition
                        // below is the same one that hands the lock screen
                        // back.
                        FilledTonalIconButton(
                            onClick = { fullscreenId = null },
                            shapes = IconButtonDefaults.shapes(),
                            modifier = Modifier.testTag("back-to-grid"),
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.viewer_all_cameras),
                            )
                        }
                        // Read from the session, as the tile would: a camera
                        // opened from the grid is already connected, and a
                        // pill that reset to "Connecting" would report a
                        // reconnection that is not happening.
                        val stream = streams[fullscreen.id]
                        val connection by (stream?.connection ?: connecting).collectAsState()
                        val lastFrameAtMs by (stream?.lastFrameAtMs ?: noFrameYet).collectAsState()
                        StatusOverlay(
                            state = connection,
                            lastFrameAtMs = lastFrameAtMs,
                            height = OverlayChrome.ControlHeight,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                    // The controls a single camera keeps. Without the second,
                    // sound could be switched on solely from the grid —
                    // including for a camera an alert opened, which is
                    // precisely when the user wants to listen.
                    if (talkback != null) {
                        TalkbackButton(
                            availability = talkbackAvailability,
                            talking = talking,
                            // Holding a button is as much a sign of someone
                            // being there as touching the picture is — and a
                            // countdown that expired mid-sentence would take
                            // the room away while somebody was still speaking
                            // to it.
                            onPress = {
                                countdown.reset()
                                if (talkbackAvailability == TalkbackAvailability.Ready) {
                                    talkback.press()
                                }
                            },
                            // No snackbar: nobody let go, so there is nothing
                            // to tell them about how they were holding it.
                            onCancel = talkback::release,
                            onRelease = { heldMillis ->
                                talkback.release()
                                // A tap is not a mistake worth blocking — the
                                // press still runs, and its silence is
                                // harmless — but it is a mistake worth naming,
                                // because the camera does make a small noise
                                // and the room hears no words.
                                if (heldMillis < talkbackMinPressMs) {
                                    announce(R.string.talkback_too_short)
                                }
                            },
                            onExplain = {
                                countdown.reset()
                                explaining = true
                                if (talkbackAvailability == TalkbackAvailability.NeedsPermission) {
                                    onRequestMicrophone()
                                }
                            },
                        )
                    }
                    SoundModeButton(
                        soundMode = soundMode,
                        // Reaching for the sound is as much a sign of someone
                        // being there as touching the picture is.
                        onSoundModeChange = {
                            countdown.reset()
                            soundModeAnnounced(it)
                        },
                    )
                }
                if (explaining) {
                    TalkbackNotice(
                        availability = talkbackAvailability,
                        modifier = Modifier
                            .align(Alignment.End)
                            .padding(horizontal = OverlayChrome.Margin),
                    )
                }
            }
            // The bottom column: the room's level, the countdown, and whatever
            // a button press just said — the same gap between each as the top
            // row keeps between its pieces.
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .safeDrawingPadding()
                    .padding(OverlayChrome.Margin),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(OverlayChrome.Gap),
            ) {
                // Here with the countdown rather than on the tile: two
                // overlays that each pick their own corner meet in the middle
                // of a narrow phone, and the notice would cover the meter for
                // the whole minute it counts.
                val level = audioLevels[fullscreen.id]
                if (level != null) {
                    AudioMeterPill(
                        cameraName = fullscreen.name,
                        level = level,
                        threshold = audioThreshold,
                        height = OverlayChrome.ControlHeight,
                    )
                }
                InactivityNotice(countdown = countdown, onStay = countdown::reset)
                ToggleSnackbarHost(snackbarHostState)
            }
        }
        return
    }

    // Exiting is the one control here that can quietly undo the whole point of
    // the app, and the viewer is a screen people prop up and brush past at
    // night. So the button opens the question and this answers it.
    var confirmingExit by rememberSaveable { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxSize()
            // The grid sits on black so the letterboxing around a picture is
            // part of the picture; the empty state is a page, and sits on one.
            .background(if (cameras.isEmpty()) MaterialTheme.colorScheme.surface else Color.Black),
    ) {
        Column(modifier = Modifier.safeDrawingPadding()) {
            // A strip of its own above the cameras rather than floated over
            // the first of them. It costs a row of buttons' worth of height,
            // and buys every tile its whole picture: floated, the row sat on
            // the first camera's top edge, across the status pill of whatever
            // tile was under it — a button over "Reconnecting" lets a frozen
            // frame pass for a live one.
            //
            // A flow rather than a row: on the narrowest phones the badge plus
            // five buttons is more than one line holds, and a row would
            // squeeze whatever came last instead of letting it step down a
            // line.
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(OverlayChrome.Margin),
                horizontalArrangement = Arrangement.spacedBy(OverlayChrome.Gap, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(OverlayChrome.Gap),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                NotMonitoringBadge(
                    running = monitoringRunning,
                    canMonitor = canMonitor,
                    armingGraceMs = armingGraceMs,
                    onStart = onStartMonitoring,
                )
                // The way out, first: it is the one control that ends the
                // night rather than adjusting it, and it should not sit
                // between two buttons that get reached for in the dark.
                ExitButton(onClick = { confirmingExit = true })
                // Nothing to listen to yet: an empty viewer offers setup, not
                // a switch for sound that has no camera to come from, nor for
                // alerts no room can raise — nor one for a display it never
                // holds awake in the first place.
                if (cameras.isNotEmpty()) {
                    SoundModeButton(
                        soundMode = soundMode,
                        onSoundModeChange = soundModeAnnounced,
                    )
                    AlertsToggle(
                        alertsEnabled = alertsEnabled,
                        onAlertsEnabledChange = alertsToggleAnnounced,
                    )
                    KeepScreenToggle(
                        keepScreenOn = keepScreenOn,
                        onKeepScreenOnChange = { enabled ->
                            announce(
                                if (enabled) R.string.viewer_keep_screen_on_confirmed
                                else R.string.viewer_keep_screen_off_confirmed,
                            )
                            onKeepScreenOnChange(enabled)
                        },
                    )
                }
                FilledTonalIconButton(
                    onClick = onOpenSettings,
                    shapes = IconButtonDefaults.shapes(),
                    modifier = Modifier.testTag("open-settings"),
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = stringResource(R.string.settings),
                    )
                }
            }
            if (cameras.isEmpty()) {
                EmptyState(
                    hasDisabledOnly = hasDisabledOnly,
                    readiness = readiness,
                    onOpenSettings = onOpenSettings,
                    onOpenOnboarding = onOpenOnboarding,
                )
            } else {
                if (unmonitorable.isNotEmpty()) {
                    UnmonitorableNotice(
                        cameras = unmonitorable,
                        modifier = Modifier.padding(
                            start = OverlayChrome.Margin,
                            end = OverlayChrome.Margin,
                            bottom = OverlayChrome.Margin,
                        ),
                    )
                }
                CameraLayout(
                    cameras = cameras,
                    sources = sources,
                    streams = streams,
                    audibleCameraIds = audibleCameraIds,
                    audioLevels = audioLevels,
                    audioThreshold = audioThreshold,
                    gridState = gridState,
                    onFullscreen = ::promote,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        // Bottom-aligned, unlike the notices above the grid: the top of this
        // screen is already spoken for by the controls, and a message about the
        // whole device belongs where nothing is competing with it.
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .safeDrawingPadding()
                .padding(OverlayChrome.Margin),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(OverlayChrome.Gap),
        ) {
            NetworkNotice(reach = networkReach)
            MonitoringFailureNotice(failures = monitoringFailures)
            ToggleSnackbarHost(snackbarHostState)
        }

        // One question at a time, and in this order. The exit question is the
        // one a person asked for a second ago and is waiting on, so it keeps
        // the screen; the other two arrive on their own schedule and can wait
        // for an answer that is already in flight. Stacked instead, they would
        // sit on top of each other with the first touch going to whichever
        // window happened to be uppermost.
        if (confirmingExit) {
            ExitDialog(
                onConfirm = {
                    confirmingExit = false
                    onExit()
                },
                onDismiss = { confirmingExit = false },
            )
        } else if (testAlertShowing) {
            // The test says what just woke the screen; the prompt says what
            // will not wake it later. The one that has already happened first.
            TestAlertDialog(onDismiss = onTestAlertDismissed)
        } else if (readinessPrompt.isNotEmpty()) {
            ReadinessPromptDialog(
                problems = readinessPrompt,
                onOpen = onReadinessPromptOpen,
                onDismiss = onReadinessPromptDismiss,
            )
        }
    }
}

/**
 * The one thing about monitoring the viewer still has to say: that it is not
 * happening. Monitoring runs for as long as the app does, so there is no
 * switch and nothing to announce while it is running — but a start that never
 * landed must not stay a secret, because the grid looks exactly the same
 * either way, and that is the picture someone would take as proof.
 *
 * A tap tries again, asking for whatever grant the start refused on.
 */
@Composable
private fun NotMonitoringBadge(
    running: Boolean,
    canMonitor: Boolean,
    armingGraceMs: Long,
    onStart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Nothing listenable and nothing running: the empty state and the
    // unmonitorable notice already explain why, and an offer to start what
    // cannot start would only be a third thing to read.
    if (running || !canMonitor) return

    // A viewer that has just opened is almost always a monitor part-way
    // through starting — arming is a permission check, a settings read and a
    // service launch behind the first frame. Saying "not monitoring" into that
    // gap would make the badge cry wolf on every single launch, and a warning
    // that is usually wrong is not read at all by the night it is right. So it
    // waits, and speaks up only if the start never lands.
    var armingFailed by remember { mutableStateOf(false) }
    LaunchedEffect(armingGraceMs) {
        armingFailed = false
        delay(armingGraceMs)
        armingFailed = true
    }
    if (!armingFailed) return

    Button(
        onClick = onStart,
        shapes = ButtonDefaults.shapes(),
        // Not an idle state to be styled quietly: a baby monitor that is not
        // monitoring is the one thing on this screen worth alarm.
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
        contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
        modifier = modifier.testTag("monitoring-badge"),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_do_not_disturb),
            // The label says what is happening; the action a tap performs is
            // the part a screen reader would otherwise have to guess.
            contentDescription = stringResource(R.string.viewer_start_monitoring),
            modifier = Modifier.size(ButtonDefaults.IconSize),
        )
        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
        Text(stringResource(R.string.viewer_monitoring_off))
    }
}

/**
 * The way to end Dozecam, and the monitor with it. Monitoring has no switch of
 * its own — it runs for as long as the app does — so this is the only control
 * that can quietly undo the whole point of the app, and it asks first.
 */
@Composable
private fun ExitButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    FilledTonalIconButton(
        onClick = onClick,
        shapes = IconButtonDefaults.shapes(),
        modifier = modifier.testTag("exit"),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_power),
            contentDescription = stringResource(R.string.viewer_exit),
        )
    }
}

/**
 * Exiting is deliberate or it is a mistake — there is no third case. The button
 * sits over live video on a screen that stays on all night, and a stray tap
 * that ended the monitor would at least take the cameras with it, but the
 * question is still worth asking of someone reaching past it in the dark.
 */
@Composable
private fun ExitDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.viewer_exit_title)) },
        text = { Text(stringResource(R.string.viewer_exit_body)) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.testTag("confirm-exit"),
            ) {
                Text(stringResource(R.string.viewer_exit_confirm))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("keep-running"),
            ) {
                Text(stringResource(R.string.viewer_keep_running))
            }
        },
    )
}

/**
 * One button for the whole speaker rather than one per camera, stepping
 * through off, one room at a time, and every room at once. Which room is
 * audible is already answered — by opening a camera, by whose turn it is, or
 * by every tile wearing the badge — so the only question left is what the
 * phone should be doing with its speaker.
 *
 * The third step is listen mode: every camera aloud on screen, and the same
 * mix carried on by the monitoring service once the screen is off. One button
 * rather than two because it is one speaker, and the old pair — the viewer's
 * sound and the house's — fought over it.
 */
@Composable
private fun SoundModeButton(
    soundMode: SoundMode,
    onSoundModeChange: (SoundMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val next = when (soundMode) {
        SoundMode.OFF -> SoundMode.ROTATING
        SoundMode.ROTATING -> SoundMode.ALL_ALOUD
        SoundMode.ALL_ALOUD -> SoundMode.OFF
    }
    FilledTonalIconButton(
        onClick = { onSoundModeChange(next) },
        shapes = IconButtonDefaults.shapes(),
        modifier = modifier.testTag("toggle-sound"),
    ) {
        Icon(
            painter = painterResource(
                when (soundMode) {
                    SoundMode.OFF -> R.drawable.ic_volume_off
                    SoundMode.ROTATING -> R.drawable.ic_volume_up
                    SoundMode.ALL_ALOUD -> R.drawable.ic_broadcast
                },
            ),
            // The action a tap performs, as the other toggles say theirs.
            contentDescription = stringResource(
                when (next) {
                    SoundMode.ROTATING -> R.string.viewer_sound_rotate
                    SoundMode.ALL_ALOUD -> R.string.viewer_listen_on
                    SoundMode.OFF -> R.string.viewer_sound_off
                },
            ),
        )
    }
}

/**
 * Whether a loud room reaches anyone. Off, the button wears the error colours
 * for as long as it stays off: with monitoring always on, this is the one
 * state in which the night is not being watched, and the badge that used to
 * say so is gone.
 */
@Composable
private fun AlertsToggle(
    alertsEnabled: Boolean,
    onAlertsEnabledChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    FilledTonalIconButton(
        onClick = { onAlertsEnabledChange(!alertsEnabled) },
        shapes = IconButtonDefaults.shapes(),
        colors = if (alertsEnabled) {
            IconButtonDefaults.filledTonalIconButtonColors()
        } else {
            IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            )
        },
        modifier = modifier.testTag("toggle-alerts"),
    ) {
        Icon(
            painter = painterResource(
                if (alertsEnabled) R.drawable.ic_notifications_active
                else R.drawable.ic_notifications_off,
            ),
            contentDescription = stringResource(
                if (alertsEnabled) R.string.viewer_alerts_off else R.string.viewer_alerts_on,
            ),
        )
    }
}

/**
 * Whether a propped-up monitor may go dark at the system timeout. Also in
 * settings with room for an explanation; it lives here too because the moment
 * the choice matters — the phone being set down for the night, or picked up
 * for a glance — is a moment spent on this screen.
 */
@Composable
private fun KeepScreenToggle(
    keepScreenOn: Boolean,
    onKeepScreenOnChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    FilledTonalIconButton(
        onClick = { onKeepScreenOnChange(!keepScreenOn) },
        shapes = IconButtonDefaults.shapes(),
        modifier = modifier.testTag("toggle-keep-screen"),
    ) {
        Icon(
            painter = painterResource(
                if (keepScreenOn) R.drawable.ic_aod else R.drawable.ic_screen_lock_portrait,
            ),
            contentDescription = stringResource(
                if (keepScreenOn) R.string.viewer_keep_screen_off else R.string.viewer_keep_screen_on,
            ),
        )
    }
}

/**
 * A short, replaceable confirmation for a button press. Each new press cuts
 * off whatever the last one was saying: someone tapping twice to undo a slip
 * should read the outcome, not a queue of everything that happened on the way.
 */
private class Announcer(
    private val context: Context,
    private val show: (String) -> Unit,
) {
    /** [args] for the messages that have to name the room they are about. */
    operator fun invoke(@StringRes message: Int, vararg args: Any) {
        show(context.getString(message, *args))
    }
}

@Composable
private fun rememberAnnouncer(hostState: SnackbarHostState): Announcer {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    return remember(hostState, context) {
        Announcer(context) { message ->
            hostState.currentSnackbarData?.dismiss()
            scope.launch {
                hostState.showSnackbar(message = message, duration = SnackbarDuration.Short)
            }
        }
    }
}

@Composable
private fun ToggleSnackbarHost(hostState: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(
        hostState = hostState,
        modifier = modifier.testTag("toggle-feedback"),
    )
}

/**
 * One column on a phone, more where there is room. Always a scrolling list of
 * whole tiles rather than one camera at a time: seeing every room at once is
 * the point, and a swipe that hides the others is exactly wrong at 3am.
 *
 * The threshold is the window's own width rather than the device's, so a phone
 * in landscape and a tablet in split screen each get what their space supports.
 */
@Composable
private fun CameraLayout(
    cameras: List<Camera>,
    sources: Map<String, StreamSource>,
    streams: CameraStreams,
    audibleCameraIds: Set<String>,
    audioLevels: Map<String, Float>,
    audioThreshold: Float,
    gridState: LazyGridState,
    onFullscreen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier) {
        val columns = when {
            maxWidth >= THREE_COLUMN_BREAKPOINT -> 3
            maxWidth >= TWO_COLUMN_BREAKPOINT -> 2
            else -> 1
        }.coerceAtMost(cameras.size)
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(columns),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier
                .fillMaxSize()
                .testTag("camera-list-$columns"),
        ) {
            items(cameras, key = { it.id }) { camera ->
                val audible = camera.id in audibleCameraIds
                // Tiles keep a 16:9 box; the picture letterboxes inside it, so
                // a 4:3 camera still shows its whole frame.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f),
                ) {
                    CameraTile(
                        camera = camera,
                        source = sources[camera.id],
                        streams = streams,
                        audible = audible,
                        audioLevel = audioLevels[camera.id],
                        audioThreshold = audioThreshold,
                        onClick = { onFullscreen(camera.id) },
                        modifier = Modifier.fillMaxSize(),
                    )
                    // Drawn over the tile rather than around it: the tile paints
                    // its own black background, which would cover an outline
                    // that arrived earlier in the chain. Takes no pointer input,
                    // so the tap still reaches the camera underneath.
                    if (audible) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .border(2.dp, MaterialTheme.colorScheme.primary),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyState(
    hasDisabledOnly: Boolean,
    readiness: List<ReadinessFinding>,
    onOpenSettings: () -> Unit,
    onOpenOnboarding: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(
                if (hasDisabledOnly) R.string.viewer_all_disabled else R.string.viewer_no_cameras,
            ),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (hasDisabledOnly) {
            Button(
                onClick = onOpenSettings,
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier.testTag("empty-open-settings"),
            ) {
                Text(stringResource(R.string.settings))
            }
        } else {
            Button(
                onClick = onOpenOnboarding,
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier.testTag("empty-open-onboarding"),
            ) {
                Text(stringResource(R.string.connect_to_protect))
            }
            TextButton(onClick = onOpenSettings) {
                Text(stringResource(R.string.viewer_add_manually))
            }
        }
        // The compact bedtime check. Here rather than over the grid because a
        // standing warning above live cameras every night is a warning nobody
        // reads by the night it is right — and because this page is where
        // somebody is already being asked to go and set the app up.
        ReadinessCompact(findings = readiness, onOpen = onOpenSettings)
    }
}

/**
 * The whole checklist in one line, for a screen that has no room for eleven.
 *
 * Silent when everything passes: an empty viewer offering to set up cameras
 * does not also need a green tick, and a card that appears only when something
 * is wrong is a card that means something when it appears.
 */
@Composable
private fun ReadinessCompact(
    findings: List<ReadinessFinding>,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val problems = findings.problems()
    if (problems.isEmpty()) return
    val state = findings.worstState()
    Button(
        onClick = onOpen,
        shapes = ButtonDefaults.shapes(),
        colors = ButtonDefaults.buttonColors(
            containerColor = readinessContainerColor(state),
            contentColor = if (state == ReadinessState.FAIL) {
                MaterialTheme.colorScheme.onErrorContainer
            } else {
                MaterialTheme.colorScheme.onTertiaryContainer
            },
        ),
        contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
        modifier = modifier.testTag("readiness-compact"),
    ) {
        ReadinessIcon(state, modifier = Modifier.size(ButtonDefaults.IconSize))
        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
        Text(readinessHeadline(findings))
    }
}

/**
 * What just woke the screen, when the answer is "nothing".
 *
 * The test alert is deliberately indistinguishable from a real one while it is
 * happening — that is the entire point of it — so this is the moment the
 * pretence has to end, in the first place anyone looks.
 */
@Composable
private fun TestAlertDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.viewer_test_alert_title)) },
        text = { Text(stringResource(R.string.viewer_test_alert_body)) },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("test-alert-dismiss"),
            ) {
                Text(stringResource(R.string.viewer_test_alert_dismiss))
            }
        },
        modifier = Modifier.testTag("test-alert-dialog"),
    )
}

/**
 * The one interruption a failing bedtime check is allowed outside settings.
 *
 * Shown once, when something that was working stops working — never as a
 * standing banner over the cameras, which is how a warning becomes wallpaper.
 * "Later" is a real answer: the card in settings goes on saying it for as long
 * as it is true, and the next time this same thing breaks it will speak up
 * again.
 */
@Composable
private fun ReadinessPromptDialog(
    problems: List<ReadinessFinding>,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.readiness_prompt_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                problems.forEach { Text(readinessSentence(it)) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onOpen,
                modifier = Modifier.testTag("readiness-prompt-open"),
            ) {
                Text(stringResource(R.string.readiness_prompt_open))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("readiness-prompt-dismiss"),
            ) {
                Text(stringResource(R.string.readiness_prompt_dismiss))
            }
        },
        modifier = Modifier.testTag("readiness-prompt"),
    )
}

/**
 * Says out loud that this device is not where its cameras are.
 *
 * Dozecam only ever talks to a console on the house's own network, so a phone
 * carried out to the car is not a monitor any more — but nothing on screen
 * would say so. The tiles would sit at OFFLINE looking exactly as they do when
 * a console has died or a camera has been unplugged, and the one explanation
 * that costs nothing to check is the one the viewer is in a position to give.
 *
 * Stated rather than toasted, and it stays for as long as it is true. This is a
 * screen people prop up and walk away from; a message that had already faded by
 * the time anyone looked would be no message at all.
 */
@Composable
private fun NetworkNotice(reach: NetworkReach, modifier: Modifier = Modifier) {
    val message = when (reach) {
        NetworkReach.LOCAL -> return
        NetworkReach.OFFLINE -> R.string.viewer_no_network
        NetworkReach.MOBILE_DATA -> R.string.viewer_off_wifi
    }
    OverlayNotice(
        text = stringResource(message),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = modifier
            // Worth interrupting for, and only ever a sentence — nothing like
            // the countdown next door, which changes every second.
            .semantics { liveRegion = LiveRegionMode.Polite }
            .testTag("network-notice"),
    )
}

/**
 * The monitor owning up: it cannot currently do its job, and this is why. One
 * notice per failure, in the colours the other coverage notices use, because
 * it is the same kind of fact — and the one the alarm woke someone to read.
 */
@Composable
private fun MonitoringFailureNotice(
    failures: List<MonitoringFailure>,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    failures.forEach { failure ->
        OverlayNotice(
            text = stringResource(
                R.string.viewer_failure_since,
                FailureWording.title(context, failure.reason),
                FailureWording.time(context, failure.sinceMs),
            ),
            color = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
            modifier = modifier
                .semantics { liveRegion = LiveRegionMode.Polite }
                .testTag("failure-notice"),
        )
    }
}

/**
 * Enabled but not listenable: say so rather than imply full coverage. The same
 * notice as the network one, in the same colours — two facts about coverage,
 * said the same way.
 */
@Composable
private fun UnmonitorableNotice(cameras: List<Camera>, modifier: Modifier = Modifier) {
    OverlayNotice(
        text = pluralStringResource(
            R.plurals.viewer_unmonitorable,
            cameras.size,
            cameras.size,
            cameras.joinToString { it.name },
        ),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = modifier
            .fillMaxWidth()
            .testTag("unmonitorable-notice"),
    )
}

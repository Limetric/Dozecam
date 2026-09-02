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
import androidx.compose.material3.Surface
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
import app.dozecam.network.NetworkReach
import app.dozecam.player.CameraStreams
import kotlinx.coroutines.launch
import app.dozecam.player.StreamSource
import app.dozecam.player.VideoPlayerController
import app.dozecam.player.rememberCameraStreams
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
 * How long the viewer gives listen mode's speaker to arrive before it says out
 * loud that it did not.
 *
 * The switch lives in the monitoring service's state and reaches this screen
 * through a flow, so "the request has not landed yet" and "the request was
 * refused" look identical for a frame or two. Long enough to cover that hop,
 * short enough that a phone which stayed silent does not stay unexplained.
 */
private const val LISTEN_REFUSAL_GRACE_MS = 750L

/**
 * How long the viewer gives monitoring to start before it says out loud that it
 * has not. Long enough to cover a cold start's permission check, settings read
 * and service launch; short enough that a start which genuinely failed is not
 * a secret for long.
 */
private const val ARMING_GRACE_MS = 3_000L

/**
 * How far the fullscreen status pill steps aside for the back button: the
 * button's 12dp margin plus its 40dp container, so the pill's own 12dp margin
 * leaves an even gap between them.
 */
private val BACK_BUTTON_STATUS_INSET = 52.dp

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
     * Whether the monitor is listening. Shown rather than assumed: the grid
     * looks exactly the same either way, so a viewer that stayed silent about
     * it would let a stopped monitor pass for a running one all night.
     */
    monitoringRunning: Boolean = false,
    /** Whether starting it would achieve anything — some camera can be heard. */
    canMonitor: Boolean = false,
    /**
     * Whether it is off because the user said so, as opposed to not having
     * started yet. The badge needs the difference: one is a fact to state at
     * once, the other is a race it should wait out.
     */
    stoppedByUser: Boolean = false,
    onStopMonitoring: () -> Unit = {},
    onStartMonitoring: () -> Unit = {},
    /** Injectable so tests need not wait out a real arming attempt. */
    armingGraceMs: Long = ARMING_GRACE_MS,
    /**
     * Whether the viewer is allowed to make noise at all. Off until asked for:
     * this screen comes up on its own when a room gets loud, sometimes over a
     * lock screen at 3am, and a viewer that starts talking the moment it
     * appears is a worse surprise than a silent one.
     */
    soundEnabled: Boolean = false,
    onSoundEnabledChange: (Boolean) -> Unit = {},
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
     * Kept apart from [soundEnabled] on purpose. The cameras follow this,
     * because playing on without focus is not ours to do; the button keeps
     * showing the switch, because a tap during a passing interruption would
     * otherwise set it to what it already was, and someone reaching to silence
     * the cameras mid-call would instead arm them for when the call ends.
     */
    soundGranted: Boolean = true,
    /**
     * Listen mode: whether the monitor has been asked to keep every room coming
     * out of the speaker after this screen is gone.
     *
     * A different promise from [soundEnabled], and deliberately a different
     * switch. The viewer's sound is about the screen being looked at, and is
     * off after a restart for that reason; this one arms an all-night speaker,
     * and opening the app to check on a nap must not do that by accident.
     */
    listening: Boolean = false,
    onListeningChange: (Boolean) -> Unit = {},
    /**
     * The cameras actually coming out of the speaker, or none. The outcome
     * rather than the request, for the same reason [soundGranted] is kept apart
     * from [soundEnabled]: this screen must not confirm a room is audible
     * before it is.
     */
    listeningCameraIds: Set<String> = emptySet(),
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
    /** Injectable so tests need not wait out a real refusal. */
    listenRefusalGraceMs: Long = LISTEN_REFUSAL_GRACE_MS,
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

    val audible = soundEnabled && soundGranted

    // Rotation is a grid problem: one camera on screen alone already has the
    // user's whole attention, so it simply keeps the sound for as long as it
    // is up.
    val audibleCameraId = rememberAudibleCameraId(
        cameraIds = visibleCameraIds,
        enabled = audible && fullscreen == null,
        intervalMs = soundRotationIntervalMs,
    )

    // The two toggles are icons that flip between two states, and neither
    // icon says which state a tap just put them in — least of all the screen
    // one, whose "awake" and "asleep" glyphs read alike at a glance. So every
    // press says in words what it did.
    val snackbarHostState = remember { SnackbarHostState() }
    val announce = rememberAnnouncer(snackbarHostState)

    // Turning sound on is a request, not an outcome: the caller still has to
    // win the speaker, and a refusal quietly flips the switch back off. So
    // "Sound on" waits for the state it promises — cameras actually audible —
    // and a request that came back reverted instead says why nothing changed.
    // Off needs no such caution; letting go of the speaker cannot fail.
    var soundOnRequested by remember { mutableStateOf(false) }
    val soundToggleAnnounced = { enabled: Boolean ->
        if (enabled) {
            soundOnRequested = true
        } else {
            soundOnRequested = false
            announce(R.string.viewer_sound_off_confirmed)
        }
        onSoundEnabledChange(enabled)
    }
    LaunchedEffect(audible) {
        if (audible && soundOnRequested) {
            soundOnRequested = false
            announce(R.string.viewer_sound_on_confirmed)
        }
    }
    LaunchedEffect(soundEnabled) {
        // The switch went back off while an "on" was still unconfirmed: the
        // request was refused, not fulfilled.
        if (!soundEnabled && soundOnRequested) {
            soundOnRequested = false
            announce(R.string.viewer_sound_refused)
        }
    }

    // Only rooms the monitor can actually hear. A camera it cannot listen to
    // has no audio to play aloud, and a switch for it alone would be one that
    // silently does nothing all night.
    val listenCandidates = remember(cameras, unmonitorable) {
        val cannot = unmonitorable.mapTo(mutableSetOf()) { it.id }
        cameras.filter { it.id !in cannot }
    }

    // The same request-versus-outcome caution the sound toggle takes: the
    // speaker still has to be won from whatever else might hold it.
    var listenOnRequested by remember { mutableStateOf(false) }
    val listenToggled = { enabled: Boolean ->
        if (enabled) {
            // The viewer's own sound is the same speaker asked for one room,
            // and listen mode stands down while it is on — so a switch left up
            // here would arm a nursery that never arrives. Switched off rather
            // than fought over: this request is the newer of the two, and the
            // confirmation below says what the phone will actually be doing.
            if (soundEnabled) {
                soundOnRequested = false
                onSoundEnabledChange(false)
            }
            listenOnRequested = true
        } else {
            listenOnRequested = false
            announce(R.string.viewer_listen_off_confirmed)
        }
        onListeningChange(enabled)
    }
    // Keyed on the request as well as the outcome, unlike the sound toggle
    // above: this switch is held in memory rather than in a stored setting, so
    // a refusal can arrive in the same frame as the request and never move
    // [listening] at all. Watching only the outcome would let exactly that case
    // — the one where the phone stays silent — pass without a word.
    LaunchedEffect(listeningCameraIds, listenOnRequested) {
        val playing = listenCandidates.filter { it.id in listeningCameraIds }
        if (playing.isNotEmpty() && listenOnRequested) {
            listenOnRequested = false
            // With the screen about to go off, this is the last chance to say
            // what the phone will be broadcasting — and what that does to an
            // alert, which differs: one room needs no naming, several do.
            if (playing.size == 1) {
                announce(R.string.viewer_listen_on_confirmed, playing.single().name)
            } else {
                announce(R.string.viewer_listen_on_confirmed_rooms, playing.size)
            }
        }
    }
    LaunchedEffect(listening, listenOnRequested, listenRefusalGraceMs) {
        if (listening || !listenOnRequested) return@LaunchedEffect
        // Still off with an "on" outstanding. Waited out rather than announced
        // at once, because the answer has a flow to travel down first — and
        // this effect is re-keyed the moment the switch moves, so a speaker
        // that does arrive cancels the wait instead of being talked over.
        delay(listenRefusalGraceMs)
        listenOnRequested = false
        announce(R.string.viewer_listen_refused)
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
                showLabel = false,
                // Makes way for the back button in the top-start corner; the
                // status pill slides right of it instead of under it.
                statusInsetStart = BACK_BUTTON_STATUS_INSET,
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
            InactivityBar(
                countdown = countdown,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .safeDrawingPadding(),
            )
            // The visible way back, in the corner back buttons live in. Back
            // and the edge swipe do the same, but nothing on a screen that is
            // all picture says so; the alerted camera keeps it too, because
            // the transition below is the same one that hands the lock screen
            // back. The tile's status pill is inset to sit beside it — a
            // button over "Reconnecting" would let a frozen frame pass for a
            // live one.
            FilledTonalIconButton(
                onClick = { fullscreenId = null },
                shapes = IconButtonDefaults.shapes(),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .safeDrawingPadding()
                    .padding(12.dp)
                    .testTag("back-to-grid"),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.viewer_all_cameras),
                )
            }
            // The controls a single camera keeps. Without the first, sound could
            // be switched on solely from the grid — including for a camera an
            // alert opened, which is precisely when the user wants to listen.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .safeDrawingPadding()
                    .padding(12.dp),
            ) {
                if (talkback != null) {
                    TalkbackButton(
                        availability = talkbackAvailability,
                        talking = talking,
                        // Holding a button is as much a sign of someone being
                        // there as touching the picture is — and a countdown
                        // that expired mid-sentence would take the room away
                        // while somebody was still speaking to it.
                        onPress = {
                            countdown.reset()
                            if (talkbackAvailability == TalkbackAvailability.Ready) {
                                talkback.press()
                            }
                        },
                        // No snackbar: nobody let go, so there is nothing to
                        // tell them about how they were holding it.
                        onCancel = talkback::release,
                        onRelease = { heldMillis ->
                            talkback.release()
                            // A tap is not a mistake worth blocking — the press
                            // still runs, and its silence is harmless — but it
                            // is a mistake worth naming, because the camera does
                            // make a small noise and the room hears no words.
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
                SoundToggle(
                    soundEnabled = soundEnabled,
                    // Reaching for the sound is as much a sign of someone being
                    // there as touching the picture is.
                    onSoundEnabledChange = {
                        countdown.reset()
                        soundToggleAnnounced(it)
                    },
                )
            }
            if (explaining) {
                TalkbackNotice(
                    availability = talkbackAvailability,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .safeDrawingPadding()
                        .padding(top = 68.dp, end = 12.dp),
                )
            }
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .safeDrawingPadding()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // In the column with the countdown rather than on the tile: two
                // overlays that each pick their own corner meet in the middle
                // of a narrow phone, and the notice would cover the meter for
                // the whole minute it counts.
                val level = audioLevels[fullscreen.id]
                if (level != null) {
                    AudioMeterPill(
                        cameraName = fullscreen.name,
                        level = level,
                        threshold = audioThreshold,
                    )
                }
                InactivityNotice(countdown = countdown, onStay = countdown::reset)
                ToggleSnackbarHost(snackbarHostState)
            }
        }
        return
    }

    // Stopping is the one control here that can quietly undo the whole point of
    // the app, and the viewer is a screen people prop up and brush past at
    // night. So the badge opens the question and this answers it.
    var confirmingStop by rememberSaveable { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        when {
            cameras.isEmpty() -> EmptyState(
                hasDisabledOnly = hasDisabledOnly,
                onOpenSettings = onOpenSettings,
                onOpenOnboarding = onOpenOnboarding,
            )

            else -> Column(modifier = Modifier.safeDrawingPadding()) {
                if (unmonitorable.isNotEmpty()) {
                    UnmonitorableNotice(unmonitorable)
                }
                CameraLayout(
                    cameras = cameras,
                    sources = sources,
                    streams = streams,
                    audibleCameraId = audibleCameraId,
                    audioLevels = audioLevels,
                    audioThreshold = audioThreshold,
                    gridState = gridState,
                    onFullscreen = ::promote,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        // Floated over the video rather than given a bar of its own: on a phone
        // an app bar costs a camera's worth of height to hold a few buttons.
        // A flow rather than a row: on the narrowest phones the widest badge
        // plus three buttons is more than one line holds, and a row would
        // squeeze whatever came last instead of letting it step down a line.
        FlowRow(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .safeDrawingPadding()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            MonitoringBadge(
                running = monitoringRunning,
                canMonitor = canMonitor,
                stoppedByUser = stoppedByUser,
                armingGraceMs = armingGraceMs,
                onStop = { confirmingStop = true },
                onStart = onStartMonitoring,
            )
            // Nothing to listen to yet: an empty viewer offers setup, not a
            // switch for sound that has no camera to come from — nor one for a
            // display it never holds awake in the first place.
            if (cameras.isNotEmpty()) {
                SoundToggle(
                    soundEnabled = soundEnabled,
                    onSoundEnabledChange = soundToggleAnnounced,
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
                // Only while something is actually listening. Listen mode is
                // the monitor's decoding turned up, so with the monitor
                // stopped this would be a switch for a speaker with nothing
                // behind it — and the badge beside it is already the honest
                // account of why.
                if (monitoringRunning && listenCandidates.isNotEmpty()) {
                    ListenToggle(
                        listening = listening,
                        onListeningChange = listenToggled,
                    )
                }
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

        // Bottom-aligned, unlike the notices above the grid: the top of this
        // screen is already spoken for by the controls, and a message about the
        // whole device belongs where nothing is competing with it.
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .safeDrawingPadding()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            NetworkNotice(reach = networkReach)
            ToggleSnackbarHost(snackbarHostState)
        }

        if (confirmingStop) {
            StopMonitoringDialog(
                onConfirm = {
                    confirmingStop = false
                    onStopMonitoring()
                },
                onDismiss = { confirmingStop = false },
            )
        }
    }
}

/**
 * What the monitor is doing, and the way to change it — the control the viewer
 * had no home for until now.
 *
 * It says the state rather than offering a switch, because the state is the
 * part that is hard to know: monitoring runs in a service with no window, and
 * the only other place it ever appears is a notification behind the lock
 * screen. Reading "Listening" over the cameras is the whole point; being able
 * to end it from there is what follows from being told.
 */
@Composable
private fun MonitoringBadge(
    running: Boolean,
    canMonitor: Boolean,
    stoppedByUser: Boolean,
    armingGraceMs: Long,
    onStop: () -> Unit,
    onStart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Nothing listenable and nothing running: the empty state and the
    // unmonitorable notice already explain why, and an offer to start what
    // cannot start would only be a third thing to read.
    if (!running && !canMonitor) return

    // Off, and not because anyone asked. A viewer that has just opened is
    // almost always a monitor part-way through starting — arming is a
    // permission check, a settings read and a service launch behind the first
    // frame. Saying "not monitoring" into that gap would make the badge cry
    // wolf on every single launch, and a warning that is usually wrong is not
    // read at all by the night it is right. So it waits, and speaks up only if
    // the start never lands.
    var armingFailed by remember { mutableStateOf(false) }
    LaunchedEffect(running, stoppedByUser, armingGraceMs) {
        armingFailed = false
        if (!running && !stoppedByUser) {
            delay(armingGraceMs)
            armingFailed = true
        }
    }
    // A stop the user asked for needs none of that patience: it is already true
    // the instant they confirm it, and the badge has to show for the tap that
    // starts monitoring again.
    if (!running && !stoppedByUser && !armingFailed) return

    Button(
        onClick = if (running) onStop else onStart,
        shapes = ButtonDefaults.shapes(),
        colors = if (running) {
            ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        } else {
            // Not an idle state to be styled quietly: a baby monitor that is
            // not monitoring is the one thing on this screen worth alarm.
            ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            )
        },
        contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
        modifier = modifier.testTag("monitoring-badge"),
    ) {
        Icon(
            painter = painterResource(
                if (running) R.drawable.ic_graphic_eq else R.drawable.ic_do_not_disturb,
            ),
            // The label says what is happening; the action a tap performs is
            // the part a screen reader would otherwise have to guess.
            contentDescription = stringResource(
                if (running) R.string.viewer_stop_monitoring else R.string.viewer_start_monitoring,
            ),
            modifier = Modifier.size(ButtonDefaults.IconSize),
        )
        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
        Text(
            stringResource(
                if (running) R.string.viewer_monitoring_on else R.string.viewer_monitoring_off,
            ),
        )
    }
}

/**
 * Stopping is deliberate or it is a mistake — there is no third case. The badge
 * sits over live video on a screen that stays on all night, and a stray tap
 * that disarmed the monitor would not announce itself: the cameras would go on
 * playing exactly as they had a second earlier.
 */
@Composable
private fun StopMonitoringDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.viewer_stop_monitoring_title)) },
        text = { Text(stringResource(R.string.viewer_stop_monitoring_body)) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.testTag("confirm-stop-monitoring"),
            ) {
                Text(stringResource(R.string.viewer_stop_monitoring))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("keep-monitoring"),
            ) {
                Text(stringResource(R.string.viewer_keep_monitoring))
            }
        },
    )
}

/**
 * One switch for the whole viewer rather than one per camera. Which room is
 * audible is already answered — by opening a camera, or by whose turn it is —
 * so the only question left is whether the phone should be making noise.
 */
@Composable
private fun SoundToggle(
    soundEnabled: Boolean,
    onSoundEnabledChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    FilledTonalIconButton(
        onClick = { onSoundEnabledChange(!soundEnabled) },
        shapes = IconButtonDefaults.shapes(),
        modifier = modifier.testTag("toggle-sound"),
    ) {
        Icon(
            painter = painterResource(
                if (soundEnabled) R.drawable.ic_volume_up else R.drawable.ic_volume_off,
            ),
            contentDescription = stringResource(
                if (soundEnabled) R.string.viewer_sound_off else R.string.viewer_sound_on,
            ),
        )
    }
}

/**
 * Listen mode's switch: whether every room keeps coming out of the speaker
 * once this screen is gone.
 *
 * Next to the viewer's sound button rather than folded into it, because the two
 * mean different things. That one is "the camera I am looking at, while I am
 * looking at it"; this one is "the house I want to hear while the phone is face
 * down on the nightstand", and it is the monitoring service, not this screen,
 * that keeps its promise.
 *
 * On the grid only, not on a camera opened alone. Watching one room is the
 * opposite end of the day from setting the phone down for the night, and the
 * fullscreen chrome is already the busiest corner on the screen.
 */
@Composable
private fun ListenToggle(
    listening: Boolean,
    onListeningChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    FilledTonalIconButton(
        onClick = { onListeningChange(!listening) },
        shapes = IconButtonDefaults.shapes(),
        modifier = modifier.testTag("toggle-listen"),
    ) {
        Icon(
            painter = painterResource(
                if (listening) R.drawable.ic_broadcast else R.drawable.ic_broadcast_off,
            ),
            contentDescription = stringResource(
                if (listening) R.string.viewer_listen_off else R.string.viewer_listen_on,
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
    audibleCameraId: String?,
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
                val audible = camera.id == audibleCameraId
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
    onOpenSettings: () -> Unit,
    onOpenOnboarding: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .safeDrawingPadding()
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
    }
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
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.large,
        modifier = modifier
            // Worth interrupting for, and only ever a sentence — nothing like
            // the countdown next door, which changes every second.
            .semantics { liveRegion = LiveRegionMode.Polite }
            .testTag("network-notice"),
    ) {
        Text(
            text = stringResource(message),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}

/** Enabled but not listenable: say so rather than imply full coverage. */
@Composable
private fun UnmonitorableNotice(cameras: List<Camera>) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("unmonitorable-notice"),
    ) {
        Text(
            text = pluralStringResource(
                R.plurals.viewer_unmonitorable,
                cameras.size,
                cameras.size,
                cameras.joinToString { it.name },
            ),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

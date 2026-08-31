package app.dozecam.ui.monitor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.Color
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
import app.dozecam.data.Camera
import app.dozecam.network.NetworkReach
import app.dozecam.player.CameraStreams
import kotlinx.coroutines.launch
import app.dozecam.player.StreamSource
import app.dozecam.player.VideoPlayerController
import app.dozecam.player.rememberCameraStreams
import kotlinx.coroutines.delay

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
        Box(modifier = modifier.fillMaxSize()) {
            CameraTile(
                camera = fullscreen,
                source = sources[fullscreen.id],
                streams = streams,
                showLabel = false,
                // The one camera on screen alone is the one worth hearing; this
                // is the "listen to the room" case the viewer exists for, so it
                // needs nothing beyond sound being switched on.
                audible = audible,
                // A tap now means "I am still here" rather than "take me back":
                // leaving is Back, and a countdown with no way to answer it
                // would cap every look at one room to a flat minute.
                onClick = countdown::reset,
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
            // The only control a single camera keeps. Without it, sound could
            // be switched on solely from the grid — including for a camera an
            // alert opened, which is precisely when the user wants to listen.
            SoundToggle(
                soundEnabled = soundEnabled,
                // Reaching for the sound is as much a sign of someone being
                // there as touching the picture is.
                onSoundEnabledChange = {
                    countdown.reset()
                    soundToggleAnnounced(it)
                },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .safeDrawingPadding()
                    .padding(12.dp),
            )
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .safeDrawingPadding()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
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
@Composable
private fun rememberAnnouncer(hostState: SnackbarHostState): (Int) -> Unit {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    return remember(hostState, context) {
        { message: Int ->
            hostState.currentSnackbarData?.dismiss()
            scope.launch {
                hostState.showSnackbar(
                    message = context.getString(message),
                    duration = SnackbarDuration.Short,
                )
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
    Text(
        text = stringResource(message),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onErrorContainer,
        modifier = modifier
            .background(MaterialTheme.colorScheme.errorContainer, MaterialTheme.shapes.large)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            // Worth interrupting for, and only ever a sentence — nothing like
            // the countdown next door, which changes every second.
            .semantics { liveRegion = LiveRegionMode.Polite }
            .testTag("network-notice"),
    )
}

/** Enabled but not listenable: say so rather than imply full coverage. */
@Composable
private fun UnmonitorableNotice(cameras: List<Camera>) {
    Text(
        text = pluralStringResource(
            R.plurals.viewer_unmonitorable,
            cameras.size,
            cameras.size,
            cameras.joinToString { it.name },
        ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag("unmonitorable-notice"),
    )
}

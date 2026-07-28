package app.dozecam.ui.monitor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.dozecam.R
import app.dozecam.data.Camera
import app.dozecam.player.StreamSource
import app.dozecam.player.VideoPlayerController

/** Below this there is only room for one camera across; above it, two. */
private val TWO_COLUMN_BREAKPOINT = 600.dp

/** Above this, a third column still leaves each tile big enough to read. */
private val THREE_COLUMN_BREAKPOINT = 1000.dp

/**
 * The viewer: every enabled camera, live, and nothing else. Arming the monitor
 * and everything about how a camera is set up live in settings, so the only
 * chrome here is the way to get there.
 */
@Composable
fun MonitorScreen(
    cameras: List<Camera>,
    sources: Map<String, StreamSource>,
    controllerFactory: (StreamSource) -> VideoPlayerController,
    networkOnline: Boolean,
    unmonitorable: List<Camera>,
    hasDisabledOnly: Boolean,
    onOpenSettings: () -> Unit,
    onOpenOnboarding: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Whether the viewer is allowed to make noise at all. Off until asked for:
     * this screen comes up on its own when a room gets loud, sometimes over a
     * lock screen at 3am, and a viewer that starts talking the moment it
     * appears is a worse surprise than a silent one.
     */
    soundEnabled: Boolean = false,
    onSoundEnabledChange: (Boolean) -> Unit = {},
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
    var fullscreenId by rememberSaveable { mutableStateOf<String?>(null) }
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
                fullscreenId = id
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

    // Rotation is a grid problem: one camera on screen alone already has the
    // user's whole attention, so it simply keeps the sound for as long as it
    // is up.
    val audibleCameraId = rememberAudibleCameraId(
        cameraIds = visibleCameraIds,
        enabled = audible && fullscreen == null,
        intervalMs = soundRotationIntervalMs,
    )

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
                controllerFactory = controllerFactory,
                networkOnline = networkOnline,
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
                    onSoundEnabledChange(it)
                },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .safeDrawingPadding()
                    .padding(12.dp),
            )
            InactivityNotice(
                countdown = countdown,
                onStay = countdown::reset,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .safeDrawingPadding()
                    .padding(16.dp),
            )
        }
        return
    }

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
                    controllerFactory = controllerFactory,
                    networkOnline = networkOnline,
                    audibleCameraId = audibleCameraId,
                    gridState = gridState,
                    onFullscreen = { fullscreenId = it },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        // Floated over the video rather than given a bar of its own: on a phone
        // an app bar costs a camera's worth of height to hold two buttons.
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .safeDrawingPadding()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Nothing to listen to yet: an empty viewer offers setup, not a
            // switch for sound that has no camera to come from.
            if (cameras.isNotEmpty()) {
                SoundToggle(
                    soundEnabled = soundEnabled,
                    onSoundEnabledChange = onSoundEnabledChange,
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
    }
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
    controllerFactory: (StreamSource) -> VideoPlayerController,
    networkOnline: Boolean,
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
                        controllerFactory = controllerFactory,
                        networkOnline = networkOnline,
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

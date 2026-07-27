package app.dozecam.ui.monitor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
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

    BackHandler(enabled = fullscreen != null) { fullscreenId = null }

    if (fullscreen != null) {
        CameraTile(
            camera = fullscreen,
            source = sources[fullscreen.id],
            controllerFactory = controllerFactory,
            networkOnline = networkOnline,
            showLabel = false,
            // The one camera on screen alone is the one worth hearing; this is
            // the "listen to the room" case the viewer exists for.
            muted = false,
            onClick = { fullscreenId = null },
            modifier = modifier
                .fillMaxSize()
                .testTag("fullscreen-tile"),
        )
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
                    onFullscreen = { fullscreenId = it },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        // Floated over the video rather than given a bar of its own: on a phone
        // an app bar costs a camera's worth of height to hold one button.
        FilledTonalIconButton(
            onClick = onOpenSettings,
            shapes = IconButtonDefaults.shapes(),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .safeDrawingPadding()
                .padding(12.dp)
                .testTag("open-settings"),
        ) {
            Icon(
                imageVector = Icons.Default.Settings,
                contentDescription = stringResource(R.string.settings),
            )
        }
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
            columns = GridCells.Fixed(columns),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier
                .fillMaxSize()
                .testTag("camera-list-$columns"),
        ) {
            items(cameras, key = { it.id }) { camera ->
                CameraTile(
                    camera = camera,
                    source = sources[camera.id],
                    controllerFactory = controllerFactory,
                    networkOnline = networkOnline,
                    onClick = { onFullscreen(camera.id) },
                    // Tiles keep a 16:9 box; the picture letterboxes inside it,
                    // so a 4:3 camera still shows its whole frame.
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f),
                )
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

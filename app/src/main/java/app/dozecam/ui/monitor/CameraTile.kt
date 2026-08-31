package app.dozecam.ui.monitor

import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.viewinterop.AndroidView
import app.dozecam.R
import app.dozecam.data.Camera
import app.dozecam.player.CameraStreams
import app.dozecam.player.ConnectionState
import app.dozecam.player.StreamSource
import app.dozecam.ui.components.AudioLevelMeter
import app.dozecam.ui.theme.LocalNightTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * What a tile reports before it has a session to report on — a camera whose
 * source has not resolved yet, or one whose stream is still being built.
 */
private val connecting: StateFlow<ConnectionState> =
    MutableStateFlow(ConnectionState.Connecting)
private val noFrameYet: StateFlow<Long?> = MutableStateFlow(null)
private val noAspectYet: StateFlow<Float?> = MutableStateFlow(null)

/**
 * One camera's live picture. Tiles are independent on purpose: in a grid one
 * camera stalling must not disturb the others, and its status pill has to tell
 * the truth about that camera alone.
 *
 * A tile shows a session rather than owning one. [CameraStreams] holds the
 * players, so opening a camera from the grid hands the running stream to the
 * tile that fills the screen instead of tearing it down and negotiating the
 * same camera again — and the cameras left behind keep their sessions, minus
 * their video, until the grid comes back. What a tile still decides is what it
 * is for: where the picture goes, and whether this is the camera being heard.
 */
@Composable
fun CameraTile(
    camera: Camera,
    source: StreamSource?,
    streams: CameraStreams,
    modifier: Modifier = Modifier,
    showLabel: Boolean = true,
    /**
     * Whether this tile is the one being listened to. Silent by default:
     * several tiles play at once, and several rooms talking over each other is
     * worse than none, so only the camera holding the sound is ever audible —
     * and it says so on screen, because sound with no visible source is
     * indistinguishable from the wrong camera being open.
     */
    audible: Boolean = false,
    /**
     * What the monitor is hearing from this camera right now, or null when it
     * is not listening — a stopped monitor must show no meter at all, because
     * a bar sitting at zero would claim the room is quiet when the truth is
     * that nobody is checking.
     */
    audioLevel: Float? = null,
    /** The level at which an alert would fire, marked on the meter. */
    audioThreshold: Float = 1f,
    onClick: (() -> Unit)? = null,
    /**
     * Pinch state for a tile that has the screen to itself, where looking
     * closer at one corner of a room is worth a gesture. Null in the grid: a
     * thumbnail has no detail to lean into, and a pinch that zoomed one tile
     * would leave the grid lying about that camera's frame.
     */
    zoom: PinchZoomState? = null,
    /** Fires on every zoom gesture — fingers on the picture are presence. */
    onZoomGesture: () -> Unit = {},
) {
    val context = LocalContext.current
    val host = remember(camera.id) { FrameLayout(context) }

    // Claimed for exactly as long as this tile is on screen, under a token of
    // this tile's own. A camera being promoted is claimed by the tile filling
    // the screen and given up by the tile in the grid, and one token each is
    // what makes the handover come out the same whichever order Compose applies
    // them in.
    val claim = remember { Any() }
    DisposableEffect(streams, claim, camera.id, source) {
        if (source != null) streams.claim(claim, camera.id, source)
        onDispose { streams.unclaim(claim) }
    }

    val stream = streams[camera.id]

    DisposableEffect(stream, host) {
        stream?.attach(host)
        onDispose { stream?.detach(host) }
    }

    // A session starts silent and is asked for sound afterwards, so a tile
    // arriving in the grid can never blurt out a burst of room audio.
    LaunchedEffect(stream, audible) {
        stream?.setMuted(!audible)
    }

    // Read from the session rather than kept per tile: a camera opened from the
    // grid is already connected, and a status pill that reset to "Connecting"
    // would report a reconnection that is not happening.
    val connection by (stream?.connection ?: connecting).collectAsState()
    val lastFrameAtMs by (stream?.lastFrameAtMs ?: noFrameYet).collectAsState()

    // The zoom clamps panning to the picture rather than the tile, so it needs
    // to know both: the tile from layout, the picture's shape from the stream.
    if (zoom != null) {
        val videoAspect by (stream?.videoAspect ?: noAspectYet).collectAsState()
        LaunchedEffect(zoom, videoAspect) { zoom.pictureChanged(videoAspect) }
    }

    // Read through a state holder because the gesture coroutine is keyed on
    // the zoom state and outlives any one callback: the caller's inactivity
    // countdown is rebuilt when a repeat alert lands on this same camera, and
    // a pinch after that must reach the countdown now on duty, not restart the
    // orphaned one while the live one quietly runs out.
    val zoomGesture by rememberUpdatedState(onZoomGesture)

    Box(
        modifier = modifier
            .background(Color.Black)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .then(
                if (zoom != null) {
                    Modifier
                        .onSizeChanged { zoom.viewportChanged(it.toSize()) }
                        .pinchZoom(zoom) { zoomGesture() }
                } else {
                    Modifier
                },
            )
            .testTag("camera-tile-${camera.name}"),
    ) {
        // Only the picture is scaled; the status pill and badges are chrome
        // about the tile, not part of the room, and must stay put and legible
        // at any zoom. The update lambda re-runs whenever the zoom state it
        // reads changes, without recomposing the tile around it.
        AndroidView(
            factory = { host },
            update = { view -> view.applyPinchZoom(zoom) },
            modifier = Modifier.fillMaxSize(),
        )
        StatusOverlay(
            state = connection,
            lastFrameAtMs = lastFrameAtMs,
            modifier = Modifier.align(Alignment.TopStart),
        )
        if (audible) {
            AudibleBadge(
                cameraName = camera.name,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(12.dp),
            )
        }
        // The name and the meter share a row so they read as one statement:
        // this room, this loud. (Fullscreen shows neither — the caller draws
        // its meter among the other fullscreen controls, where the countdown
        // notice can be kept from covering it.)
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(12.dp)
                // A weighted label lets a long name stretch the row to the
                // tile's far edge — where the audible badge lives. Its
                // footprint is given up front, so the two can never cover
                // each other.
                .padding(end = if (audible) 44.dp else 0.dp),
        ) {
            if (showLabel) {
                Text(
                    text = camera.name,
                    style = MaterialTheme.typography.labelLargeEmphasized,
                    color = Color.White,
                    modifier = Modifier
                        // Weighted so the meter is measured first: names are
                        // user-typed and unbounded, and an unweighted label
                        // would swallow the whole row and squeeze the meter to
                        // nothing. A long name wraps instead.
                        .weight(1f, fill = false)
                        .background(
                            MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f),
                            MaterialTheme.shapes.small,
                        )
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                        .testTag("camera-label-${camera.name}"),
                )
            }
            if (audioLevel != null) {
                AudioMeterPill(
                    cameraName = camera.name,
                    level = audioLevel,
                    threshold = audioThreshold,
                )
            }
        }
    }
}

/**
 * The live level in a scrim pill, styled to sit over video next to the other
 * tile overlays. Shared by the grid tiles and the fullscreen controls so the
 * meter looks the same wherever the same fact is being shown.
 */
@Composable
internal fun AudioMeterPill(
    cameraName: String,
    level: Float,
    threshold: Float,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .background(
                MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f),
                MaterialTheme.shapes.small,
            )
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .testTag("camera-meter-$cameraName"),
    ) {
        AudioLevelMeter(
            level = level,
            threshold = threshold,
            thresholdColor = Color.White,
            modifier = Modifier.width(64.dp),
        )
    }
}

/**
 * Marks the camera the sound is coming from. In a grid the sound moves on a
 * timer, so without this the user hears a room and has to guess which one —
 * and guessing wrong is the entire failure mode a baby monitor cannot afford.
 */
@Composable
private fun AudibleBadge(cameraName: String, modifier: Modifier = Modifier) {
    val night = LocalNightTheme.current
    val colorScheme = MaterialTheme.colorScheme
    Icon(
        painter = painterResource(R.drawable.ic_volume_up),
        contentDescription = stringResource(R.string.viewer_audible_camera, cameraName),
        tint = if (night) colorScheme.primary else Color.White,
        modifier = modifier
            .background(colorScheme.scrim.copy(alpha = 0.55f), CircleShape)
            .padding(8.dp)
            .size(20.dp)
            .testTag("audible-badge-$cameraName"),
    )
}

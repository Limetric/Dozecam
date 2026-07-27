package app.dozecam.ui.monitor

import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.dozecam.data.Camera
import app.dozecam.player.ConnectionState
import app.dozecam.player.PlaybackWatchdog
import app.dozecam.player.StreamSource
import app.dozecam.player.VideoPlayerController
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * One camera's live picture, with its own player and its own watchdog. Tiles
 * are independent on purpose: in a grid one camera stalling must not disturb
 * the others, and its status pill has to tell the truth about that camera
 * alone.
 *
 * The player exists only while this tile is composed and its host is at least
 * STARTED, so scrolling a tile away or backgrounding the app tears the stream
 * down instead of leaving a decoder running.
 */
@Composable
fun CameraTile(
    camera: Camera,
    source: StreamSource?,
    controllerFactory: (StreamSource) -> VideoPlayerController,
    networkOnline: Boolean,
    modifier: Modifier = Modifier,
    showLabel: Boolean = true,
    /**
     * Silent by default: several tiles play at once, and several rooms talking
     * over each other is worse than none. Only a camera singled out on its own
     * is worth hearing.
     */
    muted: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val host = remember(camera.id) { FrameLayout(context) }
    val lifecycleOwner = LocalLifecycleOwner.current
    var connection by remember(camera.id) {
        mutableStateOf<ConnectionState>(ConnectionState.Connecting)
    }
    var lastFrameAtMs by remember(camera.id) { mutableStateOf<Long?>(null) }

    // Read inside the session rather than keyed on: connectivity changing is
    // exactly what the watchdog exists to absorb, so a flapping Wi-Fi must feed
    // it events, not tear the player down and build a new one.
    val online by rememberUpdatedState(networkOnline)
    val silent by rememberUpdatedState(muted)

    // Keyed by everything a session depends on: a URL edit or a transport
    // change means the running session is stale and must be rebuilt.
    LaunchedEffect(host, camera.url, source) {
        val resolved = source ?: return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val controller = controllerFactory(resolved)
            val watchdog = PlaybackWatchdog(
                scope = this,
                onReconnect = { controller.play(resolved) },
            )
            val stateJob = launch { watchdog.state.collect { connection = it } }
            val frameJob = launch { watchdog.lastFrameAtMs.collect { lastFrameAtMs = it } }
            controller.listener = watchdog::onPlayerEvent
            controller.attach(host)
            watchdog.start()
            // Emits the current value first, which also covers coming to the
            // foreground already offline: start() assumes the network is up, and
            // the network monitor only speaks up on a change, so without this
            // the tile would sit reconnecting forever.
            val networkJob = launch {
                snapshotFlow { online }.collect {
                    if (it) watchdog.onNetworkAvailable() else watchdog.onNetworkLost()
                }
            }
            // Applied synchronously, because launching it would let play()
            // start first and a tile scrolling into a grid would blurt out a
            // burst of room audio. The collector then carries later changes
            // within a session, without a rebuild.
            //
            // Promotion to fullscreen is a different matter: that swaps the
            // whole layout, so this tile is disposed and the fullscreen one
            // starts fresh. Deliberate — the alternative keeps every grid
            // decoder running behind the camera being looked at — and cheap on
            // a LAN, where reconnecting costs about as long as the 150ms of
            // caching the player asks for anyway.
            controller.setMuted(silent)
            val muteJob = launch {
                snapshotFlow { silent }.drop(1).collect(controller::setMuted)
            }
            controller.play(resolved)
            try {
                awaitCancellation()
            } finally {
                muteJob.cancel()
                networkJob.cancel()
                stateJob.cancel()
                frameJob.cancel()
                watchdog.stop()
                controller.listener = null
                controller.stop()
                controller.detach()
                controller.release()
            }
        }
    }

    Box(
        modifier = modifier
            .background(Color.Black)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .testTag("camera-tile-${camera.name}"),
    ) {
        AndroidView(factory = { host }, modifier = Modifier.fillMaxSize())
        StatusOverlay(
            state = connection,
            lastFrameAtMs = lastFrameAtMs,
            modifier = Modifier.align(Alignment.TopStart),
        )
        if (showLabel) {
            Text(
                text = camera.name,
                style = MaterialTheme.typography.labelLargeEmphasized,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(12.dp)
                    .background(
                        MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f),
                        MaterialTheme.shapes.small,
                    )
                    .padding(horizontal = 10.dp, vertical = 4.dp)
                    .testTag("camera-label-${camera.name}"),
            )
        }
    }
}

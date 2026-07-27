package app.dozecam.ui.monitor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.dozecam.R
import app.dozecam.player.ConnectionState
import app.dozecam.ui.theme.LocalNightTheme
import app.dozecam.ui.theme.StatusLive
import app.dozecam.ui.theme.StatusOffline
import app.dozecam.ui.theme.StatusWaiting
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

/**
 * Honest connection state, always visible. A frozen frame silently pretending to
 * be live is the worst failure mode for a baby monitor.
 *
 * The pill sits on a scrim over arbitrary video, so its colours are chosen for
 * legibility rather than taken from the surface roles — except under the night
 * palette, where the whole point is to keep light off the wall.
 */
@Composable
fun StatusOverlay(
    state: ConnectionState,
    lastFrameAtMs: Long?,
    modifier: Modifier = Modifier,
) {
    val night = LocalNightTheme.current
    val colorScheme = MaterialTheme.colorScheme
    val (label, indicatorColor) = when (state) {
        ConnectionState.Connecting ->
            stringResource(R.string.status_connecting) to
                if (night) colorScheme.tertiary else StatusWaiting

        ConnectionState.Live ->
            stringResource(R.string.status_live) to
                if (night) colorScheme.primary else StatusLive

        is ConnectionState.Reconnecting ->
            stringResource(R.string.status_reconnecting, state.attempt) to
                if (night) colorScheme.tertiary else StatusWaiting

        ConnectionState.Offline ->
            stringResource(R.string.status_offline) to
                if (night) colorScheme.error else StatusOffline
    }

    val text = if (state != ConnectionState.Live && lastFrameAtMs != null) {
        val time = timeFormatter.format(
            Instant.ofEpochMilli(lastFrameAtMs).atZone(ZoneId.systemDefault()),
        )
        stringResource(R.string.status_with_last_frame, label, time)
    } else {
        label
    }

    Box(modifier = modifier.safeDrawingPadding()) {
        Row(
            modifier = Modifier
                .padding(12.dp)
                .background(colorScheme.scrim.copy(alpha = 0.55f), CircleShape)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(indicatorColor, CircleShape),
            )
            Text(
                text = text,
                color = if (night) colorScheme.onSurface else Color.White,
                style = MaterialTheme.typography.labelLargeEmphasized,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

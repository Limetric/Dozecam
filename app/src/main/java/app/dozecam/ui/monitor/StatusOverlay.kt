package app.dozecam.ui.monitor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.dozecam.R
import app.dozecam.player.ConnectionState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

/**
 * Honest connection state, always visible. A frozen frame silently
 * pretending to be live is the worst failure mode for a baby monitor.
 */
@Composable
fun StatusOverlay(
    state: ConnectionState,
    lastFrameAtMs: Long?,
    modifier: Modifier = Modifier,
) {
    val (label, color) = when (state) {
        ConnectionState.Connecting -> stringResource(R.string.status_connecting) to Color(0xFFFFB74D)
        ConnectionState.Live -> stringResource(R.string.status_live) to Color(0xFF66BB6A)
        is ConnectionState.Reconnecting ->
            stringResource(R.string.status_reconnecting, state.attempt) to Color(0xFFFFB74D)
        ConnectionState.Offline -> stringResource(R.string.status_offline) to Color(0xFFEF5350)
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
                .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(16.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(color, CircleShape),
            )
            Text(
                text = text,
                color = Color.White,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

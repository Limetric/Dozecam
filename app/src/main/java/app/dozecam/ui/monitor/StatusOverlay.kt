package app.dozecam.ui.monitor

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.dozecam.R
import app.dozecam.player.ConnectionState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

/**
 * Honest connection state, always visible. A frozen frame silently pretending to
 * be live is the worst failure mode for a baby monitor.
 *
 * State is carried three ways over the same pill: the word, the colour, and the
 * icon's silhouette. The colour comes from the scheme — primary for a healthy
 * stream, tertiary while it is being fetched, error when it is gone — which
 * means it is the user's own wallpaper that decides those hues, and two of them
 * can land close together. The shape does not: signal, sync, struck-through
 * camera are told apart across a dark room, by a reader who cannot separate the
 * hues, and by anyone glancing at the tile rather than reading it.
 */
@Composable
fun StatusOverlay(
    state: ConnectionState,
    lastFrameAtMs: Long?,
    modifier: Modifier = Modifier,
) {
    val appearance = state.appearance(MaterialTheme.colorScheme)
    val label = when (state) {
        ConnectionState.Connecting -> stringResource(R.string.status_connecting)
        ConnectionState.Live -> stringResource(R.string.status_live)
        is ConnectionState.Reconnecting ->
            stringResource(R.string.status_reconnecting, state.attempt)

        ConnectionState.Offline -> stringResource(R.string.status_offline)
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
        VideoOverlaySurface(
            shape = CircleShape,
            modifier = Modifier.padding(12.dp),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(appearance.icon),
                    // The word beside it already says which state this is, and
                    // a screen reader that read both would say it twice.
                    contentDescription = null,
                    tint = appearance.color,
                    modifier = Modifier
                        .size(16.dp)
                        .testTag(appearance.tag),
                )
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelLargeEmphasized,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

/**
 * How one connection state shows itself: its silhouette, its colour, and the
 * name a test can find it by — the icon carries no description of its own, by
 * design, so the tag is what distinguishes the three on screen.
 */
internal data class StatusAppearance(
    @field:DrawableRes val icon: Int,
    val color: Color,
    val tag: String,
)

/**
 * Kept apart from the composable that draws it so the choice of roles can be
 * checked directly. Every colour here is a role rather than a constant: the
 * night palette dims all three without being asked, and nothing on this screen
 * has to know which palette is in force.
 */
internal fun ConnectionState.appearance(colors: ColorScheme): StatusAppearance = when (this) {
    ConnectionState.Live -> StatusAppearance(
        icon = R.drawable.ic_status_live,
        color = colors.primary,
        tag = "status-icon-live",
    )

    // A stream being fetched and one being fetched again are the same fact to
    // anyone looking: not live yet, not given up on. The attempt count in the
    // label is what tells them apart, for whoever wants to know.
    ConnectionState.Connecting, is ConnectionState.Reconnecting -> StatusAppearance(
        icon = R.drawable.ic_status_connecting,
        color = colors.tertiary,
        tag = "status-icon-connecting",
    )

    ConnectionState.Offline -> StatusAppearance(
        icon = R.drawable.ic_status_offline,
        color = colors.error,
        tag = "status-icon-offline",
    )
}

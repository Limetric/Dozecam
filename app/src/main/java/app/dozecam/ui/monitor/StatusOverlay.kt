package app.dozecam.ui.monitor

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import app.dozecam.R
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.dozecam.player.ConnectionState
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit

/**
 * How often the age of the last frame is re-read. The age is shown in whole
 * seconds, so a coarser tick would let the pill sit on a stale number.
 */
private const val AGE_TICK_MS = 1_000L

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
 *
 * Positioned by whoever draws it: a tile puts it in its own corner, the single
 * camera puts it in the row with the back button, and the pill is the same
 * pill in both — only [height] changes, to match what it sits beside.
 *
 * While the stream is not live the pill also says how old the picture is —
 * "last frame 12 seconds ago" — because that is the question a stalled tile
 * raises: is the room still fine, or has this been frozen for a while? A
 * clock time answered it only after arithmetic against the phone's own clock,
 * which is not what anyone does at 3am. The age is re-read every second, from
 * [clock], so the number keeps counting while the stream is still gone.
 */
@Composable
fun StatusOverlay(
    state: ConnectionState,
    lastFrameAtMs: Long?,
    modifier: Modifier = Modifier,
    height: Dp = OverlayChrome.TileHeight,
    clock: () -> Long = System::currentTimeMillis,
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
        val now = rememberTickingNow(clock)
        val age = frameAge(now - lastFrameAtMs)
        stringResource(R.string.status_with_last_frame, label, age.text())
    } else {
        label
    }

    OverlayPill(modifier = modifier, height = height) {
        Icon(
            painter = painterResource(appearance.icon),
            // The word beside it already says which state this is, and a
            // screen reader that read both would say it twice.
            contentDescription = null,
            tint = appearance.color,
            modifier = Modifier
                .size(OverlayChrome.IconSize)
                .testTag(appearance.tag),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

/**
 * The wall clock, re-read every second while the screen is started. Only the
 * non-live pill asks for it, so a healthy grid does not recompose on a timer.
 */
@Composable
private fun rememberTickingNow(clock: () -> Long): Long {
    var now by remember(clock) { mutableLongStateOf(clock()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(clock, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                now = clock()
                delay(AGE_TICK_MS)
            }
        }
    }
    return now
}

/** The unit an age is said in: the largest that still gives a count of at least one. */
internal enum class AgeUnit { SECONDS, MINUTES, HOURS, DAYS }

/**
 * How long ago the last frame arrived, in the words a person would use for it.
 * Ages are rounded down: a picture 59 seconds old is "59 seconds ago", not a
 * minute, because the monitor is the one place where rounding a stall up
 * makes it sound worse than it is and rounding it down hides how long it has
 * really been — so it says exactly what it knows.
 */
internal data class FrameAge(val count: Long, val unit: AgeUnit) {
    @Composable
    fun text(): String {
        val quantity = count.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        return when (unit) {
            AgeUnit.SECONDS -> pluralStringResource(R.plurals.last_frame_seconds_ago, quantity, quantity)
            AgeUnit.MINUTES -> pluralStringResource(R.plurals.last_frame_minutes_ago, quantity, quantity)
            AgeUnit.HOURS -> pluralStringResource(R.plurals.last_frame_hours_ago, quantity, quantity)
            AgeUnit.DAYS -> pluralStringResource(R.plurals.last_frame_days_ago, quantity, quantity)
        }
    }
}

/**
 * Kept apart from the composable so the arithmetic can be checked without a
 * screen. A negative age — the clock was set back — reads as zero seconds
 * rather than as a frame from the future.
 */
internal fun frameAge(ageMs: Long): FrameAge {
    val seconds = TimeUnit.MILLISECONDS.toSeconds(ageMs.coerceAtLeast(0L))
    return when {
        seconds < 60L -> FrameAge(seconds, AgeUnit.SECONDS)
        seconds < 3_600L -> FrameAge(seconds / 60L, AgeUnit.MINUTES)
        seconds < 86_400L -> FrameAge(seconds / 3_600L, AgeUnit.HOURS)
        else -> FrameAge(seconds / 86_400L, AgeUnit.DAYS)
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

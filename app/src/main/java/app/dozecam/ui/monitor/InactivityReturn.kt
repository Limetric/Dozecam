package app.dozecam.ui.monitor

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.dozecam.R
import kotlinx.coroutines.delay
import kotlin.math.ceil

/**
 * How long one camera stays up on its own before the viewer goes back to
 * showing all of them.
 *
 * A single camera is a detour, not the resting state: the viewer exists to
 * watch every room, and a phone left face up on a camera someone opened an hour
 * ago is one that stopped showing the rest of the house without ever saying so.
 * A minute is long enough to look, short enough that walking away from the
 * phone puts it back on its own.
 */
const val FULLSCREEN_INACTIVITY_TIMEOUT_MS = 60_000L

/** The readout counts whole seconds, so anything finer is work nobody sees. */
private const val COUNTDOWN_TICK_MS = 1_000L

/**
 * The wait before a single camera hands the screen back to the grid, and the
 * means to start that wait over.
 */
@Stable
internal class InactivityCountdown(private val timeoutMs: Long) {

    /** What is left of the wait. */
    var remainingMs by mutableLongStateOf(timeoutMs)
        private set

    /** Bumped by [reset]; the timer starts over whenever it changes. */
    var restarts by mutableIntStateOf(0)
        private set

    /** How much of the wait is left, for the draining bar. */
    val fraction: Float
        get() = if (timeoutMs <= 0L) 0f else (remainingMs.toFloat() / timeoutMs).coerceIn(0f, 1f)

    /** Rounded up, so the readout reaches zero only when the grid actually returns. */
    val remainingSeconds: Int
        get() = ceil(remainingMs / 1000.0).toInt()

    /** Someone is here after all: give them the whole minute again. */
    fun reset() {
        restarts++
    }

    internal fun remainingIs(ms: Long) {
        remainingMs = ms
    }
}

/**
 * Counts down for as long as one camera has the screen to itself, and calls
 * [onExpired] when it runs out.
 *
 * Tied to the host being at least STARTED, matching the tile's own player: a
 * camera that is not being shown is not being ignored either, so time spent in
 * the background does not count against the viewer, and coming back gives a
 * fresh minute rather than an immediate bounce to the grid.
 */
@Composable
internal fun rememberInactivityCountdown(
    cameraId: String,
    timeoutMs: Long,
    /**
     * Anything else that counts as a fresh reason to be looking at the same
     * room — a repeat alert, say, which changes nothing on screen and would
     * otherwise inherit whatever was left of the last wait.
     */
    restartOn: Any? = Unit,
    onExpired: () -> Unit,
): InactivityCountdown {
    // Keyed on the camera: an alert swapping which room is on screen is a new
    // reason to be looking, and deserves its own minute.
    val countdown = remember(cameraId, timeoutMs, restartOn) { InactivityCountdown(timeoutMs) }
    val expire by rememberUpdatedState(onExpired)
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(countdown, countdown.restarts) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var remaining = timeoutMs
            countdown.remainingIs(remaining)
            while (remaining > 0L) {
                // Stepped rather than measured against the clock: a viewer that
                // is a few frames out over a minute is nobody's problem, and
                // counting down is the same arithmetic in a test as at 3am.
                val step = minOf(COUNTDOWN_TICK_MS, remaining)
                delay(step)
                remaining -= step
                countdown.remainingIs(remaining)
            }
            expire()
        }
    }

    return countdown
}

/**
 * The wait, drawn as it drains. Sits at the very top of the picture where it
 * costs no room: the number below says exactly how long is left, and this says
 * it at a glance from across a dark room.
 */
@Composable
internal fun InactivityBar(countdown: InactivityCountdown, modifier: Modifier = Modifier) {
    LinearProgressIndicator(
        progress = { countdown.fraction },
        color = MaterialTheme.colorScheme.primary,
        trackColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = VIDEO_OVERLAY_ALPHA),
        modifier = modifier
            .fillMaxWidth()
            .testTag("inactivity-bar"),
    )
}

/**
 * Says when the grid is coming back, and offers the way to stay.
 *
 * Tapping the picture stays too, but a gesture that is also how this screen
 * behaved before deserves something visible next to it — otherwise the only
 * discoverable answer to a countdown nobody wants is to watch it run out.
 */
@Composable
internal fun InactivityNotice(
    countdown: InactivityCountdown,
    onStay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    VideoOverlaySurface(modifier = modifier.testTag("inactivity-notice")) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 16.dp, end = 4.dp),
        ) {
            Text(
                // Deliberately not a live region: a screen reader announcing a
                // new number every second would be its own kind of alarm.
                text = stringResource(R.string.viewer_returning_in, countdown.remainingSeconds),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.testTag("inactivity-countdown"),
            )
            // Left to the theme's own accent rather than tinted to match the
            // sentence beside it: this is the one thing here that can be
            // pressed, and it should look like it.
            TextButton(
                onClick = onStay,
                modifier = Modifier.testTag("inactivity-stay"),
            ) {
                Text(text = stringResource(R.string.viewer_stay))
            }
        }
    }
}

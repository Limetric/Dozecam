package app.dozecam.ui.monitor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

/** Long enough to tell whether a room is settled, short enough to feel like a round. */
const val SOUND_ROTATION_INTERVAL_MS = 10_000L

/**
 * Which camera the grid is currently listening to.
 *
 * Only ever one: several rooms at once is noise, and a grid with no sound at
 * all wastes the microphones. So the sound goes round the cameras in the order
 * they are shown, a turn each.
 */
internal object SoundRotation {

    /** The camera after [current], wrapping; the first one when it has gone. */
    fun next(cameraIds: List<String>, current: String?): String? {
        if (cameraIds.isEmpty()) return null
        // indexOf returns -1 for a camera that has been switched off or deleted
        // mid-round, which lands on the first — a fresh start rather than a
        // silent turn on a camera that is no longer there.
        return cameraIds[(cameraIds.indexOf(current) + 1) % cameraIds.size]
    }
}

/**
 * The camera that should be audible right now, or null for silence.
 *
 * The current camera keeps its turn across an unrelated camera-list update, so
 * a refresh does not restart the round or cut a turn short.
 */
@Composable
internal fun rememberAudibleCameraId(
    cameraIds: List<String>,
    enabled: Boolean,
    intervalMs: Long = SOUND_ROTATION_INTERVAL_MS,
): String? {
    var audible by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(cameraIds, enabled, intervalMs) {
        if (!enabled || cameraIds.isEmpty()) {
            audible = null
            return@LaunchedEffect
        }
        if (audible !in cameraIds) audible = cameraIds.first()
        while (true) {
            delay(intervalMs)
            audible = SoundRotation.next(cameraIds, audible)
        }
    }

    // Read through the camera list as well as the flag: the frame where a
    // camera disappears must not leave a tile that is gone holding the sound.
    return audible?.takeIf { enabled && it in cameraIds }
}

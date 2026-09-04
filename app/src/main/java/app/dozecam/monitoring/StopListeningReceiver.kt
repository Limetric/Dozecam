package app.dozecam.monitoring

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.dozecam.appContainer
import app.dozecam.data.SoundMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * "Stop playing aloud" on the ongoing notification: the speaker goes quiet, the
 * monitor keeps watching.
 *
 * Deliberately not [ExitReceiver] with a smaller blast radius. The two answer
 * different alarms — one is "I do not want to hear this room", the other is
 * "I am done with Dozecam" — and someone silencing a broadcast at 2am must not
 * find in the morning that they switched the baby monitor off along with it.
 *
 * It writes the stored sound mode rather than a flag of its own, because the
 * sound mode is the switch: the viewer's button and the service both read it,
 * and a second place to say "off" would be a second place to disagree.
 *
 * A broadcast rather than an activity for the same reason the exit button is:
 * opening the viewer would put the cameras on screen at the moment the user
 * asked for less, not more.
 */
class StopListeningReceiver(
    /** Where the write runs; the system's no-arg construction gets the main thread. */
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main),
) : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // The record is cleared at once so nothing reads the room as aloud
        // while the setting travels; the service is watching the setting and
        // releases the speaker on its own, which is also what makes this safe
        // to receive when no service is running at all.
        context.appContainer.monitoringState.listeningCameraIds.value = emptySet()
        // The write outlives onReceive — a receiver's process may be reclaimed
        // the moment it returns, and a preference edit is a disk write. Null
        // when this was not the system delivering the broadcast, which only a
        // test does.
        val pending: PendingResult? = goAsync()
        scope.launch {
            try {
                context.appContainer.appSettings.update { it.copy(soundMode = SoundMode.OFF) }
            } finally {
                pending?.finish()
            }
        }
    }
}

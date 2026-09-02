package app.dozecam.monitoring

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.dozecam.appContainer

/**
 * "Stop listening" on the ongoing notification: the speaker goes quiet, the
 * monitor keeps watching.
 *
 * Deliberately not [StopMonitoringReceiver] with a smaller blast radius. The
 * two answer different alarms — one is "I do not want to hear this room", the
 * other is "I do not want to be woken at all" — and someone silencing a
 * broadcast at 2am must not find in the morning that they switched the baby
 * monitor off along with it.
 *
 * A broadcast rather than an activity for the same reason the stop button is:
 * opening the viewer would put the cameras on screen at the moment the user
 * asked for less, not more.
 */
class StopListeningReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // The service is watching this and releases the speaker on its own,
        // which is also what makes this safe to receive when no service is
        // running at all.
        context.appContainer.monitoringState.stopListening()
    }
}

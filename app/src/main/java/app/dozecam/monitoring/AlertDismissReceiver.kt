package app.dozecam.monitoring

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.dozecam.appContainer

/**
 * Swiping the alert away is a person saying they have seen it, so it silences
 * the alarm exactly as touching the viewer does. Without this, dismissing the
 * notification would leave the room ringing with nothing on screen to explain
 * why.
 */
class AlertDismissReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        context.appContainer.alertSignaler.acknowledge()
    }
}

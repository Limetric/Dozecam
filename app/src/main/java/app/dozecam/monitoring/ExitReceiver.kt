package app.dozecam.monitoring

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * "Exit" on the ongoing notification — the one control that is always within
 * reach, since the notification is all there is of Dozecam while it listens
 * with the screen off.
 *
 * Monitoring has no switch of its own any more: it runs for as long as the app
 * does, so the way to end it is to end the app. The leaving itself is
 * [MonitoringService.exit]'s — the service stopped, the shade cleared of
 * everything monitoring posted, and the request left where the viewer, which a
 * receiver cannot reach, reads it and finishes itself.
 */
class ExitReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        MonitoringService.exit(context)
    }
}

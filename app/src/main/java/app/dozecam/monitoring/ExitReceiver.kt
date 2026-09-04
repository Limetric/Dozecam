package app.dozecam.monitoring

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.dozecam.appContainer

/**
 * "Exit" on the ongoing notification — the one control that is always within
 * reach, since the notification is all there is of Dozecam while it listens
 * with the screen off.
 *
 * Monitoring has no switch of its own any more: it runs for as long as the app
 * does, so the way to end it is to end the app. The service is stopped from
 * here; the viewer, which a receiver cannot reach, finishes itself on reading
 * [MonitoringState.exitRequested].
 */
class ExitReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        context.appContainer.monitoringState.exitRequested.value = true
        MonitoringService.stop(context)
    }
}

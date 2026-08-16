package app.dozecam.monitoring

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.dozecam.appContainer

/**
 * "Stop monitoring" on the ongoing notification — the one control that is
 * always within reach, since the notification is all there is of Dozecam while
 * it listens with the screen off.
 *
 * It records the intent the way the settings switch does before stopping the
 * service. Without [MonitoringState.userStopped] the viewer would arm again the
 * moment it came back to the front, and the notification would reappear
 * seconds after the tap that ended it.
 */
class StopMonitoringReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        context.appContainer.monitoringState.userStopped.value = true
        MonitoringService.stop(context)
    }
}

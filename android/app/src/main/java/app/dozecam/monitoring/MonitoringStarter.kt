package app.dozecam.monitoring

import android.content.Context

/**
 * Starts monitoring without interrupting the viewer with permission prompts.
 * Missing alert grants are reported by the night checklist, whose fix actions
 * let the user request them when ready. Detection does not depend on them.
 */
class MonitoringStarter(private val context: Context) {
    fun start() {
        MonitoringService.start(context)
    }
}

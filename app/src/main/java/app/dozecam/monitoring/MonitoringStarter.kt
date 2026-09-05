package app.dozecam.monitoring

import android.Manifest
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import app.dozecam.appContainer
import kotlinx.coroutines.flow.StateFlow

/**
 * Starts monitoring with the grants its alert depends on. Shared by every
 * screen that can start it — the viewer, settings and onboarding — because
 * whichever one gets there first is the one that has to ask: once the service
 * is running the others skip their own permission path, and a monitor that
 * detects sound but cannot wake the screen is the failure nobody notices until
 * the one night it matters.
 *
 * Construct during activity initialisation; it registers an activity result.
 */
class MonitoringStarter(private val activity: ComponentActivity) {

    private var onStarted: (() -> Unit)? = null

    private val notificationPermission = activity.registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        // Denied only costs the notification, not detection, so start either way.
        start()
        onStarted?.invoke()
        onStarted = null
    }

    /**
     * Whether the screen should be explaining full-screen-intent access right
     * now, before Android is asked for it.
     *
     * This used to throw the user straight into a system settings screen at the
     * moment they first armed monitoring: no reason given, no idea what had
     * just happened, and — most of the time — a screen dismissed with the
     * setting untouched. A grant asked for cold is a grant refused, and this
     * one is the difference between an alert that lights a locked screen and an
     * alert that quietly becomes a banner.
     *
     * So the ask is now a sentence first, the system screen second, and the
     * result checked afterwards rather than assumed — which is what the bedtime
     * check ([ReadinessCheck.WAKE_SCREEN]) reads. Asked once per arm at most:
     * an explanation that reappeared every time the viewer came to the front
     * would be its own reason to say no.
     */
    val explainFullScreenIntent: StateFlow<Boolean>
        get() = activity.appContainer.monitoringState.explainFullScreenIntent

    /** The user has read it and is on their way; the dialog closes as Android's opens. */
    fun openFullScreenIntentSettings() {
        activity.appContainer.monitoringState.explainFullScreenIntent.value = false
        ReadinessRemedies.open(activity, ReadinessRemedy.FULL_SCREEN_INTENT_SETTINGS)
    }

    /**
     * "Not now". Monitoring is running either way — the alert is merely quieter
     * than it should be, and the readiness card is where that goes on being
     * said for as long as it stays true.
     */
    fun dismissFullScreenIntentExplanation() {
        activity.appContainer.monitoringState.explainFullScreenIntent.value = false
    }

    /**
     * Asks for anything still missing, then starts the service and runs
     * [onStarted]. The callback matters because the permission prompt is
     * asynchronous: a caller that finished itself straight after this would
     * destroy the launcher before the answer arrived, and the service would
     * never start at all.
     */
    fun startWithAlertPermissions(onStarted: () -> Unit = {}) {
        val needsNotifications = Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(
                activity,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        if (needsNotifications) {
            this.onStarted = onStarted
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            start()
            onStarted()
        }
    }

    private fun start() {
        // Monitoring first, explanation second, and never the other way round:
        // detection does not depend on this grant, and a service that waited on
        // a dialog would leave the room unlistened-to while somebody read.
        MonitoringService.start(activity)
        if (needsFullScreenIntentAccess()) {
            activity.appContainer.monitoringState.explainFullScreenIntent.value = true
        }
    }

    /**
     * Android 14+ gates full-screen intents behind special app access that
     * Play-installed apps may not receive by default. Without it the sound
     * alert degrades to a heads-up notification and cannot wake the screen.
     */
    private fun needsFullScreenIntentAccess(): Boolean {
        if (Build.VERSION.SDK_INT < 34) return false
        return !activity.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
    }
}

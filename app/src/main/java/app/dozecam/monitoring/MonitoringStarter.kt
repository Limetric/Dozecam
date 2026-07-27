package app.dozecam.monitoring

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

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
        ensureFullScreenIntentAccess()
        MonitoringService.start(activity)
    }

    /**
     * Android 14+ gates full-screen intents behind special app access that
     * Play-installed apps may not receive by default. Without it the sound
     * alert degrades to a heads-up notification and cannot wake the screen,
     * so send the user straight to the grant screen.
     */
    private fun ensureFullScreenIntentAccess() {
        if (Build.VERSION.SDK_INT < 34) return
        val manager = activity.getSystemService(NotificationManager::class.java)
        if (manager.canUseFullScreenIntent()) return
        activity.startActivity(
            Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
                .setData(Uri.fromParts("package", activity.packageName, null)),
        )
    }
}

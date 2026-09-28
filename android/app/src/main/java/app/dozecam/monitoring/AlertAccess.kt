package app.dozecam.monitoring

import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.annotation.VisibleForTesting
import androidx.core.app.NotificationManagerCompat

/**
 * Whether an alert could reach anyone, as far as the system's grants go. Both
 * can be withdrawn at any time from system settings, with nothing telling the
 * app, so the monitor asks again on every judgement rather than once at start.
 */
object AlertAccess {

    /** Overridable so a test can withdraw a grant Robolectric cannot. */
    @VisibleForTesting
    var probe: ((Context) -> Pair<Boolean, Boolean>)? = null

    /** Whether the app may post notifications at all — no card, no wake, without it. */
    fun notificationsAllowed(context: Context): Boolean =
        probe?.invoke(context)?.first
            ?: NotificationManagerCompat.from(context).areNotificationsEnabled()

    /**
     * Whether a full-screen intent may wake the screen. Android 14+ gates it
     * behind special app access; before that it is the notification
     * permission's to give.
     */
    fun screenWakeAllowed(context: Context): Boolean {
        probe?.invoke(context)?.let { return it.second }
        if (Build.VERSION.SDK_INT < 34) return true
        return try {
            context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
        } catch (_: RuntimeException) {
            // The question could not be asked; an alarm for that would be
            // crying wolf, and the starter's own check covers the grant.
            true
        }
    }
}

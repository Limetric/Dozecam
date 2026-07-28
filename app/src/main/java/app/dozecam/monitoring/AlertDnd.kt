package app.dozecam.monitoring

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.content.edit

/**
 * The narrow slice of Do Not Disturb the alarm is allowed to touch. An interface
 * so the alarm's own schedule can be tested without a system this global.
 */
interface DndOverride {
    fun beginBypass(enabled: Boolean)
    fun endBypass()
}

/**
 * The Do Not Disturb override, and the honest scope of it.
 *
 * Alarm-usage audio already passes Do Not Disturb's default priority rules, so
 * the grant buys exactly one thing: it gets through *total silence*, the mode
 * that mutes alarms too. Everything here is therefore narrow on purpose —
 * nothing is touched unless the phone is in that one mode.
 *
 * The prior filter is written down before it is changed, and restored on the
 * next start if the process dies mid-alarm. Leaving someone's phone
 * permanently out of the mode they put it in would be a worse bug than the
 * alert being missed.
 */
class AlertDnd(context: Context) : DndOverride {

    private val appContext = context.applicationContext

    private val prefs =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val manager: NotificationManager
        get() = appContext.getSystemService(NotificationManager::class.java)

    val isGranted: Boolean
        get() = manager.isNotificationPolicyAccessGranted

    override fun beginBypass(enabled: Boolean) {
        if (!enabled || !isGranted) return
        val current = manager.currentInterruptionFilter
        if (current != NotificationManager.INTERRUPTION_FILTER_NONE) return
        // Written synchronously and *before* the change: a crash in the
        // microseconds between the two must leave the record behind, not the
        // altered setting on its own.
        prefs.edit(commit = true) { putInt(KEY_PRIOR_FILTER, current) }
        setFilter(NotificationManager.INTERRUPTION_FILTER_ALARMS)
    }

    /**
     * Puts back whatever was there. Safe to call when nothing was changed, and
     * called again at service start so a process death during an alarm heals on
     * the next launch rather than the next alert.
     */
    override fun endBypass() {
        val prior = prefs.getInt(KEY_PRIOR_FILTER, UNSET)
        if (prior == UNSET) return
        // The record is kept when the restore could not happen. Access revoked
        // mid-alarm leaves us owing the user a mode we are momentarily unable to
        // give back; forgetting the debt here would strand them in it, while
        // holding it costs nothing until the access returns.
        if (setFilter(prior)) prefs.edit { remove(KEY_PRIOR_FILTER) }
    }

    private fun setFilter(filter: Int): Boolean {
        if (!isGranted) return false
        return try {
            manager.setInterruptionFilter(filter)
            true
        } catch (_: SecurityException) {
            // Access revoked between the check and the call.
            false
        }
    }

    companion object {
        private const val PREFS_NAME = "dozecam_dnd"
        private const val KEY_PRIOR_FILTER = "prior_interruption_filter"
        private const val UNSET = -1

        /** Where the user grants — and revokes — the override. */
        fun grantIntent(): Intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
    }
}

package app.dozecam.monitoring

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * Where a remedy sends the user, when it sends them anywhere at all.
 *
 * Most of what stops an alert waking someone is not ours to change: a
 * permission, a ringer, a Do Not Disturb profile, a battery optimisation
 * exemption. The most an app can do is open the exact screen that holds the
 * switch, which is the difference between a check that reports a problem and
 * one that fixes it.
 *
 * Battery optimisation is opened as the *list* rather than asked for with
 * `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` on purpose. The direct request
 * needs the `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` permission, which Google
 * Play restricts to a short list of app types, and a monitor that could not be
 * published would keep nobody's night. The list screen needs no permission and
 * lands two taps away.
 */
object ReadinessRemedies {

    /**
     * The system screen for [remedy], or null when it is not a system screen at
     * all — three of them are the app's own settings, its own service, and its
     * own camera list, and those belong to the caller.
     *
     * Every intent here can be absent on some device: an OEM that ships no Do
     * Not Disturb screen, a build with notification settings behind another
     * name. So each has a fallback, and the last fallback is always this app's
     * own details page, which every Android has and from which every one of
     * these is reachable by hand.
     */
    fun intents(context: Context, remedy: ReadinessRemedy): List<Intent> = when (remedy) {
        ReadinessRemedy.NOTIFICATION_SETTINGS -> listOf(
            // Straight to the alert channel rather than the app's notification
            // page: the channel is the switch that is off, and the page it
            // lives on lists several.
            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .putExtra(
                    Settings.EXTRA_CHANNEL_ID,
                    MonitoringNotifications.ALERT_CHANNEL_ID,
                ),
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
            appDetails(context),
        )

        ReadinessRemedy.FULL_SCREEN_INTENT_SETTINGS ->
            // The screen only exists where the gate does.
            if (Build.VERSION.SDK_INT >= 34) {
                listOf(
                    Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
                        .setData(packageUri(context)),
                    appDetails(context),
                )
            } else {
                emptyList()
            }

        ReadinessRemedy.SOUND_SETTINGS -> listOf(Intent(Settings.ACTION_SOUND_SETTINGS))

        ReadinessRemedy.DO_NOT_DISTURB_SETTINGS -> listOf(
            // The Do Not Disturb page itself, which is where the mode is turned
            // off. The action has no constant in the public SDK, hence the
            // literal — and hence the fallbacks, since a device that does not
            // answer it simply moves to the next.
            Intent(ACTION_ZEN_MODE_SETTINGS),
            // Priority exceptions. The wrong page for switching total silence
            // off, and the right one for a profile that is merely filtering, so
            // it follows rather than leads.
            Intent(Settings.ACTION_ZEN_MODE_PRIORITY_SETTINGS),
            Intent(Settings.ACTION_SOUND_SETTINGS),
        )

        ReadinessRemedy.BATTERY_SETTINGS -> listOf(
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            appDetails(context),
        )

        ReadinessRemedy.NONE,
        ReadinessRemedy.REQUEST_NOTIFICATIONS,
        ReadinessRemedy.TURN_ALERTS_ON,
        ReadinessRemedy.TURN_CHIME_ON,
        ReadinessRemedy.START_MONITORING,
        ReadinessRemedy.GRANT_LOCAL_NETWORK,
        ReadinessRemedy.CAMERA_SETTINGS,
        -> emptyList()
    }

    /**
     * Opens the first of [intents] the device actually has. Returns false when
     * none of them resolved, which the caller must say out loud rather than
     * leave as a button that does nothing.
     */
    fun open(context: Context, remedy: ReadinessRemedy): Boolean =
        intents(context, remedy).any { intent ->
            runCatching { context.startActivity(intent) }.isSuccess
        }

    /**
     * Where a refused notification permission can still be given: the app's own
     * notification page, whose master switch *is* the `POST_NOTIFICATIONS`
     * grant on Android 13 and later.
     *
     * Deliberately not [ReadinessRemedy.NOTIFICATION_SETTINGS], which goes
     * straight to the alert channel — the right destination for a channel
     * switched off or turned down, and the wrong one entirely for a permission
     * that was never granted, since the channel page cannot give it back.
     */
    fun openAppNotifications(context: Context): Boolean =
        listOf(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
            appDetails(context),
        ).any { intent -> runCatching { context.startActivity(intent) }.isSuccess }

    /** `Settings.ACTION_ZEN_MODE_SETTINGS`, which the public SDK does not name. */
    private const val ACTION_ZEN_MODE_SETTINGS = "android.settings.ZEN_MODE_SETTINGS"

    private fun appDetails(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(packageUri(context))

    private fun packageUri(context: Context): Uri =
        Uri.fromParts("package", context.packageName, null)
}

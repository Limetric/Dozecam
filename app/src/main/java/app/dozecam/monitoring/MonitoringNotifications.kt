package app.dozecam.monitoring

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.text.format.DateFormat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.dozecam.MainActivity
import app.dozecam.R
import java.util.Date

object MonitoringNotifications {

    const val STATUS_CHANNEL_ID = "monitoring_status"

    // v2: silent channel — chime/vibration are app-side so the in-app toggles
    // actually control them (channel settings are immutable once created).
    const val ALERT_CHANNEL_ID = "sound_alerts_2"
    const val STATUS_NOTIFICATION_ID = 1
    const val ALERT_NOTIFICATION_ID = 2

    private const val REQUEST_ALERT_FULL_SCREEN = 0
    private const val REQUEST_ALERT_TAP = 1
    private const val REQUEST_STATUS_TAP = 2
    private const val REQUEST_STATUS_STOP = 3
    private const val REQUEST_STATUS_STOP_LISTENING = 4

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.deleteNotificationChannel("sound_alerts")
        manager.createNotificationChannel(
            NotificationChannel(
                STATUS_CHANNEL_ID,
                context.getString(R.string.channel_status_name),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        manager.createNotificationChannel(
            NotificationChannel(
                ALERT_CHANNEL_ID,
                context.getString(R.string.channel_alerts_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.channel_alerts_description)
                setSound(null, null)
                enableVibration(false)
            },
        )
    }

    /**
     * The ongoing notification, and — while the phone listens with its screen
     * off — the whole of Dozecam's presence. So it carries the two things a
     * person reaching for it wants: the way back into the viewer, and the way
     * to stop.
     *
     * While the healthy listening line is showing it also carries proof of
     * life — [levelBucket], the loudest camera's level as a small bar, and
     * [checkedAtMs], the minute this was actually posted — so a glance can
     * tell a quiet night from a stale notification. Deliberately not a
     * chronometer: System UI would keep one ticking over a dead process,
     * which is exactly the false comfort this exists to rule out.
     *
     * [aloud] adds the way to silence a room that is coming out of the
     * speaker. Only the way to *stop*: starting means choosing which room, and
     * a notification cannot ask. It is also the one direction that has to be
     * within reach of someone who did not expect it — a phone broadcasting a
     * bedroom needs an off switch on the same surface that admits to it.
     */
    fun statusNotification(
        context: Context,
        text: String,
        levelBucket: Int? = null,
        checkedAtMs: Long? = null,
        aloud: Boolean = false,
    ): Notification =
        NotificationCompat.Builder(context, STATUS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notification_monitoring_title))
            .setContentText(text)
            .apply {
                if (levelBucket != null) {
                    setProgress(StatusHeartbeat.LEVEL_BUCKETS, levelBucket, false)
                }
                if (checkedAtMs != null) {
                    setSubText(
                        context.getString(
                            R.string.monitoring_status_checked,
                            DateFormat.getTimeFormat(context).format(Date(checkedAtMs)),
                        ),
                    )
                }
            }
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            // Deliberately the plain viewer rather than an alert intent: this is
            // nobody being woken, so it names no camera and carries none of the
            // secrets that would let the nursery appear over a lock screen.
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    REQUEST_STATUS_TAP,
                    MainActivity.viewerIntent(context),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            // A broadcast rather than an activity: stopping is the whole of what
            // the button means, and routing it through the viewer would put the
            // cameras on screen at the moment the user asked for the opposite.
            .addAction(
                R.drawable.ic_stop,
                context.getString(R.string.notification_monitoring_stop),
                PendingIntent.getBroadcast(
                    context,
                    REQUEST_STATUS_STOP,
                    Intent(context, StopMonitoringReceiver::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .apply {
                if (!aloud) return@apply
                addAction(
                    R.drawable.ic_volume_off,
                    context.getString(R.string.notification_stop_listening),
                    PendingIntent.getBroadcast(
                        context,
                        REQUEST_STATUS_STOP_LISTENING,
                        Intent(context, StopListeningReceiver::class.java),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
            }
            .build()

    /**
     * High-priority full-screen alert: wakes the display and surfaces the
     * monitor over the lock screen when sound is detected.
     */
    fun alertNotification(
        context: Context,
        cameraId: String,
        cameraName: String,
        /**
         * False for the one room already coming out of the speaker, where
         * listen mode has been telling the user about it continuously. The rest
         * of the alert is unchanged — the chime, the vibration, the tap that
         * opens the camera — but a bedroom whose occupant is already hearing
         * the nursery does not also need lighting up at 3am.
         */
        wakeScreen: Boolean = true,
    ): Notification {
        // Built only when it will be used: minting one spends nothing, but
        // MainActivity.alertIntent stamps a single-use wake token into it, and
        // an alert that has decided not to wake the screen has no business
        // carrying the right to.
        val fullScreenIntent = if (wakeScreen) {
            PendingIntent.getActivity(
                context,
                REQUEST_ALERT_FULL_SCREEN,
                MainActivity.alertIntent(context, cameraId),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        } else {
            null
        }
        return NotificationCompat.Builder(context, ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notification_alert_title, cameraName))
            .setContentText(context.getString(R.string.notification_alert_text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setDeleteIntent(
                PendingIntent.getBroadcast(
                    context,
                    0,
                    Intent(context, AlertDismissReceiver::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            // Left off entirely rather than posted with a suppressed flag:
            // without one Android treats this as an ordinary high-priority
            // notification, which is exactly what a room the user is already
            // hearing deserves — a heads-up if the screen happens to be on,
            // and a quiet entry in the shade if it is not.
            .apply { fullScreenIntent?.let { setFullScreenIntent(it, true) } }
            // When Android suppresses the full-screen launch (screen already
            // on, or 14+ special access denied) the heads-up fallback must
            // still open the live view on tap.
            //
            // A separate PendingIntent, and a separate request code, because the
            // tap has to be distinguishable from the unattended launch: the
            // person doing the tapping is acknowledging the alert, and their tap
            // lands in System UI rather than in our window, so nothing else will
            // tell us they arrived. The request codes must differ or these two
            // would be the same PendingIntent — equality ignores extras.
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    REQUEST_ALERT_TAP,
                    MainActivity.alertTapIntent(context, cameraId),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()
    }

    fun postAlert(
        context: Context,
        cameraId: String,
        cameraName: String,
        wakeScreen: Boolean = true,
    ) {
        val manager = NotificationManagerCompat.from(context)
        try {
            manager.notify(
                ALERT_NOTIFICATION_ID,
                alertNotification(context, cameraId, cameraName, wakeScreen),
            )
        } catch (_: SecurityException) {
            // Notification permission revoked mid-run; monitoring continues,
            // the status notification (FGS-exempt) still reflects the alert.
        }
    }
}

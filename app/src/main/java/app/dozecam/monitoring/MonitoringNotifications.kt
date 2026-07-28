package app.dozecam.monitoring

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.dozecam.MainActivity
import app.dozecam.R

object MonitoringNotifications {

    const val STATUS_CHANNEL_ID = "monitoring_status"

    // v2: silent channel — chime/vibration are app-side so the in-app toggles
    // actually control them (channel settings are immutable once created).
    const val ALERT_CHANNEL_ID = "sound_alerts_2"
    const val STATUS_NOTIFICATION_ID = 1
    const val ALERT_NOTIFICATION_ID = 2

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

    fun statusNotification(context: Context, text: String): Notification =
        NotificationCompat.Builder(context, STATUS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notification_monitoring_title))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

    /**
     * High-priority full-screen alert: wakes the display and surfaces the
     * monitor over the lock screen when sound is detected.
     */
    fun alertNotification(context: Context, cameraId: String, cameraName: String): Notification {
        val fullScreenIntent = PendingIntent.getActivity(
            context,
            0,
            MainActivity.alertIntent(context, cameraId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
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
            .setFullScreenIntent(fullScreenIntent, true)
            // When Android suppresses the full-screen launch (screen already
            // on, or 14+ special access denied) the heads-up fallback must
            // still open the live view on tap.
            .setContentIntent(fullScreenIntent)
            .build()
    }

    fun postAlert(context: Context, cameraId: String, cameraName: String) {
        val manager = NotificationManagerCompat.from(context)
        try {
            manager.notify(ALERT_NOTIFICATION_ID, alertNotification(context, cameraId, cameraName))
        } catch (_: SecurityException) {
            // Notification permission revoked mid-run; monitoring continues,
            // the status notification (FGS-exempt) still reflects the alert.
        }
    }
}

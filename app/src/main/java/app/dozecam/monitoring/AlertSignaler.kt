package app.dozecam.monitoring

import android.content.Context
import android.media.RingtoneManager
import android.os.VibrationEffect
import android.os.VibratorManager
import app.dozecam.data.AppSettings

/**
 * In-app chime/vibration for sound alerts. The alert notification channel is
 * deliberately silent so these user-toggleable effects are the single source
 * of alert noise (post-O channels cannot vary sound per notification).
 */
class AlertSignaler(private val context: Context) {

    fun signal(settings: AppSettings) {
        if (settings.alertVibrate) {
            val vibrator =
                (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager)
                    .defaultVibrator
            vibrator.vibrate(
                VibrationEffect.createWaveform(longArrayOf(0, 300, 150, 300), -1),
            )
        }
        if (settings.alertChime) {
            RingtoneManager.getRingtone(
                context,
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
            )?.play()
        }
    }
}

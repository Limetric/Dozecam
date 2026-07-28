package app.dozecam.monitoring

import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.provider.Settings
import androidx.core.net.toUri
import app.dozecam.R
import app.dozecam.data.AppSettings

/**
 * Which sound an alert makes, and how the user changes it.
 *
 * Both answers are alarm tones, never notification tones. That distinction is
 * the whole complaint this feature answers: a message tone is the one sound a
 * phone's owner is trained to sleep through, and on silent it does not play at
 * all.
 */
object AlarmSound {

    /** The chosen tone, or the phone's own alarm sound if no choice has been made. */
    fun uriFor(settings: AppSettings): Uri =
        settings.alertSoundUri?.toUri()
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: Settings.System.DEFAULT_ALARM_ALERT_URI

    /**
     * The system picker, asked for alarm tones so the list a parent chooses from
     * is the one their message tone is not in.
     */
    fun pickerIntent(context: Context, current: String?): Intent =
        Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
            .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
            .putExtra(
                RingtoneManager.EXTRA_RINGTONE_TITLE,
                context.getString(R.string.setting_alert_sound),
            )
            // No "Silent" entry: an alert that plays nothing is what the chime
            // switch is for, and a silent choice here would be indistinguishable
            // from a broken one.
            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
            .putExtra(
                RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI,
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
            )
            .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, current?.toUri())
}

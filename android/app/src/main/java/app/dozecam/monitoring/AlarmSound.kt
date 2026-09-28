package app.dozecam.monitoring

import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.provider.Settings
import androidx.core.net.toUri
import app.dozecam.R
import app.dozecam.data.AppSettings
import java.io.IOException

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
    fun uriFor(settings: AppSettings): Uri = settings.alertSoundUri?.toUri() ?: default

    /**
     * The phone's own alarm sound: the one tone that needs no permission and no
     * storage to be mounted, and so the only safe thing to fall back to.
     */
    val default: Uri
        get() = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: Settings.System.DEFAULT_ALARM_ALERT_URI

    /**
     * The monitoring failure tone: the beep a physical monitor makes when it
     * loses the other unit. Bundled rather than chosen, and deliberately not
     * the alert sound, so the two are never confused at 3am — one means a
     * room needs someone, the other means nobody would be told if it did.
     */
    fun failure(context: Context): Uri =
        "android.resource://${context.packageName}/${R.raw.monitoring_failure}".toUri()

    /**
     * Whether a tone can actually be opened by this app.
     *
     * The picker hands back bare `content://media/external/...` URIs for a file
     * the user dropped in `Alarms/` themselves, with no permission grant
     * attached and no `READ_MEDIA_AUDIO` on our side to fall back on. Opening it
     * is the only way to find out, and it is far better to find out in daylight,
     * at the moment of choosing, than at 3am.
     */
    fun isPlayable(context: Context, uri: Uri): Boolean =
        try {
            context.contentResolver.openInputStream(uri).use { it != null }
        } catch (_: SecurityException) {
            false
        } catch (_: IOException) {
            false
        } catch (_: IllegalArgumentException) {
            // A malformed or unroutable URI; unusable for the same purposes.
            false
        }

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

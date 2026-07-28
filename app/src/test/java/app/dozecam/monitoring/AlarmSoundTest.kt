package app.dozecam.monitoring

import android.content.Context
import android.media.RingtoneManager
import androidx.test.core.app.ApplicationProvider
import app.dozecam.data.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * An alert must never sound like a message. That is the failure this whole
 * feature answers, and it is one line away from coming back.
 */
@RunWith(RobolectricTestRunner::class)
class AlarmSoundTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `with no choice made an alert uses an alarm tone, not the notification tone`() {
        val resolved = AlarmSound.uriFor(AppSettings(alertSoundUri = null))

        assertNotEquals(
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
            resolved,
        )
        assertEquals(
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: android.provider.Settings.System.DEFAULT_ALARM_ALERT_URI,
            resolved,
        )
    }

    @Test
    fun `a chosen sound is the one that plays`() {
        val chosen = "content://media/internal/audio/media/9"

        assertEquals(chosen, AlarmSound.uriFor(AppSettings(alertSoundUri = chosen)).toString())
    }

    @Test
    fun `the picker offers alarms rather than notification tones`() {
        val intent = AlarmSound.pickerIntent(context, current = null)

        assertEquals(RingtoneManager.ACTION_RINGTONE_PICKER, intent.action)
        assertEquals(
            RingtoneManager.TYPE_ALARM,
            intent.getIntExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, -1),
        )
    }

    /** A silent choice would be indistinguishable from a broken alert. */
    @Test
    fun `the picker does not offer silence`() {
        val intent = AlarmSound.pickerIntent(context, current = null)

        assertEquals(
            false,
            intent.getBooleanExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true),
        )
    }

    @Test
    fun `the picker opens on the sound already chosen`() {
        val chosen = "content://media/internal/audio/media/9"

        val intent = AlarmSound.pickerIntent(context, current = chosen)

        assertEquals(
            chosen,
            intent.getParcelableExtra<android.net.Uri>(
                RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
            )?.toString(),
        )
    }
}

package app.dozecam.monitoring

import android.content.Context
import android.media.RingtoneManager
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import app.dozecam.data.AppSettings
import java.io.ByteArrayInputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

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

    /**
     * The picker hands back bare media URIs for anything the user added to
     * `Alarms/` themselves, with no grant to read them by. Stored unchecked,
     * that is an alert that fails at 3am and nowhere else.
     */
    @Test
    fun `a sound that cannot be opened is not playable`() {
        val unreadable = "content://media/external/audio/media/99999".toUri()

        assertEquals(false, AlarmSound.isPlayable(context, unreadable))
    }

    /**
     * The other half of the check, and the one that matters more: a probe that
     * said no to everything would reject every sound the user picked, which is a
     * worse bug than the one it guards against.
     */
    @Test
    fun `a sound that can be opened is playable`() {
        val readable = "content://app.dozecam.test/alarm.ogg".toUri()
        shadowOf(context.contentResolver)
            .registerInputStream(readable, ByteArrayInputStream(ByteArray(16)))

        assertEquals(true, AlarmSound.isPlayable(context, readable))
    }

    /** A tone on storage that has since gone away fails the same way. */
    @Test
    fun `a sound whose file is gone is not playable`() {
        val missing = File(context.cacheDir, "no-such-alarm.ogg").toUri()

        assertEquals(false, AlarmSound.isPlayable(context, missing))
    }

    /** Whatever else fails, there has to be something left to make a noise with. */
    @Test
    fun `the fallback is the phone's own alarm tone`() {
        assertEquals(
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: android.provider.Settings.System.DEFAULT_ALARM_ALERT_URI,
            AlarmSound.default,
        )
        assertNotEquals(
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
            AlarmSound.default,
        )
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

package app.dozecam.monitoring

import android.content.Context
import android.media.AudioAttributes
import android.os.VibratorManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class SystemAlarmVibratorTest {

    /**
     * The bug this whole feature exists to fix: the old tone was played with
     * *notification* usage, which a phone on silent — how most phones spend the
     * night — makes no sound for at all.
     */
    @Test
    fun `alert audio is declared as an alarm, never as a notification`() {
        assertEquals(AudioAttributes.USAGE_ALARM, AlarmAudio.ATTRIBUTES.usage)
        assertEquals(
            AudioAttributes.CONTENT_TYPE_SONIFICATION,
            AlarmAudio.ATTRIBUTES.contentType,
        )
    }

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun vibratorShadow() = shadowOf(
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager)
            .defaultVibrator,
    )

    @Test
    fun `pulses the alert waveform`() {
        SystemAlarmVibrator(context).pulse()

        assertArrayEquals(SystemAlarmVibrator.PATTERN, vibratorShadow().pattern)
    }

    @Test
    fun `cancelling stops the vibration`() {
        val vibrator = SystemAlarmVibrator(context)

        vibrator.pulse()
        vibrator.cancel()

        assertEquals(false, vibratorShadow().isVibrating)
    }

    /**
     * The phone this has to reach is face-down on a nightstand, or under a
     * pillow — not in a hand. Every buzz is at full strength, and there are more
     * of them than the in-hand waveform this replaced.
     */
    @Test
    fun `the waveform is long and at full strength`() {
        val buzzes = SystemAlarmVibrator.AMPLITUDES.filter { it > 0 }

        assertEquals(
            SystemAlarmVibrator.PATTERN.size,
            SystemAlarmVibrator.AMPLITUDES.size,
        )
        assertTrue("expected at least three buzzes", buzzes.size >= 3)
        assertTrue("every buzz should be at full amplitude", buzzes.all { it == 255 })
        assertTrue(
            "each buzz should outlast the 300ms in-hand one",
            SystemAlarmVibrator.PATTERN.filterIndexed { index, _ ->
                SystemAlarmVibrator.AMPLITUDES[index] > 0
            }.all { it >= 500 },
        )
    }
}

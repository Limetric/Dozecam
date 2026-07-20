package app.dozecam.monitoring

import android.content.Context
import android.os.VibratorManager
import androidx.test.core.app.ApplicationProvider
import app.dozecam.data.AppSettings
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class AlertSignalerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun vibratorShadow() = shadowOf(
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager)
            .defaultVibrator,
    )

    @Test
    fun `vibrates with the alert waveform when the vibrate setting is on`() {
        AlertSignaler(context).signal(AppSettings(alertVibrate = true, alertChime = false))

        assertArrayEquals(longArrayOf(0, 300, 150, 300), vibratorShadow().pattern)
    }

    @Test
    fun `does not vibrate when the vibrate setting is off`() {
        AlertSignaler(context).signal(AppSettings(alertVibrate = false, alertChime = false))

        assertNull(vibratorShadow().pattern)
    }
}

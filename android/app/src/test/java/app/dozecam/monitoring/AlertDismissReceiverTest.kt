package app.dozecam.monitoring

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import app.dozecam.appContainer
import app.dozecam.data.AppSettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AlertDismissReceiverTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** Swiping the alert away is a person saying they have seen it. */
    @Test
    fun `dismissing the alert silences the alarm`() {
        val signaler = context.appContainer.alertSignaler
        signaler.signal("cam-a", AppSettings(alertChime = false, alertVibrate = false))
        assertTrue(signaler.isAlarming)

        AlertDismissReceiver().onReceive(context, Intent())

        assertFalse(signaler.isAlarming)
    }

    @Test
    fun `a dismissal with no alarm sounding is harmless`() {
        AlertDismissReceiver().onReceive(context, Intent())

        assertFalse(context.appContainer.alertSignaler.isAlarming)
    }
}

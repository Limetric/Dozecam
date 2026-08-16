package app.dozecam.monitoring

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import app.dozecam.appContainer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class StopMonitoringReceiverTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `the notification action stops the service`() {
        StopMonitoringReceiver().onReceive(context, Intent())

        val stopped = shadowOf(context as Application).nextStoppedService
        assertEquals(MonitoringService::class.java.name, stopped.component?.className)
    }

    /**
     * A tap here is as deliberate as the settings switch, and has to be
     * remembered as such: without it the viewer would arm again the next time
     * it came to the front, and the notification the user just ended would be
     * back within seconds.
     */
    @Test
    fun `stopping from the notification is remembered as deliberate`() {
        val state = context.appContainer.monitoringState
        state.userStopped.value = false

        StopMonitoringReceiver().onReceive(context, Intent())

        assertTrue(state.userStopped.value)
    }
}

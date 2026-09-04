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
class ExitReceiverTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `the notification action stops the service`() {
        ExitReceiver().onReceive(context, Intent())

        val stopped = shadowOf(context as Application).nextStoppedService
        assertEquals(MonitoringService::class.java.name, stopped.component?.className)
    }

    /**
     * Exit means the whole app, and a receiver cannot reach a viewer that may
     * still be sitting in the background. So it leaves the request where the
     * viewer will read it and finish itself.
     */
    @Test
    fun `exiting from the notification asks the viewer to go too`() {
        val state = context.appContainer.monitoringState
        state.exitRequested.value = false

        ExitReceiver().onReceive(context, Intent())

        assertTrue(state.exitRequested.value)
    }
}

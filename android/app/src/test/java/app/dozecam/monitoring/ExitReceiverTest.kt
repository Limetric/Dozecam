package app.dozecam.monitoring

import android.app.Application
import android.app.NotificationManager
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

    /**
     * Exiting is the app going away: the notices it posted go with it. The
     * unplugged one in particular would otherwise outlive the service — it is
     * not the service's own card, so stopping the service does not take it
     * down.
     */
    @Test
    fun `exiting clears the notifications monitoring left behind`() {
        MonitoringNotifications.ensureChannels(context)
        MonitoringNotifications.postUnplugged(context, 64)
        MonitoringNotifications.postAlert(context, "a", "Nursery")
        val manager = context.getSystemService(NotificationManager::class.java)
        assertEquals(2, shadowOf(manager).size())

        ExitReceiver().onReceive(context, Intent())

        assertEquals(0, shadowOf(manager).size())
    }

    /**
     * A pause is for tonight. Leaving is when it ends, so the next open
     * watches every room again rather than a forgotten few.
     */
    @Test
    fun `exiting brings every paused camera back`() {
        val state = context.appContainer.monitoringState
        state.pause("a")
        state.pause("b")

        ExitReceiver().onReceive(context, Intent())

        assertTrue(state.pausedCameraIds.value.isEmpty())
    }
}

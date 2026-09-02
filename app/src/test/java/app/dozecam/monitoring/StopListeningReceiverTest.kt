package app.dozecam.monitoring

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import app.dozecam.appContainer
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class StopListeningReceiverTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `the notification action silences the speaker`() {
        val state = context.appContainer.monitoringState
        state.listenRequest.value = "a"
        state.listeningCameraId.value = "a"

        StopListeningReceiver().onReceive(context, Intent())

        assertNull(state.listenRequest.value)
        // Both, not just the ask: a target left standing would be picked
        // straight back up the next time the speaker came free.
        assertNull(state.listeningCameraId.value)
    }

    /**
     * The two notification actions answer different alarms. "I do not want to
     * hear this room" is not "I do not want to be woken at all", and someone
     * silencing a broadcast at 2am must not find in the morning that they
     * switched the baby monitor off with it.
     */
    @Test
    fun `silencing the speaker leaves the monitor running`() {
        context.appContainer.monitoringState.listenRequest.value = "a"

        StopListeningReceiver().onReceive(context, Intent())

        assertNull(shadowOf(context as Application).nextStoppedService)
    }
}

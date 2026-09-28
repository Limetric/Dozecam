package app.dozecam.monitoring

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import app.dozecam.appContainer
import app.dozecam.data.SoundMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class StopListeningReceiverTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `the notification action silences the speaker`() = runTest {
        val container = context.appContainer
        container.appSettings.update { it.copy(soundMode = SoundMode.ALL_ALOUD) }
        container.monitoringState.listeningCameraIds.value = setOf("a")

        StopListeningReceiver(backgroundScope).onReceive(context, Intent())

        // The stored mode, because it is the switch: the viewer's button and
        // the service both read it, and a target left standing would be picked
        // straight back up the next time the speaker came free.
        val settled = container.appSettings.settings.first { it.soundMode == SoundMode.OFF }
        assertEquals(SoundMode.OFF, settled.soundMode)
        assertEquals(emptySet<String>(), container.monitoringState.listeningCameraIds.value)
    }

    /**
     * The two notification actions answer different alarms. "I do not want to
     * hear this room" is not "I am done with Dozecam", and someone silencing a
     * broadcast at 2am must not find in the morning that they switched the
     * baby monitor off with it.
     */
    @Test
    fun `silencing the speaker leaves the monitor running`() = runTest {
        context.appContainer.appSettings.update { it.copy(soundMode = SoundMode.ALL_ALOUD) }

        StopListeningReceiver(backgroundScope).onReceive(context, Intent())
        context.appContainer.appSettings.settings.first { it.soundMode == SoundMode.OFF }

        assertNull(shadowOf(context as Application).nextStoppedService)
    }
}

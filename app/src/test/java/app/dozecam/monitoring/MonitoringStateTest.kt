package app.dozecam.monitoring

import app.dozecam.audio.SoundDetector
import app.dozecam.player.ConnectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MonitoringStateTest {

    private val state = MonitoringState()

    @Test
    fun `a switched-on camera arms the monitor on its own`() {
        assertTrue(state.shouldAutoArm(enabledCameraCount = 1))
    }

    @Test
    fun `nothing switched on means nothing to arm`() {
        assertFalse(state.shouldAutoArm(enabledCameraCount = 0))
    }

    @Test
    fun `an already-running monitor is not started again`() {
        state.serviceRunning.value = true

        assertFalse(state.shouldAutoArm(enabledCameraCount = 2))
    }

    @Test
    fun `a deliberate stop survives coming back to the viewer`() {
        state.userStopped.value = true

        // This is what stops a rotation from re-arming what was just switched
        // off; only a fresh process, or switching it back on, clears it.
        assertFalse(state.shouldAutoArm(enabledCameraCount = 2))
    }

    @Test
    fun `switching monitoring back on re-arms it`() {
        state.userStopped.value = true
        state.userStopped.value = false

        assertTrue(state.shouldAutoArm(enabledCameraCount = 1))
    }

    @Test
    fun `per-camera updates do not touch the others`() {
        state.put(CameraMonitorState("a", "Nursery"))
        state.put(CameraMonitorState("b", "Play room"))

        state.update("a") { it.copy(level = 0.3f, connection = ConnectionState.Live) }

        assertEquals(0.3f, state.cameras.value.getValue("a").level)
        assertEquals(0f, state.cameras.value.getValue("b").level)
        assertEquals(ConnectionState.Connecting, state.cameras.value.getValue("b").connection)
    }

    @Test
    fun `an event for a camera that stopped being monitored is dropped`() {
        state.put(CameraMonitorState("a", "Nursery"))
        state.remove("a")

        // A level callback in flight when a camera is switched off must not
        // resurrect it into the status line.
        state.update("a") { it.copy(phase = SoundDetector.Phase.TRIGGERED) }

        assertTrue(state.cameras.value.isEmpty())
    }

    @Test
    fun `clearing drops every camera`() {
        state.put(CameraMonitorState("a", "Nursery"))
        state.put(CameraMonitorState("b", "Play room"))

        state.clear()

        assertTrue(state.cameras.value.isEmpty())
        assertNull(state.cameras.value["a"])
    }
}

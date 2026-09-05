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

    /**
     * Settings re-arms the instant it sees the service go, and an exit from
     * the notification stops the service before the screens have finished
     * themselves. The request has to hold the door until they have.
     */
    @Test
    fun `an exit in flight is not re-armed over`() {
        state.exitRequested.value = true

        assertFalse(state.shouldAutoArm(enabledCameraCount = 1))
    }

    /**
     * There is no switch to have left off. A monitor that was exited arms
     * again the next time the viewer opens, because the exit is what ended it
     * and opening the app is what asks for it back.
     */
    @Test
    fun `a stopped monitor arms again on the next open`() {
        state.serviceRunning.value = true
        state.serviceRunning.value = false

        assertTrue(state.shouldAutoArm(enabledCameraCount = 1))
    }

    @Test
    fun `per-camera updates do not touch the others`() {
        state.put(CameraMonitorState("a", "Nursery"))
        state.put(CameraMonitorState("b", "Play room"))

        state.update("a") { it.copy(level = 0.3f, connection = ConnectionState.Live) }

        assertEquals(0.3f, state.cameras.value.getValue("a").level)
        // Unheard, not silent: no level has been decoded for this camera yet.
        assertNull(state.cameras.value.getValue("b").level)
        assertEquals(ConnectionState.Connecting, state.cameras.value.getValue("b").connection)
    }

    @Test
    fun `a dropped connection takes its level with it`() {
        state.put(CameraMonitorState("a", "Nursery"))
        state.update("a") { it.copy(level = 0.3f, connection = ConnectionState.Live) }

        state.update("a") { it.withConnection(ConnectionState.Reconnecting(attempt = 1)) }

        // The 0.3 was measured on a stream that no longer exists; carrying it
        // into the next connection would report a room nobody is hearing.
        assertNull(state.cameras.value.getValue("a").level)
    }

    @Test
    fun `going live does not invent a level`() {
        state.put(CameraMonitorState("a", "Nursery"))

        // A player can reach Live off its clock alone, before the first PCM
        // buffer is decoded.
        state.update("a") { it.withConnection(ConnectionState.Live) }

        assertNull(state.cameras.value.getValue("a").level)
        assertEquals(ConnectionState.Live, state.cameras.value.getValue("a").connection)
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

    /**
     * A level and the moment it arrived are evidence about one connection.
     * Carrying the timestamp into a connection that has not decoded anything
     * would make a camera that just dropped look freshly heard to the bedtime
     * check — which is the one lie that check exists to catch.
     */
    @Test
    fun `losing the connection forgets when the room was last heard`() {
        val state = CameraMonitorState("a", "Nursery", level = 0.4f, lastAudioAtMs = 5_000L)
            .withConnection(ConnectionState.Live)
        assertEquals(5_000L, state.lastAudioAtMs)

        val dropped = state.withConnection(ConnectionState.Reconnecting(1))

        assertNull(dropped.lastAudioAtMs)
        assertNull(dropped.level)
    }

    @Test
    fun `a live connection keeps the moment its audio arrived`() {
        val state = CameraMonitorState("a", "Nursery", level = 0.4f, lastAudioAtMs = 5_000L)
            .withConnection(ConnectionState.Live)

        assertEquals(5_000L, state.lastAudioAtMs)
        assertTrue(state.isLive)
    }
}

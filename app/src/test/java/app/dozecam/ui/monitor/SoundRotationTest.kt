package app.dozecam.ui.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SoundRotationTest {

    private val cameras = listOf("nursery", "playroom", "hall")

    @Test
    fun `the first turn goes to the first camera`() {
        assertEquals("nursery", SoundRotation.next(cameras, null))
    }

    @Test
    fun `each turn moves to the next camera`() {
        assertEquals("playroom", SoundRotation.next(cameras, "nursery"))
        assertEquals("hall", SoundRotation.next(cameras, "playroom"))
    }

    @Test
    fun `the round wraps rather than stopping at the end`() {
        assertEquals("nursery", SoundRotation.next(cameras, "hall"))
    }

    @Test
    fun `a camera that has gone hands the turn back to the top`() {
        // Switched off or deleted mid-round. Anything else spends a turn on a
        // camera that is no longer on screen — silence the user cannot explain.
        assertEquals("nursery", SoundRotation.next(cameras, "garage"))
    }

    @Test
    fun `one camera keeps the sound`() {
        assertEquals("nursery", SoundRotation.next(listOf("nursery"), "nursery"))
    }

    @Test
    fun `no cameras means silence`() {
        assertNull(SoundRotation.next(emptyList(), null))
        assertNull(SoundRotation.next(emptyList(), "nursery"))
    }

    @Test
    fun `a turn lasts ten seconds`() {
        // Long enough to tell whether a room has settled; short enough that
        // going round three cameras is not a wait. Pinned because it is the
        // whole feel of the grid, not an implementation detail.
        assertEquals(10_000L, SOUND_ROTATION_INTERVAL_MS)
    }
}

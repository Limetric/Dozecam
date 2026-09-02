package app.dozecam.monitoring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenTargetTest {

    private val monitored = listOf("a", "b", "c")

    @Test
    fun `the only camera there is needs no asking`() {
        assertEquals("a", ListenTarget.resolve(chosen = null, monitored = listOf("a")))
    }

    @Test
    fun `with several to choose from, an unanswered question stays unanswered`() {
        // Guessing here means a voice from a room nobody named, on a phone whose
        // screen is off — the one thing listen mode must never be.
        assertNull(ListenTarget.resolve(chosen = null, monitored = monitored))
        assertTrue(ListenTarget.needsChoice(chosen = null, monitored = monitored))
    }

    @Test
    fun `the chosen camera is the one that plays`() {
        assertEquals("b", ListenTarget.resolve(chosen = "b", monitored = monitored))
        assertFalse(ListenTarget.needsChoice(chosen = "b", monitored = monitored))
    }

    @Test
    fun `a choice the monitor is no longer listening to is not quietly replaced`() {
        // Switched off in settings, or gone with the console that issued it.
        // Another room is not a near-enough answer: whoever set this asked for
        // that one, and a monitor that substitutes rooms is unusable.
        assertNull(ListenTarget.resolve(chosen = "gone", monitored = monitored))
        assertTrue(ListenTarget.needsChoice(chosen = "gone", monitored = monitored))
    }

    @Test
    fun `a stale choice still resolves when it is the only camera left`() {
        assertEquals("a", ListenTarget.resolve(chosen = "gone", monitored = listOf("a")))
    }

    @Test
    fun `nothing to listen to is nothing to ask about`() {
        assertNull(ListenTarget.resolve(chosen = "a", monitored = emptyList()))
        assertFalse(ListenTarget.needsChoice(chosen = "a", monitored = emptyList()))
    }

    @Test
    fun `the target follows the setting`() {
        assertEquals(
            "b",
            ListenTarget.of(
                listening = true,
                speakerGranted = true,
                viewerAudible = false,
                chosen = "b",
                monitored = monitored,
            ),
        )
        assertNull(
            ListenTarget.of(
                listening = false,
                speakerGranted = true,
                viewerAudible = false,
                chosen = "b",
                monitored = monitored,
            ),
        )
    }

    @Test
    fun `losing the speaker silences it without waiting for the switch`() {
        // The switch is still on — a call has the speaker, not the user's mind.
        assertNull(
            ListenTarget.of(
                listening = true,
                speakerGranted = false,
                viewerAudible = false,
                chosen = "b",
                monitored = monitored,
            ),
        )
    }

    @Test
    fun `listen mode stands down while the viewer is making noise`() {
        // Otherwise the same nursery comes out of one speaker twice, a second
        // or so apart.
        assertNull(
            ListenTarget.of(
                listening = true,
                speakerGranted = true,
                viewerAudible = true,
                chosen = "b",
                monitored = monitored,
            ),
        )
    }

    @Test
    fun `switching the target moves the sound rather than adding to it`() {
        // One id at a time is the whole guarantee: there is no shape of this
        // answer that names two rooms.
        val before = ListenTarget.of(true, true, false, "b", monitored)
        val after = ListenTarget.of(true, true, false, "c", monitored)

        assertEquals("b", before)
        assertEquals("c", after)
    }
}

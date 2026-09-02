package app.dozecam.monitoring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ListenTargetTest {

    private val monitored = listOf("a", "b", "c")

    @Test
    fun `the room that was asked for is the room that plays`() {
        assertEquals("b", ListenTarget.of("b", speakerGranted = true, viewerAudible = false, monitored))
    }

    @Test
    fun `nothing asked for is nothing played`() {
        assertNull(ListenTarget.of(null, speakerGranted = true, viewerAudible = false, monitored))
    }

    @Test
    fun `a room the monitor is no longer listening to is not quietly replaced`() {
        // Switched off in settings, or gone with the console that issued it.
        // Another room is not a near-enough answer, and neither is the only
        // room left: whoever asked, asked for that one. Silence is at least
        // legible; a substituted bedroom is not.
        assertNull(ListenTarget.of("gone", speakerGranted = true, viewerAudible = false, monitored))
        assertNull(
            ListenTarget.of("gone", speakerGranted = true, viewerAudible = false, listOf("a")),
        )
    }

    @Test
    fun `losing the speaker silences it without waiting for the switch`() {
        // The ask stands — a call has the speaker, not the user's mind.
        assertNull(ListenTarget.of("b", speakerGranted = false, viewerAudible = false, monitored))
    }

    @Test
    fun `listen mode stands down while the viewer is making noise`() {
        // Otherwise the same nursery comes out of one speaker twice, a second
        // or so apart.
        assertNull(ListenTarget.of("b", speakerGranted = true, viewerAudible = true, monitored))
    }

    @Test
    fun `switching the target moves the sound rather than adding to it`() {
        // One id at a time is the whole guarantee: there is no shape of this
        // answer that names two rooms.
        assertEquals("b", ListenTarget.of("b", true, false, monitored))
        assertEquals("c", ListenTarget.of("c", true, false, monitored))
    }
}

package app.dozecam.monitoring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenTargetTest {

    private val monitored = listOf("a", "b", "c")

    @Test
    fun `every room the monitor can hear plays, together`() {
        assertEquals(
            setOf("a", "b", "c"),
            ListenTarget.of(requested = true, speakerGranted = true, viewerAudible = false, monitored),
        )
    }

    @Test
    fun `nothing asked for is nothing played`() {
        assertEquals(
            emptySet<String>(),
            ListenTarget.of(requested = false, speakerGranted = true, viewerAudible = false, monitored),
        )
    }

    @Test
    fun `a house with nothing monitored has nothing to play`() {
        // Every camera switched off, or gone with the console that issued it.
        assertEquals(
            emptySet<String>(),
            ListenTarget.of(requested = true, speakerGranted = true, viewerAudible = false, emptyList()),
        )
    }

    @Test
    fun `losing the speaker silences it without waiting for the switch`() {
        // The ask stands — a call has the speaker, not the user's mind.
        assertEquals(
            emptySet<String>(),
            ListenTarget.of(requested = true, speakerGranted = false, viewerAudible = false, monitored),
        )
    }

    @Test
    fun `listen mode stands down while the viewer is making noise`() {
        // Otherwise the same nursery comes out of one speaker twice, a second
        // or so apart.
        assertEquals(
            emptySet<String>(),
            ListenTarget.of(requested = true, speakerGranted = true, viewerAudible = true, monitored),
        )
    }

    @Test
    fun `the only room playing aloud needs no naming`() {
        // Whoever switched listen mode on is being told about that room
        // continuously; lighting a bedroom at 3am on top of it wakes the
        // parent who is already listening, and the one beside them.
        assertFalse(ListenTarget.alertWakesScreen("a", aloud = setOf("a")))
    }

    @Test
    fun `one room among several is named on screen`() {
        // A cry out of a mix of rooms does not say whose it was, and the name
        // is the one thing the speaker cannot supply.
        assertTrue(ListenTarget.alertWakesScreen("a", aloud = setOf("a", "b")))
    }

    @Test
    fun `a room nobody can hear always wakes the screen`() {
        assertTrue(ListenTarget.alertWakesScreen("c", aloud = setOf("a")))
        assertTrue(ListenTarget.alertWakesScreen("c", aloud = emptySet()))
    }
}

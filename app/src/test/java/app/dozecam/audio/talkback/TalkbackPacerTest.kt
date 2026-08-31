package app.dozecam.audio.talkback

import org.junit.Assert.assertEquals
import org.junit.Test

class TalkbackPacerTest {

    private val start = 5_000_000_000L
    private val frame = 20_000_000L

    @Test
    fun `frames are due at exact multiples of the frame duration`() {
        val pacer = TalkbackPacer(start)

        assertEquals(start, pacer.dueAtNanos(0))
        assertEquals(start + frame, pacer.dueAtNanos(1))
        assertEquals(start + 50 * frame, pacer.dueAtNanos(50))
    }

    /**
     * The reason the pacer takes an index rather than counting sleeps. A minute
     * of talking is 3000 frames; if each one were scheduled a frame after the
     * last actually went out, every microsecond spent encoding would push the
     * next one later, and the phone would fall steadily behind the camera.
     */
    @Test
    fun `a long press does not drift, however slow the sending was`() {
        val pacer = TalkbackPacer(start)
        val frames = 3_000L

        assertEquals(start + frames * frame, pacer.dueAtNanos(frames))
        // Sixty seconds of audio, sixty seconds of schedule, to the nanosecond.
        assertEquals(60_000_000_000L, pacer.dueAtNanos(frames) - pacer.dueAtNanos(0))
    }

    @Test
    fun `waiting is measured from now, not from the last frame`() {
        val pacer = TalkbackPacer(start)

        // A third of the way into frame 1's slot: the rest of that slot remains
        // before frame 2 is due.
        assertEquals(
            frame - frame / 3,
            pacer.waitNanos(index = 2, nowNanos = start + frame + frame / 3),
        )
    }

    /**
     * A frame whose moment has passed goes out immediately and the schedule is
     * left alone. Waiting a full frame anyway would turn every hiccup into a
     * permanent lag; moving the clock to suit it would turn the catch-up into
     * the burst the pacer exists to prevent.
     */
    @Test
    fun `a late frame never waits, and never earns the next one extra time`() {
        val pacer = TalkbackPacer(start)
        val veryLate = start + 100 * frame

        assertEquals(0L, pacer.waitNanos(index = 3, nowNanos = veryLate))
        assertEquals(start + 4 * frame, pacer.dueAtNanos(4))
    }

    @Test
    fun `the lead-in is a fifth of a second of silence`() {
        val pacer = TalkbackPacer(start)

        assertEquals(
            200_000_000L,
            pacer.dueAtNanos(TalkbackPacer.LEAD_IN_FRAMES.toLong()) - pacer.dueAtNanos(0),
        )
    }

    /**
     * Longer than the lead-in on purpose. The click it covers is the camera's
     * buffer starting up and is over quickly; the silence after a release has
     * to outlast whatever the decoder would otherwise invent.
     */
    @Test
    fun `the lead-out is three tenths of a second of silence`() {
        val pacer = TalkbackPacer(start)

        assertEquals(
            300_000_000L,
            pacer.dueAtNanos(TalkbackPacer.LEAD_OUT_FRAMES.toLong()) - pacer.dueAtNanos(0),
        )
    }
}

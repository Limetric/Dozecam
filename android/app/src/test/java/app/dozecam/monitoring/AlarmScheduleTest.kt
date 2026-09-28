package app.dozecam.monitoring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ramp and the repeat, checked off-device. These are the numbers that decide
 * whether anyone wakes up, and a device is the worst place to find out they are
 * wrong.
 */
class AlarmScheduleTest {

    @Test
    fun `starts gently rather than at full volume`() {
        val schedule = AlarmSchedule(ramp = true, rampMs = 5_000)

        assertEquals(AlarmSchedule.RAMP_START, schedule.volumeAt(0), TOLERANCE)
    }

    @Test
    fun `climbs to full over the ramp and stays there`() {
        val schedule = AlarmSchedule(ramp = true, rampMs = 5_000)

        val quiet = schedule.volumeAt(0)
        val middle = schedule.volumeAt(2_500)
        val full = schedule.volumeAt(5_000)

        assertTrue("$quiet should be below $middle", quiet < middle)
        assertTrue("$middle should be below $full", middle < full)
        assertEquals(1f, full, TOLERANCE)
        assertEquals(1f, schedule.volumeAt(60_000), TOLERANCE)
    }

    @Test
    fun `starts at full volume when the ramp is switched off`() {
        val schedule = AlarmSchedule(ramp = false, rampMs = 5_000)

        assertEquals(1f, schedule.volumeAt(0), TOLERANCE)
    }

    @Test
    fun `the ceiling scales the whole ramp and is never exceeded`() {
        val schedule = AlarmSchedule(ramp = true, rampMs = 5_000, ceiling = 0.5f)

        assertEquals(0.5f * AlarmSchedule.RAMP_START, schedule.volumeAt(0), TOLERANCE)
        assertEquals(0.5f, schedule.volumeAt(5_000), TOLERANCE)
        assertEquals(0.5f, schedule.volumeAt(60_000), TOLERANCE)
    }

    @Test
    fun `a ceiling outside the usable range is clamped rather than trusted`() {
        assertEquals(1f, AlarmSchedule(ramp = false, ceiling = 4f).volumeAt(0), TOLERANCE)
        assertEquals(0f, AlarmSchedule(ramp = false, ceiling = -1f).volumeAt(0), TOLERANCE)
    }

    @Test
    fun `a burst is due each time a tick crosses the interval`() {
        val schedule = AlarmSchedule(repeatIntervalMs = 8_000)

        assertFalse(schedule.burstDue(7_750, 7_999))
        assertTrue(schedule.burstDue(7_750, 8_000))
        assertFalse(schedule.burstDue(8_000, 8_250))
        assertTrue(schedule.burstDue(15_750, 16_000))
    }

    /**
     * A tick the system delayed must not lose the burst it slept through — the
     * repeat is what a sleeping parent is relying on.
     */
    @Test
    fun `a tick that overshoots several intervals still reports a burst`() {
        val schedule = AlarmSchedule(repeatIntervalMs = 8_000)

        assertTrue(schedule.burstDue(1_000, 30_000))
    }

    @Test
    fun `time standing still is not a burst`() {
        val schedule = AlarmSchedule(repeatIntervalMs = 8_000)

        assertFalse(schedule.burstDue(8_000, 8_000))
        assertFalse(schedule.burstDue(9_000, 8_000))
    }

    @Test
    fun `gives up only once the cap is reached`() {
        val schedule = AlarmSchedule(maxDurationMs = 300_000)

        assertFalse(schedule.expired(0))
        assertFalse(schedule.expired(299_999))
        assertTrue(schedule.expired(300_000))
    }

    private companion object {
        const val TOLERANCE = 0.0001f
    }
}

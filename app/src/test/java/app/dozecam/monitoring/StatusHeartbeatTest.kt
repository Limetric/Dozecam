package app.dozecam.monitoring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class StatusHeartbeatTest {

    private var wallMs = 0L
    private var elapsedMs = 0L
    private val heartbeat = StatusHeartbeat(now = { wallMs }, elapsed = { elapsedMs })

    /** Both clocks tick together, as they do on a device whose time is never corrected. */
    private fun advanceTo(ms: Long) {
        wallMs = ms
        elapsedMs = ms
    }

    private val listening = "Listening to 2 cameras"

    @Test
    fun `the first status always posts`() {
        val display = heartbeat.offer(listening, 0f)

        assertNotNull(display)
        assertEquals(0, display!!.levelBucket)
    }

    @Test
    fun `a text change posts immediately even mid-interval`() {
        heartbeat.offer(listening, 0f)
        advanceTo(100)

        assertNotNull(heartbeat.offer("Sound detected — Nursery", null))
    }

    @Test
    fun `level motion posts no faster than the interval`() {
        heartbeat.offer(listening, 0f)

        // The room gets loud straight away, but the last post is too fresh.
        advanceTo(1_000)
        assertNull(heartbeat.offer(listening, 0.3f))

        // Once the interval has passed, the still-loud room shows.
        advanceTo(StatusHeartbeat.MIN_INTERVAL_MS)
        val display = heartbeat.offer(listening, 0.3f)
        assertEquals(6, display?.levelBucket)
    }

    /**
     * RMS wobbles on every decoded buffer; the coarse buckets exist so that
     * wobble is not a reason to repost.
     */
    @Test
    fun `a sub-bucket wiggle never reposts`() {
        heartbeat.offer(listening, 0.30f)

        advanceTo(10_000)
        assertNull(heartbeat.offer(listening, 0.31f))
    }

    /**
     * The point of the whole thing: a silent, healthy night must still visibly
     * advance, or "Listening to 2 cameras" is indistinguishable from a wedged
     * process that last posted the same words.
     */
    @Test
    fun `a silent room still posts once a minute`() {
        heartbeat.offer(listening, 0f)

        advanceTo(59_999)
        assertNull(heartbeat.offer(listening, 0f))

        advanceTo(60_000)
        val display = heartbeat.offer(listening, 0f)
        assertNotNull(display)
        assertEquals(60_000L, display?.checkedAtMs)
    }

    /**
     * "Offline" wearing a fresh timestamp would read as reassurance it has not
     * earned, so states without a level only repost when their words change.
     */
    @Test
    fun `states without a level never repost on time alone`() {
        heartbeat.offer("Offline — waiting for network", null)

        advanceTo(10 * 60_000L)
        assertNull(heartbeat.offer("Offline — waiting for network", null))
    }

    @Test
    fun `a level past the meter's range fills the bar rather than overflowing it`() {
        val display = heartbeat.offer(listening, 0.9f)

        assertEquals(StatusHeartbeat.LEVEL_BUCKETS, display?.levelBucket)
    }

    /**
     * The throttle runs on monotonic time precisely so this cannot happen: a
     * wall clock corrected backwards after a post must not leave "now minus
     * then" negative and the heartbeat frozen until wall time catches up.
     */
    @Test
    fun `a wall clock set backwards cannot freeze the heartbeat`() {
        wallMs = 3_600_000L
        elapsedMs = 0L
        heartbeat.offer(listening, 0f)

        // An hour's correction backwards; only a minute really passes.
        wallMs = 60_000L
        elapsedMs = 60_000L

        assertNotNull(heartbeat.offer(listening, 0f))
    }
}

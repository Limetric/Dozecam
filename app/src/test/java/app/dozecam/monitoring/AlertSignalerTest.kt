package app.dozecam.monitoring

import android.net.Uri
import app.dozecam.data.AppSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The alarm's behaviour over time, on virtual time and fake outputs — the parts
 * that matter here (does it repeat, does it stop, does it stack) all happen over
 * minutes, which is no way to run a test suite.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AlertSignalerTest {

    private val player = FakePlayer()
    private val vibrator = FakeVibrator()
    private val dnd = FakeDnd()

    /**
     * Virtual time throughout: the alarm reads the test scheduler's clock, so a
     * five-minute cap costs nothing to check.
     */
    private fun TestScope.signaler() = AlertSignaler(
        player = player,
        vibrator = vibrator,
        dnd = dnd,
        scope = backgroundScope,
        clock = { testScheduler.currentTime },
        tickMs = AlertSignaler.TICK_MS,
    )

    @Test
    fun `sounds and vibrates the moment it is triggered`() = alarmTest {
        val signaler = signaler()

        signaler.signal("cam-1", settings())
        runCurrent()

        assertEquals(1, player.starts.size)
        assertEquals(1, vibrator.pulses)
        assertEquals("cam-1", signaler.alarmingCameraId.value)
    }

    @Test
    fun `starts below full volume and climbs to it`() = alarmTest {
        val signaler = signaler()

        signaler.signal("cam-1", settings(alertRamp = true))
        runCurrent()
        val first = player.volume!!

        advanceTimeBy(AlarmSchedule.DEFAULT_RAMP_MS)
        runCurrent()

        assertEquals(AlarmSchedule.RAMP_START, first, TOLERANCE)
        assertEquals(1f, player.volume!!, TOLERANCE)
        assertTrue(signaler.isAlarming)
    }

    @Test
    fun `repeats on the chosen interval`() = alarmTest {
        val signaler = signaler()

        signaler.signal("cam-1", settings(alertRepeatIntervalMs = 8_000))
        runCurrent()
        advanceTimeBy(8_000)
        runCurrent()

        assertEquals(2, player.starts.size)
        assertEquals(2, vibrator.pulses)
    }

    /**
     * The latch, which is the whole point: the detector re-arms after its quiet
     * window, and a baby who cries for forty seconds and settles is exactly the
     * alert nobody heard. Nothing but a person or the cap may end this.
     */
    @Test
    fun `keeps sounding long after the room has gone quiet`() = alarmTest {
        val signaler = signaler()

        signaler.signal("cam-1", settings())
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()

        assertTrue(signaler.isAlarming)
        assertTrue("expected repeats, got ${player.starts.size}", player.starts.size >= 7)
    }

    @Test
    fun `a second trigger retargets the alarm instead of stacking a second player`() = alarmTest {
        val signaler = signaler()

        signaler.signal("cam-1", settings())
        runCurrent()
        advanceTimeBy(1_000)
        signaler.signal("cam-2", settings())
        runCurrent()

        assertEquals("cam-2", signaler.alarmingCameraId.value)
        assertEquals("the second trigger must not open its own burst", 1, player.starts.size)

        advanceTimeBy(7_000)
        runCurrent()

        assertEquals("one burst per interval, not two", 2, player.starts.size)
    }

    @Test
    fun `acknowledging stops everything`() = alarmTest {
        val signaler = signaler()

        signaler.signal("cam-1", settings())
        runCurrent()
        signaler.acknowledge()
        runCurrent()

        assertFalse(signaler.isAlarming)
        assertNull(signaler.alarmingCameraId.value)
        assertFalse(player.playing)
        assertEquals(1, vibrator.cancels)

        advanceTimeBy(60_000)
        runCurrent()

        assertEquals("nothing may sound after an acknowledgement", 1, player.starts.size)
    }

    @Test
    fun `gives up once the cap is reached`() = alarmTest {
        val signaler = signaler()

        signaler.signal("cam-1", settings())
        runCurrent()
        advanceTimeBy(AlarmSchedule.DEFAULT_MAX_DURATION_MS)
        runCurrent()

        assertFalse(signaler.isAlarming)
        assertFalse(player.playing)
    }

    /** A room still going off half an hour later has earned another five minutes. */
    @Test
    fun `a fresh trigger extends the cap`() = alarmTest {
        val signaler = signaler()

        signaler.signal("cam-1", settings())
        runCurrent()
        advanceTimeBy(240_000)
        signaler.signal("cam-1", settings())
        runCurrent()

        advanceTimeBy(AlarmSchedule.DEFAULT_MAX_DURATION_MS - 240_000)
        runCurrent()
        assertTrue("the cap runs from the newest trigger", signaler.isAlarming)

        advanceTimeBy(AlarmSchedule.DEFAULT_MAX_DURATION_MS)
        runCurrent()
        assertFalse(signaler.isAlarming)
    }

    @Test
    fun `vibration carries on alone when the chime is switched off`() = alarmTest {
        val signaler = signaler()

        signaler.signal("cam-1", settings(alertChime = false))
        runCurrent()
        advanceTimeBy(8_000)
        runCurrent()

        assertEquals(0, player.starts.size)
        assertEquals(2, vibrator.pulses)
    }

    @Test
    fun `the chime carries on alone when vibration is switched off`() = alarmTest {
        val signaler = signaler()

        signaler.signal("cam-1", settings(alertVibrate = false))
        runCurrent()

        assertEquals(1, player.starts.size)
        assertEquals(0, vibrator.pulses)
    }

    @Test
    fun `the alert volume caps the ramp`() = alarmTest {
        val signaler = signaler()

        signaler.signal("cam-1", settings(alertVolume = 0.5f))
        runCurrent()
        advanceTimeBy(AlarmSchedule.DEFAULT_RAMP_MS)
        runCurrent()

        assertEquals(0.5f, player.volume!!, TOLERANCE)
    }

    @Test
    fun `the do not disturb override is asked for and always handed back`() = alarmTest {
        val signaler = signaler()

        signaler.signal("cam-1", settings(alertBypassDnd = true))
        runCurrent()
        assertEquals(true, dnd.begun)
        assertEquals(0, dnd.ends)

        signaler.acknowledge()
        runCurrent()
        assertEquals(1, dnd.ends)
    }

    @Test
    fun `an alarm that gives up still hands the override back`() = alarmTest {
        val signaler = signaler()

        signaler.signal("cam-1", settings(alertBypassDnd = true))
        runCurrent()
        advanceTimeBy(AlarmSchedule.DEFAULT_MAX_DURATION_MS)
        runCurrent()

        assertEquals(1, dnd.ends)
    }

    @Test
    fun `preview plays once without latching an alarm`() = alarmTest {
        val signaler = signaler()

        signaler.preview(settings(alertVolume = 0.8f))
        runCurrent()

        assertEquals(1, player.starts.size)
        assertEquals(0.8f, player.starts.single(), TOLERANCE)
        assertFalse("a preview is not an alert", signaler.isAlarming)
        assertEquals(0, vibrator.pulses)

        advanceTimeBy(AlertSignaler.PREVIEW_MS)
        runCurrent()
        assertFalse(player.playing)
    }

    @Test
    fun `a real alarm refuses to be talked over by a preview`() = alarmTest {
        val signaler = signaler()

        signaler.signal("cam-1", settings())
        runCurrent()
        signaler.preview(settings())
        runCurrent()

        assertEquals(1, player.starts.size)
        assertTrue(signaler.isAlarming)
    }

    /**
     * The preview and the alarm share one player. A preview cut short by a real
     * alert must not reach its own cleanup and silence the burst that replaced
     * it — the alert would be lost to a sound test run an hour earlier.
     */
    @Test
    fun `a preview interrupted by a real alert does not silence it`() = alarmTest {
        val signaler = signaler()

        signaler.preview(settings())
        runCurrent()
        signaler.signal("cam-1", settings())
        runCurrent()

        // Long enough that the abandoned preview's own timer would have expired.
        advanceTimeBy(AlertSignaler.PREVIEW_MS)
        runCurrent()

        assertTrue(signaler.isAlarming)
        assertTrue("the alarm should still be sounding", player.playing)
    }

    @Test
    fun `a preview replaced by another does not silence its replacement`() = alarmTest {
        val signaler = signaler()

        signaler.preview(settings())
        runCurrent()
        advanceTimeBy(1_000)
        signaler.preview(settings())
        runCurrent()

        advanceTimeBy(AlertSignaler.PREVIEW_MS - 1_000)
        runCurrent()

        assertTrue("the newer preview should still be playing", player.playing)
    }

    @Test
    fun `stopping a preview leaves a live alarm alone`() = alarmTest {
        val signaler = signaler()

        signaler.signal("cam-1", settings())
        runCurrent()
        signaler.stopPreview()
        runCurrent()

        assertTrue("leaving settings must not silence an alert", signaler.isAlarming)
    }

    private fun settings(
        alertChime: Boolean = true,
        alertVibrate: Boolean = true,
        alertRamp: Boolean = true,
        alertRepeatIntervalMs: Long = AlarmSchedule.DEFAULT_REPEAT_INTERVAL_MS,
        alertVolume: Float = 1f,
        alertBypassDnd: Boolean = false,
    ) = AppSettings(
        alertChime = alertChime,
        alertVibrate = alertVibrate,
        alertRamp = alertRamp,
        alertRepeatIntervalMs = alertRepeatIntervalMs,
        alertVolume = alertVolume,
        alertBypassDnd = alertBypassDnd,
    )

    private fun alarmTest(body: suspend TestScope.() -> Unit) = runTest { body() }

    private class FakePlayer : AlarmPlayer {
        val starts = mutableListOf<Float>()
        var volume: Float? = null
        var playing = false

        override fun start(uri: Uri, volume: Float) {
            starts += volume
            this.volume = volume
            playing = true
        }

        override fun setVolume(volume: Float) {
            this.volume = volume
        }

        override fun stop() {
            playing = false
        }
    }

    private class FakeVibrator : AlarmVibrator {
        var pulses = 0
        var cancels = 0

        override fun pulse() {
            pulses++
        }

        override fun cancel() {
            cancels++
        }
    }

    private class FakeDnd : DndOverride {
        var begun: Boolean? = null
        var ends = 0

        override fun beginBypass(enabled: Boolean) {
            begun = enabled
        }

        override fun endBypass() {
            ends++
        }
    }

    private companion object {
        const val TOLERANCE = 0.0001f
    }
}

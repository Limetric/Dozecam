package app.dozecam.player

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackWatchdogTest {

    private class ReconnectRecorder(private val timeMs: () -> Long) {
        val attempts = mutableListOf<Long>()
        suspend fun record() {
            attempts += timeMs()
        }
    }

    private fun TestScope.recorder(): ReconnectRecorder =
        ReconnectRecorder { testScheduler.currentTime }

    private fun TestScope.watchdog(
        recorder: ReconnectRecorder,
        config: PlaybackWatchdog.Config = PlaybackWatchdog.Config(),
    ): PlaybackWatchdog = PlaybackWatchdog(
        scope = backgroundScope,
        onReconnect = { recorder.record() },
        wallClock = { testScheduler.currentTime },
        monotonicClock = { testScheduler.currentTime },
        config = config,
    )

    @Test
    fun `frames drive the state to live and record the frame time`() = runTest {
        val recorder = recorder()
        val watchdog = watchdog(recorder)
        watchdog.start()

        watchdog.onPlayerEvent(PlayerEvent.Playing)
        runCurrent()

        assertEquals(ConnectionState.Live, watchdog.state.value)
        assertEquals(0L, watchdog.lastFrameAtMs.value)
        assertTrue(recorder.attempts.isEmpty())
    }

    @Test
    fun `stall while live triggers reconnect after backoff`() = runTest {
        val recorder = recorder()
        val watchdog = watchdog(recorder)
        watchdog.start()
        watchdog.onPlayerEvent(PlayerEvent.Playing)
        runCurrent()

        // No frames for stallTimeout(2500) then backoff(500) before reconnect.
        advanceTimeBy(3_001)

        assertEquals(1, recorder.attempts.size)
        assertEquals(3_000L, recorder.attempts.first())
        assertEquals(ConnectionState.Reconnecting(1), watchdog.state.value)
    }

    @Test
    fun `repeated failures back off exponentially up to the cap`() = runTest {
        val recorder = recorder()
        val watchdog = watchdog(recorder)
        watchdog.start()

        // Never any frames: every connect attempt times out (5000ms) and
        // retries after 500, 1000, 2000, 4000 (cap), 4000...
        advanceTimeBy(60_000)

        val gaps = recorder.attempts.zipWithNext { a, b -> b - a }
        assertTrue("expected several attempts, got ${recorder.attempts}", gaps.size >= 4)
        // Each gap = connectTimeout(5000) + backoff for that attempt.
        assertEquals(6_000L, gaps[0]) // 5000 + 1000
        assertEquals(7_000L, gaps[1]) // 5000 + 2000
        assertEquals(9_000L, gaps[2]) // 5000 + 4000 (cap)
        assertEquals(9_000L, gaps[3]) // stays at cap
    }

    @Test
    fun `player error triggers reconnect and recovery resets attempts`() = runTest {
        val recorder = recorder()
        val watchdog = watchdog(recorder)
        watchdog.start()
        watchdog.onPlayerEvent(PlayerEvent.Playing)
        runCurrent()

        watchdog.onPlayerEvent(PlayerEvent.Error)
        advanceTimeBy(501)
        assertEquals(1, recorder.attempts.size)
        assertEquals(ConnectionState.Reconnecting(1), watchdog.state.value)

        watchdog.onPlayerEvent(PlayerEvent.Playing)
        runCurrent()
        assertEquals(ConnectionState.Live, watchdog.state.value)

        // Next failure starts back at attempt 1 with the initial backoff.
        watchdog.onPlayerEvent(PlayerEvent.Error)
        advanceTimeBy(501)
        assertEquals(2, recorder.attempts.size)
        assertEquals(ConnectionState.Reconnecting(1), watchdog.state.value)
    }

    @Test
    fun `network loss parks the watchdog offline until network returns`() = runTest {
        val recorder = recorder()
        val watchdog = watchdog(recorder)
        watchdog.start()
        watchdog.onPlayerEvent(PlayerEvent.Playing)
        runCurrent()

        watchdog.onNetworkLost()
        runCurrent()
        assertEquals(ConnectionState.Offline, watchdog.state.value)

        // Parked: no reconnect attempts accumulate while offline.
        advanceTimeBy(30_000)
        assertTrue(recorder.attempts.isEmpty())

        watchdog.onNetworkAvailable()
        runCurrent()
        assertEquals(1, recorder.attempts.size)
        assertEquals(ConnectionState.Reconnecting(1), watchdog.state.value)
    }

    @Test
    fun `network up while live does not restart the stream`() = runTest {
        val recorder = recorder()
        val watchdog = watchdog(recorder)
        watchdog.start()
        watchdog.onPlayerEvent(PlayerEvent.Playing)
        runCurrent()

        watchdog.onNetworkAvailable()
        runCurrent()

        assertTrue(recorder.attempts.isEmpty())
        assertEquals(ConnectionState.Live, watchdog.state.value)
    }

    @Test
    fun `teardown stop echo during recovery is ignored`() = runTest {
        val recorder = recorder()
        val watchdog = watchdog(recorder)
        watchdog.start()
        watchdog.onPlayerEvent(PlayerEvent.Playing)
        runCurrent()

        watchdog.onPlayerEvent(PlayerEvent.Error)
        advanceTimeBy(501) // reconnect issued; awaiting recovery
        assertEquals(1, recorder.attempts.size)

        // The old session's Stopped event arrives after our own teardown.
        watchdog.onPlayerEvent(PlayerEvent.Stopped)
        runCurrent()

        assertEquals(1, recorder.attempts.size)
        assertEquals(ConnectionState.Reconnecting(1), watchdog.state.value)
    }

    @Test
    fun `frames arriving while offline never flip the state back to live`() = runTest {
        val recorder = recorder()
        val watchdog = watchdog(recorder)
        watchdog.start()
        watchdog.onPlayerEvent(PlayerEvent.Playing)
        runCurrent()

        watchdog.onNetworkLost()
        runCurrent()
        // Buffered frames trickle in after the network dropped.
        watchdog.onPlayerEvent(PlayerEvent.TimeChanged(1_000))
        runCurrent()

        assertEquals(ConnectionState.Offline, watchdog.state.value)
    }

    @Test
    fun `restart after stop begins in connecting state`() = runTest {
        val recorder = recorder()
        val watchdog = watchdog(recorder)
        watchdog.start()
        watchdog.onPlayerEvent(PlayerEvent.Playing)
        runCurrent()
        assertEquals(ConnectionState.Live, watchdog.state.value)

        watchdog.stop()
        watchdog.start()

        assertEquals(ConnectionState.Connecting, watchdog.state.value)
    }

    @Test
    fun `network loss during backoff aborts the pending reconnect`() = runTest {
        val recorder = recorder()
        val watchdog = watchdog(recorder)
        watchdog.start()
        watchdog.onPlayerEvent(PlayerEvent.Playing)
        runCurrent()

        watchdog.onPlayerEvent(PlayerEvent.Error)
        runCurrent() // enter the backoff window
        watchdog.onNetworkLost()
        advanceTimeBy(10_000)

        assertTrue(recorder.attempts.isEmpty())
        assertEquals(ConnectionState.Offline, watchdog.state.value)
    }

    @Test
    fun `frames resuming during backoff cancel the restart`() = runTest {
        val recorder = recorder()
        val watchdog = watchdog(recorder)
        watchdog.start()
        watchdog.onPlayerEvent(PlayerEvent.Playing)
        runCurrent()

        // Stall fires at 2500ms, then the stream recovers inside the backoff
        // window (2500..3000). Advance just past that window: the pending
        // restart must have been cancelled, not merely delayed.
        advanceTimeBy(2_600)
        watchdog.onPlayerEvent(PlayerEvent.TimeChanged(1_000))
        advanceTimeBy(600)

        assertTrue(recorder.attempts.isEmpty())
        assertEquals(ConnectionState.Live, watchdog.state.value)
    }

    @Test
    fun `repeated buffering events do not defer stall detection`() = runTest {
        val recorder = recorder()
        val watchdog = watchdog(recorder)
        watchdog.start()
        watchdog.onPlayerEvent(PlayerEvent.Playing)
        runCurrent()

        // A frozen stream that keeps emitting Buffering callbacks must still
        // stall at 2500ms after the last frame (reconnect fires at 3000ms).
        advanceTimeBy(1_000)
        watchdog.onPlayerEvent(PlayerEvent.Buffering)
        runCurrent()
        advanceTimeBy(1_000)
        watchdog.onPlayerEvent(PlayerEvent.Buffering)
        runCurrent()
        advanceTimeBy(1_100)

        assertEquals(1, recorder.attempts.size)
        assertEquals(3_000L, recorder.attempts.first())
    }

    @Test
    fun `events queued while stopped are discarded on restart`() = runTest {
        val recorder = recorder()
        val watchdog = watchdog(recorder)
        watchdog.start()
        watchdog.onPlayerEvent(PlayerEvent.Playing)
        runCurrent()

        watchdog.stop()
        watchdog.onPlayerEvent(PlayerEvent.Error) // stale failure from the old session
        watchdog.start()
        runCurrent()
        advanceTimeBy(1_000)

        assertTrue(recorder.attempts.isEmpty())
        assertEquals(ConnectionState.Connecting, watchdog.state.value)
    }

    @Test
    fun `a camera nobody is watching is not declared stalled`() = runTest {
        val recorder = recorder()
        val watchdog = watchdog(recorder)
        watchdog.start()
        watchdog.onPlayerEvent(PlayerEvent.Playing)
        runCurrent()

        // Opening one camera drops the video track on the rest. No frames will
        // ever arrive for them, which is the point — and reading that as a
        // stall would reconnect the very sessions being kept warm.
        watchdog.onVideoDisabled()
        advanceTimeBy(60_000)

        assertTrue("kept warm but reconnected: ${recorder.attempts}", recorder.attempts.isEmpty())
        assertEquals(ConnectionState.Live, watchdog.state.value)
    }

    @Test
    fun `an unwatched stream that dies is still recovered`() = runTest {
        val recorder = recorder()
        val watchdog = watchdog(recorder)
        watchdog.start()
        watchdog.onPlayerEvent(PlayerEvent.Playing)
        runCurrent()
        watchdog.onVideoDisabled()
        runCurrent()

        // The socket drops while the grid is behind an open camera. Waiting to
        // find out on the way back would make returning cost a reconnect —
        // exactly what keeping it warm is meant to save.
        watchdog.onPlayerEvent(PlayerEvent.Error)
        advanceTimeBy(501)

        assertEquals(1, recorder.attempts.size)
    }

    @Test
    fun `a warm reconnect is not retried on a timer it cannot answer`() = runTest {
        val recorder = recorder()
        val watchdog = watchdog(recorder)
        watchdog.start()
        watchdog.onPlayerEvent(PlayerEvent.Playing)
        runCurrent()
        watchdog.onVideoDisabled()
        runCurrent()

        watchdog.onPlayerEvent(PlayerEvent.Error)
        advanceTimeBy(501)
        assertEquals(1, recorder.attempts.size)

        // With no video there is nothing to confirm the attempt worked, so
        // retrying on a deadline would just reconnect a healthy stream over and
        // over for as long as one camera stayed open.
        advanceTimeBy(60_000)
        assertEquals(1, recorder.attempts.size)
    }

    @Test
    fun `network changes still reach a camera being kept warm`() = runTest {
        val recorder = recorder()
        val watchdog = watchdog(recorder)
        watchdog.start()
        watchdog.onPlayerEvent(PlayerEvent.Playing)
        runCurrent()
        watchdog.onVideoDisabled()
        runCurrent()

        watchdog.onNetworkLost()
        runCurrent()
        assertEquals(ConnectionState.Offline, watchdog.state.value)

        watchdog.onNetworkAvailable()
        runCurrent()

        // A Wi-Fi blip during a look at one room must not leave the whole grid
        // dead when it comes back.
        assertEquals(1, recorder.attempts.size)
    }

    @Test
    fun `coming back to a camera allows for the wait on a keyframe`() = runTest {
        val recorder = recorder()
        val watchdog = watchdog(recorder)
        watchdog.start()
        watchdog.onPlayerEvent(PlayerEvent.Playing)
        runCurrent()
        watchdog.onVideoDisabled()
        advanceTimeBy(30_000)

        watchdog.onVideoEnabled()
        // The decoder went away with the track and cannot paint until the next
        // keyframe, which is longer than the stall allowance the camera was
        // live under. Judging it by that would reconnect every camera the grid
        // just got back.
        advanceTimeBy(2_600)
        assertTrue(recorder.attempts.isEmpty())

        watchdog.onPlayerEvent(PlayerEvent.Playing)
        runCurrent()
        assertEquals(ConnectionState.Live, watchdog.state.value)
        assertTrue(recorder.attempts.isEmpty())
    }

    @Test
    fun `a camera coming back does not claim to be live before it paints`() = runTest {
        val recorder = recorder()
        val watchdog = watchdog(recorder)
        watchdog.start()
        watchdog.onPlayerEvent(PlayerEvent.Playing)
        runCurrent()
        watchdog.onVideoDisabled()
        advanceTimeBy(30_000)
        assertEquals(ConnectionState.Live, watchdog.state.value)

        watchdog.onVideoEnabled()
        runCurrent()

        // Its decoder went away with the track, so nothing is on screen yet.
        // A pill reading LIVE over a picture that has not come back is the
        // frozen frame this whole class exists to refuse to report — and Live
        // is also the one state that hides the last frame's timestamp, which
        // would otherwise give it away.
        assertEquals(ConnectionState.Connecting, watchdog.state.value)

        watchdog.onPlayerEvent(PlayerEvent.Playing)
        runCurrent()
        assertEquals(ConnectionState.Live, watchdog.state.value)
    }

    @Test
    fun `coming back while reconnecting does not overwrite the honest state`() = runTest {
        val recorder = recorder()
        val watchdog = watchdog(recorder)
        watchdog.start()
        watchdog.onPlayerEvent(PlayerEvent.Playing)
        runCurrent()
        watchdog.onVideoDisabled()
        runCurrent()

        watchdog.onPlayerEvent(PlayerEvent.Error)
        advanceTimeBy(501)
        assertEquals(ConnectionState.Reconnecting(1), watchdog.state.value)

        // Asking for the picture back says nothing good about a stream already
        // known to be in trouble; only Live is the claim that needs retracting.
        watchdog.onVideoEnabled()
        runCurrent()

        assertEquals(ConnectionState.Reconnecting(1), watchdog.state.value)
    }

    @Test
    fun `a camera that never comes back is reconnected once it is watched again`() = runTest {
        val recorder = recorder()
        val watchdog = watchdog(recorder)
        watchdog.start()
        watchdog.onPlayerEvent(PlayerEvent.Playing)
        runCurrent()
        watchdog.onVideoDisabled()
        advanceTimeBy(30_000)

        // Nothing arrives after the picture is asked for again: the session
        // quietly died while unwatched. The first-frame allowance runs out and
        // normal recovery takes over.
        watchdog.onVideoEnabled()
        advanceTimeBy(5_501)

        assertEquals(1, recorder.attempts.size)
    }

    @Test
    fun `frames on the audio clock cannot keep a warm camera on a stall timer`() = runTest {
        val recorder = recorder()
        val watchdog = watchdog(recorder)
        watchdog.start()
        watchdog.onVideoDisabled()
        runCurrent()

        // libVLC keeps reporting time from the audio track with the video track
        // dropped. Marking that live must not re-arm a stall deadline the
        // stream has no way of meeting.
        watchdog.onPlayerEvent(PlayerEvent.TimeChanged(1_000))
        advanceTimeBy(60_000)

        assertTrue(recorder.attempts.isEmpty())
    }

    @Test
    fun `stopped when idle live counts as a failure`() = runTest {
        val recorder = recorder()
        val watchdog = watchdog(recorder)
        watchdog.start()
        watchdog.onPlayerEvent(PlayerEvent.Playing)
        runCurrent()

        watchdog.onPlayerEvent(PlayerEvent.Stopped)
        advanceTimeBy(501)

        assertEquals(1, recorder.attempts.size)
    }
}

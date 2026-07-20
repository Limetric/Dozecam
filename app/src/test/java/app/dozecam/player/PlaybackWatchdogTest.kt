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

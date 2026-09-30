package app.dozecam.player

import app.dozecam.testing.Fixtures
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The stall and backoff timings are the shared rule in
 * `shared/fixtures/playback-watchdog/timings.json`. The watchdog runs with its
 * own defaults and every reconnect time asserted here is derived from that
 * file, so the two cannot drift apart.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackWatchdogTest {

    @Serializable
    private data class Timings(val stallTimeoutMs: Long, val connectTimeoutMs: Long, val backoffMs: List<Long>)

    private val timings = Fixtures.decode<Timings>("playback-watchdog/timings.json")

    /** The wait before the first reconnect attempt. */
    private val firstBackoffMs get() = timings.backoffMs.first()

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

        // No frames for stallTimeoutMs, then the first backoff before reconnect.
        val reconnectAt = timings.stallTimeoutMs + firstBackoffMs
        advanceTimeBy(reconnectAt + 1)

        assertEquals(1, recorder.attempts.size)
        assertEquals("timings.json: stallTimeoutMs + backoffMs[0]", reconnectAt, recorder.attempts.first())
        assertEquals(ConnectionState.Reconnecting(1), watchdog.state.value)
    }

    @Test
    fun `repeated failures back off exponentially up to the cap`() = runTest {
        val recorder = recorder()
        val watchdog = watchdog(recorder)
        watchdog.start()

        // Never any frames: every connect attempt times out and retries after
        // the next backoff in the fixture, doubling up to its cap. Long enough
        // for one attempt per backoff entry.
        advanceTimeBy(timings.backoffMs.sumOf { timings.connectTimeoutMs + it } + 1)

        val gaps = recorder.attempts.zipWithNext { a, b -> b - a }
        assertTrue(
            "expected several attempts, got ${recorder.attempts}",
            gaps.size >= timings.backoffMs.size - 1,
        )
        // Each gap = connectTimeoutMs + the backoff for the attempt it ends in.
        timings.backoffMs.drop(1).forEachIndexed { i, backoffMs ->
            assertEquals(
                "timings.json: gap before attempt ${i + 2} = connectTimeoutMs + backoffMs[${i + 1}]",
                timings.connectTimeoutMs + backoffMs,
                gaps[i],
            )
        }
    }

    @Test
    fun `player error triggers reconnect and recovery resets attempts`() = runTest {
        val recorder = recorder()
        val watchdog = watchdog(recorder)
        watchdog.start()
        watchdog.onPlayerEvent(PlayerEvent.Playing)
        runCurrent()

        watchdog.onPlayerEvent(PlayerEvent.Error)
        advanceTimeBy(firstBackoffMs + 1)
        assertEquals(1, recorder.attempts.size)
        assertEquals(ConnectionState.Reconnecting(1), watchdog.state.value)

        watchdog.onPlayerEvent(PlayerEvent.Playing)
        runCurrent()
        assertEquals(ConnectionState.Live, watchdog.state.value)

        // Next failure starts back at attempt 1 with the initial backoff.
        watchdog.onPlayerEvent(PlayerEvent.Error)
        advanceTimeBy(firstBackoffMs + 1)
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
        advanceTimeBy(firstBackoffMs + 1) // reconnect issued; awaiting recovery
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

        // The stall fires, then the stream recovers inside the first backoff
        // window. Advance just past that window: the pending restart must have
        // been cancelled, not merely delayed.
        advanceTimeBy(timings.stallTimeoutMs + 100)
        watchdog.onPlayerEvent(PlayerEvent.TimeChanged(1_000))
        advanceTimeBy(firstBackoffMs + 100)

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
        // stall stallTimeoutMs after the last frame, and reconnect after the
        // first backoff.
        val reconnectAt = timings.stallTimeoutMs + firstBackoffMs
        advanceTimeBy(1_000)
        watchdog.onPlayerEvent(PlayerEvent.Buffering)
        runCurrent()
        advanceTimeBy(1_000)
        watchdog.onPlayerEvent(PlayerEvent.Buffering)
        runCurrent()
        advanceTimeBy(reconnectAt + 100 - 2_000)

        assertEquals(1, recorder.attempts.size)
        assertEquals("timings.json: stallTimeoutMs + backoffMs[0]", reconnectAt, recorder.attempts.first())
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
        advanceTimeBy(firstBackoffMs * 2)

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
    fun `nothing is restarted for a camera nobody is watching`() = runTest {
        val recorder = recorder()
        val watchdog = watchdog(recorder)
        watchdog.start()
        watchdog.onPlayerEvent(PlayerEvent.Playing)
        runCurrent()
        watchdog.onVideoDisabled()
        runCurrent()

        // Everything that would normally start a restart, arriving while the
        // room is behind an open camera.
        watchdog.onPlayerEvent(PlayerEvent.Error)
        watchdog.onPlayerEvent(PlayerEvent.Stopped)
        advanceTimeBy(60_000)

        // A stream with no video track cannot say whether a restart worked, so
        // it is not started blind. Repairing a camera nobody can see would also
        // take the network from the room actually being watched.
        assertTrue("restarted while unwatched: ${recorder.attempts}", recorder.attempts.isEmpty())
    }

    @Test
    fun `a camera that broke while unwatched is repaired the moment it is wanted`() = runTest {
        val recorder = recorder()
        val watchdog = watchdog(recorder)
        watchdog.start()
        watchdog.onPlayerEvent(PlayerEvent.Playing)
        runCurrent()
        watchdog.onVideoDisabled()
        runCurrent()

        watchdog.onPlayerEvent(PlayerEvent.Error)
        advanceTimeBy(30_000)
        assertTrue(recorder.attempts.isEmpty())

        watchdog.onVideoEnabled()
        runCurrent()

        // Asking for the picture is the first moment the question is answerable,
        // and it is answered at once rather than after a backoff the user would
        // spend looking at nothing.
        assertEquals(1, recorder.attempts.size)
        assertEquals(ConnectionState.Reconnecting(1), watchdog.state.value)
    }

    @Test
    fun `several failures while unwatched still cost one repair`() = runTest {
        val recorder = recorder()
        val watchdog = watchdog(recorder)
        watchdog.start()
        watchdog.onPlayerEvent(PlayerEvent.Playing)
        runCurrent()
        watchdog.onVideoDisabled()
        runCurrent()

        watchdog.onPlayerEvent(PlayerEvent.Error)
        watchdog.onPlayerEvent(PlayerEvent.Stopped)
        watchdog.onPlayerEvent(PlayerEvent.Error)
        advanceTimeBy(10_000)

        watchdog.onVideoEnabled()
        runCurrent()

        // One broken session is one thing to fix, however many ways it said so.
        // Counting each would walk the backoff up before the first attempt.
        assertEquals(1, recorder.attempts.size)
        assertEquals(ConnectionState.Reconnecting(1), watchdog.state.value)
    }

    @Test
    fun `a camera unwatched and healthy is not repaired on the way back`() = runTest {
        val recorder = recorder()
        val watchdog = watchdog(recorder)
        watchdog.start()
        watchdog.onPlayerEvent(PlayerEvent.Playing)
        runCurrent()

        watchdog.onVideoDisabled()
        advanceTimeBy(30_000)
        watchdog.onVideoEnabled()
        watchdog.onPlayerEvent(PlayerEvent.Playing)
        runCurrent()

        // Nothing went wrong, so coming back costs a keyframe and nothing else.
        assertTrue(recorder.attempts.isEmpty())
        assertEquals(ConnectionState.Live, watchdog.state.value)
    }

    @Test
    fun `an audio tick does not date a frame a warm camera never drew`() = runTest {
        val recorder = recorder()
        val watchdog = watchdog(recorder)
        watchdog.start()
        watchdog.onPlayerEvent(PlayerEvent.Playing)
        runCurrent()
        val lastRealFrame = watchdog.lastFrameAtMs.value

        watchdog.onVideoDisabled()
        advanceTimeBy(10_000)
        watchdog.onPlayerEvent(PlayerEvent.TimeChanged(1_000))
        runCurrent()

        // The timestamp is what the pill offers instead of a picture. Moving it
        // on for audio would say a frame arrived ten seconds after the last one
        // that actually did.
        assertEquals(lastRealFrame, watchdog.lastFrameAtMs.value)
    }

    @Test
    fun `a restart abandoned when the camera stops being watched is not left in flight`() = runTest {
        val recorder = recorder()
        val watchdog = watchdog(recorder)
        watchdog.start()
        watchdog.onPlayerEvent(PlayerEvent.Playing)
        runCurrent()

        // A stall starts a restart, and the user opens another camera before its
        // backoff is out.
        watchdog.onPlayerEvent(PlayerEvent.Error)
        runCurrent()
        watchdog.onVideoDisabled()
        advanceTimeBy(30_000)

        // Finishing it would have put a reconnect in flight for a picture nobody
        // was waiting for, and left the events that followed ambiguous between
        // the old session and the new.
        assertTrue(recorder.attempts.isEmpty())

        watchdog.onVideoEnabled()
        runCurrent()

        // The failure is not forgotten, though — it is settled on the way back.
        assertEquals(1, recorder.attempts.size)
    }

    @Test
    fun `a network drop while unwatched is repaired on the way back`() = runTest {
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
        advanceTimeBy(10_000)

        // The Wi-Fi coming back is not a reason to reconnect a room nobody is
        // looking at, ahead of the one they are.
        assertTrue(recorder.attempts.isEmpty())

        watchdog.onVideoEnabled()
        runCurrent()

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
        advanceTimeBy(timings.stallTimeoutMs + 100)
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
    fun `coming back to a camera with no network still reports offline`() = runTest {
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

        // Asking for the picture retracts a claim of Live, because the decoder
        // went with the track. It must not talk over a state that already says
        // something truer — a room with no network to reach is offline, not
        // connecting, and saying otherwise promises a picture that cannot come.
        watchdog.onVideoEnabled()
        advanceTimeBy(10_000)

        assertEquals(ConnectionState.Offline, watchdog.state.value)
        assertTrue(recorder.attempts.isEmpty())
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
        advanceTimeBy(timings.connectTimeoutMs + firstBackoffMs + 1)

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
        advanceTimeBy(firstBackoffMs + 1)

        assertEquals(1, recorder.attempts.size)
    }
}

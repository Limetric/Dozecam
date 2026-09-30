import Foundation
import Testing

@testable import Dozecam

/// The port of Android's `PlaybackWatchdogTest`, test for test and by the same
/// names. The stall and backoff timings are the shared rule in
/// `shared/fixtures/playback-watchdog/timings.json`: the watchdog runs with
/// its own defaults and every reconnect time asserted here is derived from
/// that file, so the two cannot drift apart.
@MainActor
struct PlaybackWatchdogTests {
    struct Timings: Codable {
        let stallTimeoutMs: Int64
        let connectTimeoutMs: Int64
        let backoffMs: [Int64]
    }

    let timings: Timings
    let scheduler = ManualScheduler()
    let recorder = ReconnectRecorder()

    /// The wait before the first reconnect attempt.
    var firstBackoffMs: Int64 { timings.backoffMs[0] }

    init() throws {
        timings = try Fixtures.decode(Timings.self, from: "playback-watchdog/timings.json")
    }

    @MainActor
    final class ReconnectRecorder {
        var attempts: [Int64] = []
    }

    func watchdog() -> PlaybackWatchdog {
        let scheduler = scheduler
        let recorder = recorder
        return PlaybackWatchdog(
            scheduler: scheduler,
            wallClock: { Date(timeIntervalSince1970: Double(scheduler.nowMs) / 1_000) },
            onReconnect: { recorder.attempts.append(scheduler.nowMs) })
    }

    func live() -> PlaybackWatchdog {
        let watchdog = watchdog()
        watchdog.start()
        watchdog.onPlayerEvent(.playing)
        scheduler.runCurrent()
        return watchdog
    }

    // MARK: - The fixture itself

    @Test func theDefaultsAreTheSharedTimings() {
        let config = PlaybackWatchdog.Config()
        #expect(config.stallTimeoutMs == timings.stallTimeoutMs)
        #expect(config.connectTimeoutMs == timings.connectTimeoutMs)
        // Entry n - 1 is the wait before attempt n, and the last entry holds
        // for every attempt after the list.
        for attempt in 1...(timings.backoffMs.count + 3) {
            let expected = timings.backoffMs[min(attempt, timings.backoffMs.count) - 1]
            #expect(config.backoffMs(forAttempt: attempt) == expected, "timings.json: backoffMs for attempt \(attempt)")
        }
    }

    // MARK: - Android's PlaybackWatchdogTest

    @Test("frames drive the state to live and record the frame time")
    func framesDriveTheStateToLive() {
        let watchdog = watchdog()
        watchdog.start()
        watchdog.onPlayerEvent(.playing)
        scheduler.runCurrent()

        #expect(watchdog.state == .live)
        #expect(watchdog.lastFrameAt == Date(timeIntervalSince1970: 0))
        #expect(recorder.attempts.isEmpty)
    }

    @Test("stall while live triggers reconnect after backoff")
    func stallWhileLive() {
        let watchdog = live()

        // No frames for stallTimeoutMs, then the first backoff before reconnect.
        let reconnectAt = timings.stallTimeoutMs + firstBackoffMs
        scheduler.advance(by: reconnectAt + 1)

        #expect(recorder.attempts == [reconnectAt], "timings.json: stallTimeoutMs + backoffMs[0]")
        #expect(watchdog.state == .reconnecting(attempt: 1))
    }

    @Test("repeated failures back off exponentially up to the cap")
    func repeatedFailuresBackOff() {
        let watchdog = watchdog()
        watchdog.start()

        // Never any frames: every connect attempt times out and retries after
        // the next backoff in the fixture, doubling up to its cap.
        scheduler.advance(by: timings.backoffMs.map { timings.connectTimeoutMs + $0 }.reduce(0, +) + 1)

        let attempts = recorder.attempts
        let gaps = zip(attempts.dropFirst(), attempts).map { $0 - $1 }
        #expect(gaps.count >= timings.backoffMs.count - 1, "expected several attempts, got \(attempts)")
        #expect(attempts.first == timings.connectTimeoutMs + firstBackoffMs)
        for (index, backoffMs) in timings.backoffMs.dropFirst().enumerated() where index < gaps.count {
            #expect(
                gaps[index] == timings.connectTimeoutMs + backoffMs,
                "timings.json: gap before attempt \(index + 2) = connectTimeoutMs + backoffMs[\(index + 1)]")
        }
        #expect(watchdog.state == .reconnecting(attempt: attempts.count))
    }

    @Test("player error triggers reconnect and recovery resets attempts")
    func playerErrorTriggersReconnect() {
        let watchdog = live()

        watchdog.onPlayerEvent(.error)
        scheduler.advance(by: firstBackoffMs + 1)
        #expect(recorder.attempts.count == 1)
        #expect(watchdog.state == .reconnecting(attempt: 1))

        watchdog.onPlayerEvent(.playing)
        scheduler.runCurrent()
        #expect(watchdog.state == .live)

        // The next failure starts back at attempt 1 with the initial backoff.
        watchdog.onPlayerEvent(.error)
        scheduler.advance(by: firstBackoffMs + 1)
        #expect(recorder.attempts.count == 2)
        #expect(watchdog.state == .reconnecting(attempt: 1))
    }

    @Test("network loss parks the watchdog offline until network returns")
    func networkLossParksOffline() {
        let watchdog = live()

        watchdog.onNetworkLost()
        scheduler.runCurrent()
        #expect(watchdog.state == .offline)

        // Parked: no reconnect attempts accumulate while offline.
        scheduler.advance(by: 30_000)
        #expect(recorder.attempts.isEmpty)

        watchdog.onNetworkAvailable()
        scheduler.runCurrent()
        #expect(recorder.attempts.count == 1)
        #expect(watchdog.state == .reconnecting(attempt: 1))
    }

    @Test("network up while live does not restart the stream")
    func networkUpWhileLive() {
        let watchdog = live()

        watchdog.onNetworkAvailable()
        scheduler.runCurrent()

        #expect(recorder.attempts.isEmpty)
        #expect(watchdog.state == .live)
    }

    @Test("teardown stop echo during recovery is ignored")
    func teardownStopEcho() {
        let watchdog = live()

        watchdog.onPlayerEvent(.error)
        scheduler.advance(by: firstBackoffMs + 1)  // reconnect issued; awaiting recovery
        #expect(recorder.attempts.count == 1)

        // The old session's stopped event arrives after our own teardown.
        watchdog.onPlayerEvent(.stopped)
        scheduler.runCurrent()

        #expect(recorder.attempts.count == 1)
        #expect(watchdog.state == .reconnecting(attempt: 1))
    }

    @Test("frames arriving while offline never flip the state back to live")
    func framesWhileOffline() {
        let watchdog = live()

        watchdog.onNetworkLost()
        scheduler.runCurrent()
        // Buffered frames trickle in after the network dropped.
        scheduler.advance(by: 1_000)
        watchdog.onPlayerEvent(.timeChanged(milliseconds: 1_000))
        scheduler.runCurrent()

        #expect(watchdog.state == .offline)
        // They still date the picture.
        #expect(watchdog.lastFrameAt == Date(timeIntervalSince1970: 1))
    }

    @Test("restart after stop begins in connecting state")
    func restartAfterStop() {
        let watchdog = live()
        #expect(watchdog.state == .live)

        watchdog.stop()
        watchdog.start()

        #expect(watchdog.state == .connecting)
    }

    @Test("network loss during backoff aborts the pending reconnect")
    func networkLossDuringBackoff() {
        let watchdog = live()

        watchdog.onPlayerEvent(.error)
        scheduler.runCurrent()  // enter the backoff window
        watchdog.onNetworkLost()
        scheduler.advance(by: 10_000)

        #expect(recorder.attempts.isEmpty)
        #expect(watchdog.state == .offline)
    }

    @Test("frames resuming during backoff cancel the restart")
    func framesResumingDuringBackoff() {
        let watchdog = live()

        // The stall fires, then the stream recovers inside the first backoff
        // window. Advance just past that window: the pending restart must have
        // been cancelled, not merely delayed.
        scheduler.advance(by: timings.stallTimeoutMs + 100)
        watchdog.onPlayerEvent(.timeChanged(milliseconds: 1_000))
        scheduler.advance(by: firstBackoffMs + 100)

        #expect(recorder.attempts.isEmpty)
        #expect(watchdog.state == .live)
    }

    @Test("repeated buffering events do not defer stall detection")
    func bufferingDoesNotDeferStall() {
        let watchdog = live()

        // A frozen stream that keeps emitting buffering callbacks must still
        // stall stallTimeoutMs after the last frame, and reconnect after the
        // first backoff.
        let reconnectAt = timings.stallTimeoutMs + firstBackoffMs
        scheduler.advance(by: 1_000)
        watchdog.onPlayerEvent(.buffering)
        scheduler.runCurrent()
        scheduler.advance(by: 1_000)
        watchdog.onPlayerEvent(.buffering)
        scheduler.runCurrent()
        scheduler.advance(by: reconnectAt + 100 - 2_000)

        #expect(recorder.attempts == [reconnectAt], "timings.json: stallTimeoutMs + backoffMs[0]")
    }

    @Test("events queued while stopped are discarded on restart")
    func eventsWhileStopped() {
        let watchdog = live()

        watchdog.stop()
        watchdog.onPlayerEvent(.error)  // stale failure from the old session
        watchdog.start()
        scheduler.runCurrent()
        scheduler.advance(by: firstBackoffMs * 2)

        #expect(recorder.attempts.isEmpty)
        #expect(watchdog.state == .connecting)
    }

    @Test("a camera nobody is watching is not declared stalled")
    func unwatchedNotStalled() {
        let watchdog = live()

        // Opening one camera drops the video track on the rest. No frames will
        // ever arrive for them, and reading that as a stall would reconnect
        // the very sessions being kept warm.
        watchdog.onVideoDisabled()
        scheduler.advance(by: 60_000)

        #expect(recorder.attempts.isEmpty, "kept warm but reconnected: \(recorder.attempts)")
        #expect(watchdog.state == .live)
    }

    @Test("nothing is restarted for a camera nobody is watching")
    func nothingRestartedUnwatched() {
        let watchdog = live()
        watchdog.onVideoDisabled()
        scheduler.runCurrent()

        watchdog.onPlayerEvent(.error)
        watchdog.onPlayerEvent(.stopped)
        scheduler.advance(by: 60_000)

        #expect(recorder.attempts.isEmpty, "restarted while unwatched: \(recorder.attempts)")
    }

    @Test("a camera that broke while unwatched is repaired the moment it is wanted")
    func brokeWhileUnwatchedRepairedWhenWanted() {
        let watchdog = live()
        watchdog.onVideoDisabled()
        scheduler.runCurrent()

        watchdog.onPlayerEvent(.error)
        scheduler.advance(by: 30_000)
        #expect(recorder.attempts.isEmpty)

        watchdog.onVideoEnabled()
        scheduler.runCurrent()

        // Answered at once rather than after a backoff spent looking at nothing.
        #expect(recorder.attempts.count == 1)
        #expect(watchdog.state == .reconnecting(attempt: 1))
    }

    @Test("several failures while unwatched still cost one repair")
    func severalFailuresOneRepair() {
        let watchdog = live()
        watchdog.onVideoDisabled()
        scheduler.runCurrent()

        watchdog.onPlayerEvent(.error)
        watchdog.onPlayerEvent(.stopped)
        watchdog.onPlayerEvent(.error)
        scheduler.advance(by: 10_000)

        watchdog.onVideoEnabled()
        scheduler.runCurrent()

        #expect(recorder.attempts.count == 1)
        #expect(watchdog.state == .reconnecting(attempt: 1))
    }

    @Test("a camera unwatched and healthy is not repaired on the way back")
    func unwatchedHealthyNotRepaired() {
        let watchdog = live()

        watchdog.onVideoDisabled()
        scheduler.advance(by: 30_000)
        watchdog.onVideoEnabled()
        watchdog.onPlayerEvent(.playing)
        scheduler.runCurrent()

        #expect(recorder.attempts.isEmpty)
        #expect(watchdog.state == .live)
    }

    @Test("an audio tick does not date a frame a warm camera never drew")
    func audioTickDoesNotDateAFrame() {
        let watchdog = live()
        let lastRealFrame = watchdog.lastFrameAt

        watchdog.onVideoDisabled()
        scheduler.advance(by: 10_000)
        watchdog.onPlayerEvent(.timeChanged(milliseconds: 1_000))
        scheduler.runCurrent()

        #expect(watchdog.lastFrameAt == lastRealFrame)
    }

    @Test("a restart abandoned when the camera stops being watched is not left in flight")
    func abandonedRestartNotLeftInFlight() {
        let watchdog = live()

        // A failure starts a restart, and the user opens another camera before
        // its backoff is out.
        watchdog.onPlayerEvent(.error)
        scheduler.runCurrent()
        watchdog.onVideoDisabled()
        scheduler.advance(by: 30_000)

        #expect(recorder.attempts.isEmpty)

        watchdog.onVideoEnabled()
        scheduler.runCurrent()

        // Not forgotten, though: settled on the way back.
        #expect(recorder.attempts.count == 1)
    }

    @Test("a network drop while unwatched is repaired on the way back")
    func networkDropWhileUnwatched() {
        let watchdog = live()
        watchdog.onVideoDisabled()
        scheduler.runCurrent()

        watchdog.onNetworkLost()
        scheduler.runCurrent()
        #expect(watchdog.state == .offline)

        watchdog.onNetworkAvailable()
        scheduler.advance(by: 10_000)

        // The Wi-Fi coming back is no reason to reconnect a room nobody is
        // looking at, ahead of the one they are.
        #expect(recorder.attempts.isEmpty)

        watchdog.onVideoEnabled()
        scheduler.runCurrent()

        #expect(recorder.attempts.count == 1)
    }

    @Test("coming back to a camera allows for the wait on a keyframe")
    func comingBackAllowsForKeyframe() {
        let watchdog = live()
        watchdog.onVideoDisabled()
        scheduler.advance(by: 30_000)

        watchdog.onVideoEnabled()
        // The decoder cannot paint until the next keyframe, which is longer
        // than the stall allowance the camera was live under.
        scheduler.advance(by: timings.stallTimeoutMs + 100)
        #expect(recorder.attempts.isEmpty)

        watchdog.onPlayerEvent(.playing)
        scheduler.runCurrent()
        #expect(watchdog.state == .live)
        #expect(recorder.attempts.isEmpty)
    }

    @Test("a camera coming back does not claim to be live before it paints")
    func comingBackNotLiveBeforePainting() {
        let watchdog = live()
        watchdog.onVideoDisabled()
        scheduler.advance(by: 30_000)
        #expect(watchdog.state == .live)

        watchdog.onVideoEnabled()
        scheduler.runCurrent()

        // Its decoder went away with the track, so nothing is on screen yet.
        #expect(watchdog.state == .connecting)

        watchdog.onPlayerEvent(.playing)
        scheduler.runCurrent()
        #expect(watchdog.state == .live)
    }

    @Test("coming back to a camera with no network still reports offline")
    func comingBackWithNoNetwork() {
        let watchdog = live()
        watchdog.onVideoDisabled()
        scheduler.runCurrent()

        watchdog.onNetworkLost()
        scheduler.runCurrent()
        #expect(watchdog.state == .offline)

        watchdog.onVideoEnabled()
        scheduler.advance(by: 10_000)

        #expect(watchdog.state == .offline)
        #expect(recorder.attempts.isEmpty)
    }

    @Test("a camera that never comes back is reconnected once it is watched again")
    func neverComesBack() {
        let watchdog = live()
        watchdog.onVideoDisabled()
        scheduler.advance(by: 30_000)

        watchdog.onVideoEnabled()
        scheduler.advance(by: timings.connectTimeoutMs + firstBackoffMs + 1)

        #expect(recorder.attempts.count == 1)
    }

    @Test("frames on the audio clock cannot keep a warm camera on a stall timer")
    func audioClockNoStallTimer() {
        let watchdog = watchdog()
        watchdog.start()
        watchdog.onVideoDisabled()
        scheduler.runCurrent()

        watchdog.onPlayerEvent(.timeChanged(milliseconds: 1_000))
        scheduler.advance(by: 60_000)

        #expect(recorder.attempts.isEmpty)
    }

    @Test("stopped when idle live counts as a failure")
    func stoppedWhenLiveIsAFailure() {
        let watchdog = live()

        watchdog.onPlayerEvent(.stopped)
        scheduler.advance(by: firstBackoffMs + 1)

        #expect(recorder.attempts.count == 1)
    }

    // MARK: - iOS specifics

    @Test func aPictureShapeIsNotAFrame() {
        let watchdog = live()
        scheduler.advance(by: 2_000)
        watchdog.onPlayerEvent(.videoAspect(16.0 / 9.0))
        scheduler.advance(by: timings.stallTimeoutMs - 2_000 + firstBackoffMs + 1)

        #expect(recorder.attempts == [timings.stallTimeoutMs + firstBackoffMs])
    }

    /// A restart whose player echoes `stopped` from inside `play` is handled
    /// after the restart, as the channel on Android would order it.
    @Test func anEchoFromInsideTheRestartIsTheWatchdogsOwn() {
        final class Echo {
            weak var watchdog: PlaybackWatchdog?
            var restarts = 0
        }
        let echo = Echo()
        let watchdog = PlaybackWatchdog(scheduler: scheduler) { @MainActor in
            echo.restarts += 1
            echo.watchdog?.onPlayerEvent(.stopped)
        }
        echo.watchdog = watchdog
        watchdog.start()
        watchdog.onPlayerEvent(.playing)
        watchdog.onPlayerEvent(.error)
        scheduler.advance(by: firstBackoffMs + 1)

        #expect(echo.restarts == 1)
        #expect(watchdog.state == .reconnecting(attempt: 1))
    }

    @Test func aWallClockJumpMovesNoDeadline() {
        final class Wall {
            var now = Date(timeIntervalSince1970: 1_000)
        }
        let wall = Wall()
        let recorder = recorder
        let scheduler = scheduler
        let watchdog = PlaybackWatchdog(
            scheduler: scheduler, wallClock: { wall.now }, onReconnect: { recorder.attempts.append(scheduler.nowMs) })
        watchdog.start()
        watchdog.onPlayerEvent(.playing)
        wall.now = Date(timeIntervalSince1970: 1_000_000)  // the phone's clock set forward
        scheduler.advance(by: timings.stallTimeoutMs - 1)

        #expect(watchdog.state == .live)
        #expect(recorder.attempts.isEmpty)
    }
}

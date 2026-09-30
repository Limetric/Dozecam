import Foundation
import Testing

@testable import Dozecam

/// An audio player that plays nothing: the test says what it decodes.
@MainActor
final class FakeAudioPlayer: AudioPlayer {
    var onEvent: ((AudioPlayerEvent) -> Void)?
    private(set) var plays: [StreamSource] = []
    private(set) var released = false

    func play(_ source: StreamSource) { plays.append(source) }
    func stop() {}
    func release() { released = true }

    func emit(_ event: AudioPlayerEvent) { onEvent?(event) }
}

/// The port of Android's `CameraAudioMonitor` behaviour, on a manual clock:
/// the watchdog's timings are the shared ones
/// (`shared/fixtures/playback-watchdog/timings.json`, checked by
/// `PlaybackWatchdogTests`), and the fallback count the one in
/// `shared/fixtures/transport-fallback/fallback.json`.
@MainActor
struct CameraAudioMonitorTests {
    let scheduler = ManualScheduler()
    let player = FakeAudioPlayer()
    let config = PlaybackWatchdog.Config()
    static let rtsp = StreamSource.rtsp(url: "rtsp://console:7447/nursery")
    static let livestream = StreamSource.livestream(cameraId: "abc", channel: 0)

    final class Batches {
        var received: [[LevelSample]] = []
    }

    func monitor(_ transports: [StreamSource] = [rtsp, livestream]) -> CameraAudioMonitor {
        let player = player
        return CameraAudioMonitor(cameraId: "nursery", transports: transports, scheduler: scheduler) { player }
    }

    /// One buffer decoded now.
    func decode(_ rms: Float) {
        player.emit(.levels([LevelSample(rms: rms, atMs: scheduler.nowMs)]))
    }

    /// Lets a stall be detected and waits out its backoff: one restart.
    func stallUntilRestart(attempt: Int) {
        scheduler.advance(by: config.stallTimeoutMs + config.backoffMs(forAttempt: attempt))
    }

    /// A connection that never decodes: the connect timeout, then the backoff.
    func failToConnect(attempt: Int) {
        scheduler.advance(by: config.connectTimeoutMs + config.backoffMs(forAttempt: attempt))
    }

    @Test func startingPlaysTheBestTransportAndConnects() {
        let monitor = monitor()
        monitor.start()

        #expect(player.plays == [Self.rtsp])
        #expect(monitor.connection == .connecting)
        #expect(monitor.level == nil)
        #expect(!monitor.isAudible)
    }

    /// libVLC says "playing" before any sample decodes, so it proves nothing.
    @Test func playingIsNotABuffer() {
        let monitor = monitor()
        monitor.start()
        player.emit(.playing)

        #expect(monitor.connection == .connecting)
        #expect(monitor.level == nil)
    }

    @Test func theFirstBufferMakesTheRoomLiveAndAudibleWithALevel() {
        let monitor = monitor()
        let batches = Batches()
        monitor.onLevels = { batches.received.append($0) }
        monitor.start()

        decode(0.02)

        #expect(monitor.connection == .live)
        #expect(monitor.level == 0.02)
        #expect(monitor.isAudible)
        #expect(batches.received == [[LevelSample(rms: 0.02, atMs: 0)]])
    }

    /// Every buffer reaches the detector with its own time; the meter shows
    /// the batch's peak.
    @Test func aBatchReachesTheDetectorWholeAndTheMeterShowsItsPeak() {
        let monitor = monitor()
        let batches = Batches()
        monitor.onLevels = { batches.received.append($0) }
        monitor.start()

        let batch = [LevelSample(rms: 0.1, atMs: 0), LevelSample(rms: 0.4, atMs: 21), LevelSample(rms: 0.2, atMs: 42)]
        player.emit(.levels(batch))

        #expect(batches.received == [batch])
        #expect(monitor.level == 0.4)
        #expect(monitor.lastAudioAtMs == 42)
    }

    @Test func audioThatStopsArrivingIsAStallAndReconnects() {
        let monitor = monitor()
        monitor.start()
        decode(0.05)

        scheduler.advance(by: config.stallTimeoutMs)
        #expect(monitor.connection == .reconnecting(attempt: 1))
        #expect(monitor.level == nil, "a connection that stops being live forgets its level")
        #expect(!monitor.isAudible)

        scheduler.advance(by: config.backoffMs(forAttempt: 1))
        #expect(player.plays == [Self.rtsp, Self.rtsp])
    }

    @Test func buffersKeepALiveRoomLive() {
        let monitor = monitor()
        monitor.start()
        for _ in 0..<10 {
            decode(0.05)
            scheduler.advance(by: config.stallTimeoutMs - 100)
        }
        #expect(monitor.connection == .live)
        #expect(player.plays.count == 1)
    }

    @Test func theLevelIsForgottenOnReconnectUntilTheNewConnectionDecodes() {
        let monitor = monitor()
        monitor.start()
        decode(0.3)
        player.emit(.error)
        scheduler.advance(by: config.backoffMs(forAttempt: 1))

        #expect(player.plays.count == 2)
        #expect(monitor.level == nil)
        #expect(monitor.lastAudioAtMs == nil)

        decode(0.01)
        #expect(monitor.connection == .live)
        #expect(monitor.level == 0.01)
    }

    @Test func aPlayerErrorReconnectsAfterTheBackoff() {
        let monitor = monitor()
        monitor.start()
        decode(0.05)
        player.emit(.error)

        #expect(monitor.connection == .reconnecting(attempt: 1))
        scheduler.advance(by: config.backoffMs(forAttempt: 1) - 1)
        #expect(player.plays.count == 1)
        scheduler.advance(by: 1)
        #expect(player.plays.count == 2)
    }

    @Test func aStreamThatEndsOnItsOwnReconnects() {
        let monitor = monitor()
        monitor.start()
        decode(0.05)
        player.emit(.stopped)
        #expect(monitor.connection == .reconnecting(attempt: 1))
    }

    /// A transport that plays without ever yielding a sample is given up on
    /// after the shared count of restarts, for the livestream.
    @Test func aTransportThatNeverDecodesFallsBackToTheLivestream() throws {
        let restarts = try Fixtures.decode(
            TransportFallbackTests.Fixture.self, from: "transport-fallback/fallback.json"
        ).restartsBeforeFallback
        let monitor = monitor()
        monitor.start()

        for attempt in 1..<restarts {
            failToConnect(attempt: attempt)
            #expect(player.plays.last == Self.rtsp, "restart \(attempt) stays on RTSP")
        }
        failToConnect(attempt: restarts)

        #expect(player.plays.last == Self.livestream)
        #expect(monitor.transportIndex == 1)
        #expect(player.plays.count == restarts + 1)
    }

    @Test func errorsWithoutAudioFallBackToo() {
        let monitor = monitor()
        monitor.start()
        for attempt in 1...3 {
            player.emit(.error)
            scheduler.advance(by: config.backoffMs(forAttempt: attempt))
        }
        #expect(player.plays.last == Self.livestream)
    }

    @Test func aTransportThatHasDecodedIsKeptThroughLaterTrouble() {
        let monitor = monitor()
        monitor.start()
        decode(0.05)
        for attempt in 1...10 {
            player.emit(.error)
            scheduler.advance(by: config.backoffMs(forAttempt: attempt) + config.connectTimeoutMs)
        }
        #expect(player.plays.allSatisfy { $0 == Self.rtsp })
    }

    @Test func aLoneTransportIsNeverAbandoned() {
        let monitor = monitor([Self.rtsp])
        monitor.start()
        for attempt in 1...6 { failToConnect(attempt: attempt) }
        #expect(player.plays.allSatisfy { $0 == Self.rtsp })
        #expect(player.plays.count == 7)
    }

    /// Decided as the restart is made: a stream that recovers during its
    /// backoff stays on its transport.
    @Test func recoveringDuringTheBackoffSkipsTheRestart() {
        let monitor = monitor()
        monitor.start()
        player.emit(.error)
        decode(0.05)
        // Past the moment the restart was due, and short of a stall.
        scheduler.advance(by: config.backoffMs(forAttempt: 1) + 1)

        #expect(monitor.connection == .live)
        #expect(player.plays == [Self.rtsp])
    }

    // MARK: - Network

    @Test func losingTheNetworkGoesOfflineAtOnceWithoutRetrying() {
        let monitor = monitor()
        monitor.start()
        decode(0.05)

        monitor.onNetworkLost()
        #expect(monitor.connection == .offline)
        #expect(monitor.level == nil)
        scheduler.advance(by: 60_000)
        #expect(player.plays.count == 1)

        // Buffers still draining are the past: they do not make it live.
        decode(0.05)
        #expect(monitor.connection == .offline)
        #expect(!monitor.isAudible)
    }

    @Test func theNetworkComingBackReconnectsAtOnce() {
        let monitor = monitor()
        monitor.start()
        decode(0.05)
        monitor.onNetworkLost()

        monitor.onNetworkAvailable()
        #expect(player.plays.count == 2)
        #expect(monitor.connection == .reconnecting(attempt: 1))

        decode(0.05)
        #expect(monitor.connection == .live)
    }

    // MARK: - Lifecycle

    @Test func aCameraWithNoTransportIsNeverStarted() {
        let monitor = monitor([])
        monitor.start()
        #expect(player.plays.isEmpty)
    }

    @Test func stoppingReleasesThePlayerAndIgnoresLateEvents() {
        let monitor = monitor()
        let batches = Batches()
        monitor.onLevels = { batches.received.append($0) }
        monitor.start()
        monitor.stop()

        #expect(player.released)
        decode(0.5)
        player.emit(.error)
        scheduler.advance(by: 60_000)
        #expect(batches.received.isEmpty)
        #expect(player.plays.count == 1)
    }
}

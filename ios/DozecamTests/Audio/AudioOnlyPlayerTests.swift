import Foundation
import Network
import Testing

@testable import Dozecam

/// Whether `tools/testbed.sh` is serving RTSP on this Mac: the simulator
/// shares its loopback. A quick TCP connect, so CI (no testbed) skips.
private let testbedReachable: Bool = {
    let connection = NWConnection(host: "127.0.0.1", port: 18_554, using: .tcp)
    let ready = DispatchSemaphore(value: 0)
    let result = Locked(false)
    connection.stateUpdateHandler = { state in
        switch state {
        case .ready:
            result.value = true
            ready.signal()
        case .failed, .waiting, .cancelled:
            ready.signal()
        default:
            break
        }
    }
    connection.start(queue: DispatchQueue(label: "testbed-probe"))
    _ = ready.wait(timeout: .now() + 1)
    connection.cancel()
    return result.value
}()

/// Records an audio player's events.
@MainActor
final class AudioEventLog {
    private(set) var events: [AudioPlayerEvent] = []

    init(_ player: any AudioPlayer) {
        player.onEvent = { [weak self] in self?.events.append($0) }
    }

    var levels: [LevelSample] {
        events.flatMap { event -> [LevelSample] in
            if case .levels(let batch) = event { batch } else { [] }
        }
    }

    var failed: Bool { events.contains(.error) || events.contains(.stopped) }

    func wait(for timeout: Duration = .seconds(15), until condition: (AudioEventLog) -> Bool) async -> Bool {
        let deadline = ContinuousClock.now + timeout
        while ContinuousClock.now < deadline {
            if condition(self) { return true }
            try? await Task.sleep(for: .milliseconds(50))
        }
        return condition(self)
    }
}

/// The monitor's audio-only libVLC player against the local testbed
/// (`tools/testbed.sh start`): what #59's spike measured, now through the
/// product code.
///
/// The nursery's audio is silence unless `tools/testbed.sh noise on` made it a
/// loud tone. The testbed records which in its runtime directory, which the
/// simulator can read, so the level is checked against whichever is playing:
/// about 0.35 for the tone (#59), 0 for silence.
@MainActor
@Suite(.serialized, .enabled(if: testbedReachable, "the RTSP testbed is not running on 127.0.0.1:18554"))
struct AudioOnlyPlayerTests {
    static let nursery = "rtsp://127.0.0.1:18554/nursery"

    /// "noise" or "silent", as `tools/testbed.sh` last set the nursery.
    static var nurseryAudio: String? {
        let dir = ProcessInfo.processInfo.environment["DOZECAM_TESTBED_DIR"] ?? "/tmp/dozecam-testbed"
        return (try? String(contentsOfFile: "\(dir)/cam-nursery.audio", encoding: .utf8))?
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// Plays and waits for `condition`, starting over once if the first
    /// session shows nothing: VLC 4's live555 now and then stays buffering on
    /// a connect to mediamtx (#84), which the watchdog's connect timeout
    /// handles in the app.
    private func play(
        _ url: String, on player: AudioOnlyPlayer, log: AudioEventLog, until condition: (AudioEventLog) -> Bool
    ) async -> Bool {
        player.play(.rtsp(url: url))
        if await log.wait(for: .seconds(8), until: condition) { return true }
        player.play(.rtsp(url: url))
        return await log.wait(for: .seconds(12), until: condition)
    }

    @Test func theNurseryDecodesIntoLevelsAndTheSpeakerSink() async throws {
        let sink = SpeakerSink(cameraId: "nursery")
        let player = AudioOnlyPlayer(sink: sink)
        defer { player.release() }
        let log = AudioEventLog(player)

        // A second of audio.
        let decoded = await play(Self.nursery, on: player, log: log) { $0.levels.count >= 40 }
        #expect(decoded, "\(log.events.prefix(20))")
        #expect(sink.queued > 0)
        #expect(!log.failed)

        let levels = log.levels
        let times = levels.map(\.atMs)
        #expect(times == times.sorted(), "levels arrive oldest first")
        // Buffers come about every 20 ms of audio; a second's worth spans a
        // stretch of the monotonic clock, not one instant.
        #expect(times.last! - times.first! >= 300)

        let tail = levels.suffix(20).map(\.rms)
        let mean = tail.reduce(0, +) / Float(tail.count)
        print("nursery audio \(Self.nurseryAudio ?? "?"): mean level \(mean) over \(tail.count) buffers")
        switch Self.nurseryAudio {
        case "noise": #expect(abs(mean - 0.35) < 0.03, "the testbed's tone measures ~0.35 (#59): \(mean)")
        case "silent": #expect(mean < 0.001, "the silent nursery: \(mean)")
        default: #expect(mean >= 0 && mean <= 1)
        }
    }

    @Test func libVLCSaysPlaying() async {
        let player = AudioOnlyPlayer(sink: nil)
        defer { player.release() }
        let log = AudioEventLog(player)
        #expect(await play(Self.nursery, on: player, log: log) { $0.events.contains(.playing) })
    }

    @Test func aStreamThatDoesNotExistFailsWithoutLevels() async {
        let player = AudioOnlyPlayer(sink: nil)
        defer { player.release() }
        let log = AudioEventLog(player)

        player.play(.rtsp(url: "rtsp://127.0.0.1:18554/no-such-camera"))

        #expect(await log.wait { $0.failed })
        #expect(log.levels.isEmpty)
    }

    /// A reconnect is a new libVLC player; the old one is retired without
    /// reporting anything further.
    @Test func aReconnectDecodesAgainOnTheSamePlayer() async throws {
        let player = AudioOnlyPlayer(sink: nil)
        defer { player.release() }
        let log = AudioEventLog(player)
        #expect(await play(Self.nursery, on: player, log: log) { !$0.levels.isEmpty })

        player.stop()
        try await Task.sleep(for: .milliseconds(300))
        let afterStop = log.events.count
        try await Task.sleep(for: .milliseconds(500))
        #expect(log.events.count == afterStop, "a stopped session reports nothing more")

        let before = log.levels.count
        #expect(await play(Self.nursery, on: player, log: log) { $0.levels.count > before + 5 })
    }

    /// The whole chain on the real player and clock: the monitor goes live
    /// and audible off decoded buffers.
    @Test func aMonitorOnTheTestbedBecomesAudible() async {
        let monitor = CameraAudioMonitor(cameraId: "nursery", transports: [.rtsp(url: Self.nursery)]) {
            AudioOnlyPlayer(sink: nil)
        }
        defer { monitor.stop() }
        let batches = Locked(0)
        monitor.onLevels = { _ in batches.value += 1 }
        monitor.start()

        let deadline = ContinuousClock.now + .seconds(20)
        while ContinuousClock.now < deadline, !monitor.isAudible {
            try? await Task.sleep(for: .milliseconds(50))
        }
        #expect(monitor.isAudible)
        #expect(monitor.connection == .live)
        #expect(monitor.level != nil)
        #expect(batches.value > 0)

        // Past the first burst libVLC hands over, buffers keep coming in
        // real time: longer than a stall, and it is still live.
        try? await Task.sleep(for: .seconds(4))
        #expect(monitor.connection == .live)
        #expect(monitor.isAudible)
    }
}

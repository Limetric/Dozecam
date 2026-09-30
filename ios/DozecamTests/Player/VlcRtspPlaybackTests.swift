import Foundation
import Network
import Testing

@testable import Dozecam

/// Whether `tools/testbed.sh` is serving RTSP on this Mac: the simulator
/// shares its loopback. A quick TCP connect, so CI (no testbed) skips.
private let testbedReachable: Bool = {
    let connection = NWConnection(host: "127.0.0.1", port: 18554, using: .tcp)
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

/// VLCKit over RTSP against the local testbed (`tools/testbed.sh start`).
@MainActor
@Suite(.serialized, .enabled(if: testbedReachable, "the RTSP testbed is not running on 127.0.0.1:18554"))
struct VlcRtspPlaybackTests {
    /// Plays `url` and waits for `condition`, restarting once with a fresh
    /// session if the first attempt shows nothing, as the watchdog's connect
    /// timeout does in the app: VLC 4's live555 now and then stays buffering
    /// on a connect to mediamtx (#84).
    private func play(
        _ url: String, on player: VlcVideoPlayerController, log: PlayerEventLog,
        until condition: (PlayerEventLog) -> Bool
    ) async -> Bool {
        player.play(.rtsp(url: url))
        if await log.wait(for: .seconds(8), until: condition) { return true }
        player.play(.rtsp(url: url))
        return await log.wait(for: .seconds(12), until: condition)
    }

    private func within(_ limit: Duration, until condition: () -> Bool) async -> Bool {
        let end = ContinuousClock.now + limit
        while ContinuousClock.now < end {
            if condition() { return true }
            try? await Task.sleep(for: .milliseconds(50))
        }
        return condition()
    }

    @Test func theTestbedNurseryPlaysFrameByFrame() async {
        let player = VlcVideoPlayerController()
        defer { player.release() }
        let log = PlayerEventLog(player)
        let window = PlayerWindow(player)
        defer { window.close() }
        player.setMuted(true)

        let playing = await play("rtsp://127.0.0.1:18554/nursery", on: player, log: log) {
            $0.events.contains(.playing) && $0.frames >= 3
        }
        #expect(playing, "\(log.events)")
        #expect(
            log.events.contains(where: {
                if case .videoAspect(let a) = $0 { abs(a - 16.0 / 9.0) < 0.01 } else { false }
            }))
        #expect(!log.events.contains(.error))
    }

    /// A muted camera holds no audio output, so it cannot take the speaker
    /// from another app; unmuting brings the room's sound back.
    @Test func aMutedCameraHasNoAudioTrackSelected() async {
        let player = VlcVideoPlayerController()
        defer { player.release() }
        let log = PlayerEventLog(player)
        let window = PlayerWindow(player)
        defer { window.close() }
        player.setMuted(true)

        #expect(await play("rtsp://127.0.0.1:18554/nursery", on: player, log: log) { $0.frames >= 3 })
        #expect(!player.isAudioSelected)

        player.setMuted(false)
        #expect(await within(.seconds(5)) { player.isAudioSelected })
        player.setMuted(true)
        #expect(await within(.seconds(5)) { !player.isAudioSelected })
    }

    @Test func aStreamThatDoesNotExistIsAnErrorNotALiveTile() async {
        let player = VlcVideoPlayerController()
        defer { player.release() }
        let log = PlayerEventLog(player)
        let window = PlayerWindow(player)
        defer { window.close() }

        player.play(.rtsp(url: "rtsp://127.0.0.1:18554/no-such-camera"))

        #expect(await log.wait { $0.events.contains(.error) || $0.events.contains(.stopped) })
        #expect(log.frames == 0)
        #expect(!log.events.contains(.playing))
    }

    @Test func aHiddenPictureTicksAgainOnceShown() async throws {
        let player = VlcVideoPlayerController()
        defer { player.release() }
        let log = PlayerEventLog(player)
        let window = PlayerWindow(player)
        defer { window.close() }
        player.setVideoEnabled(false)

        player.play(.rtsp(url: "rtsp://127.0.0.1:18554/porch"))
        try await Task.sleep(for: .seconds(2))
        #expect(log.frames == 0)

        player.setVideoEnabled(true)
        #expect(await log.wait(for: .seconds(8)) { $0.frames >= 2 })
    }

    @Test func aReconnectPlaysAgainOnTheSamePlayer() async throws {
        let player = VlcVideoPlayerController()
        defer { player.release() }
        let log = PlayerEventLog(player)
        let window = PlayerWindow(player)
        defer { window.close() }
        #expect(await play("rtsp://127.0.0.1:18554/nursery", on: player, log: log) { $0.frames >= 2 })

        let before = log.events.count(where: { $0 == .playing })
        #expect(
            await play("rtsp://127.0.0.1:18554/nursery", on: player, log: log) {
                $0.events.count(where: { $0 == .playing }) > before
            })
    }
}

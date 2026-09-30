import Foundation
import Testing

@testable import Dozecam

/// The livestream path end to end without a console: a small fMP4 in
/// Protect's wire framing, served by a loopback WebSocket, negotiated through
/// a stand-in for the provider, decoded, piped into libVLC and played in the
/// simulator. Serialized: each test decodes video, and they are timing-bound.
@MainActor
@Suite(.serialized)
struct LivestreamPlaybackTests {
    /// Serves `stream` as a camera does, one fragment per fragment duration,
    /// in 1000-byte messages that frames straddle; then keeps the socket open
    /// without sending more: a camera whose picture has frozen, not one that
    /// hung up. (Sent in one burst, libVLC, which paces a live input by its
    /// PCR, sees a clock gap per fragment and drops the lot as late.)
    private func serve(_ stream: [Data], thenClose: Bool = false) async throws -> LoopbackWebSocketServer {
        try await LoopbackWebSocketServer.start { connection in
            for (index, group) in stream.enumerated() {
                if index > 1 { try? await Task.sleep(for: Fmp4.fragmentDuration) }
                for message in group.messages(of: 1000) { await connection.send(binary: message) }
            }
            if thenClose {
                await connection.close(code: .protocolCode(.goingAway))
            } else {
                try? await Task.sleep(for: .seconds(30))
            }
        }
    }

    private func controller(for server: LoopbackWebSocketServer) -> LivestreamVideoPlayerController {
        let url = server.url
        return LivestreamVideoPlayerController { cameraId, channel in
            #expect(cameraId == "cam-1")
            #expect(channel == 0)
            return ProtectLivestreamProvider.Connection(url: url, urlSession: URLSession(configuration: .ephemeral))
        }
    }

    @Test func anH264LivestreamPlaysFrameByFrameThenStopsTickingWhenThePictureFreezes() async throws {
        let video = try Fmp4.resource("livestream-h264")
        let server = try await serve(video.protectFrames(codec: "avc1.42c00d"))
        defer { server.stop() }
        let player = controller(for: server)
        defer { player.release() }
        let log = PlayerEventLog(player)
        let window = PlayerWindow(player)
        defer { window.close() }

        player.play(.livestream(cameraId: "cam-1", channel: 0))

        #expect(await log.wait { $0.events.contains(.playing) && $0.frames >= 3 }, "\(log.events)")
        #expect(log.events.first(where: { $0 == .playing || $0.isTimeChanged }) == .playing)
        let aspect = log.events.lazy.compactMap { if case .videoAspect(let a) = $0 { a } else { nil } }.first
        #expect(abs((aspect ?? 0) - 16.0 / 9.0) < 0.01)
        #expect(!log.events.contains(.error))

        // Three seconds of video, then nothing: the ticks must stop with the
        // pictures, however long VLC's clock would carry on.
        #expect(await log.wait(for: .seconds(10)) { _ in log.quietFor(.seconds(1)) })
        let frozen = log.frames
        try await Task.sleep(for: .seconds(1.5))
        #expect(log.frames == frozen)
        #expect(frozen >= 5)
    }

    @Test func aProtectAv1LivestreamPlaysThroughDav1d() async throws {
        var video = try Fmp4.resource("livestream-av1")
        // As a UniFi camera writes it: an av1C with no config OBUs.
        try video.stripAv1ConfigObus()
        #expect(video.av1cSize == 12)
        let server = try await serve(video.protectFrames(codec: "av01.0.00M.08"))
        defer { server.stop() }
        let player = controller(for: server)
        defer { player.release() }
        let log = PlayerEventLog(player)
        let window = PlayerWindow(player)
        defer { window.close() }

        player.play(.livestream(cameraId: "cam-1", channel: 0))

        #expect(await log.wait { $0.events.contains(.playing) && $0.frames >= 3 }, "\(log.events)")
        #expect(log.unsupportedCodec == nil)
    }

    @Test func libVlcNeedsNoAv1RepairForProtectsEmptyConfigRecord() async throws {
        // The repair was Media3's workaround. Played here without it, straight
        // into the pipe, Protect's bare av1C still decodes.
        var video = try Fmp4.resource("livestream-av1")
        try video.stripAv1ConfigObus()
        let pipe = LivestreamPipe()
        let core = VlcPlayerCore(runtime: .shared)
        let window = PlayerWindow(view: core.view)
        defer {
            pipe.close()
            window.close()
            core.release()
        }
        var frames = 0
        core.onEvent = { if case .timeChanged = $0 { frames += 1 } }

        pipe.offer(video.initSegment)
        core.play(try #require(LivestreamMedia.make(reading: pipe)))
        let fragments = video.fragments
        let feeding = Task {
            for fragment in fragments {
                pipe.offer(fragment.moof + fragment.mdat)
                try? await Task.sleep(for: Fmp4.fragmentDuration)
            }
        }
        defer { feeding.cancel() }

        let deadline = ContinuousClock.now + .seconds(15)
        while frames < 3, ContinuousClock.now < deadline { try await Task.sleep(for: .milliseconds(50)) }
        #expect(frames >= 3)
    }

    @Test func aCodecWithNoDecoderSaysSoInsteadOfPlayingBlack() async throws {
        // VLCKit 4.0.0a24 carries no VP9 decoder: relabelling the H.264 track
        // as VP9 gives a stream this device cannot decode.
        var video = try Fmp4.resource("livestream-h264")
        try video.renameSampleEntry("avc1", to: "vp09")
        let server = try await serve(video.protectFrames(codec: "vp09.00.10.08"))
        defer { server.stop() }
        let player = controller(for: server)
        defer { player.release() }
        let log = PlayerEventLog(player)
        let window = PlayerWindow(player)
        defer { window.close() }

        player.play(.livestream(cameraId: "cam-1", channel: 0))

        #expect(await log.wait(for: .seconds(8)) { $0.unsupportedCodec != nil }, "\(log.events)")
        #expect(log.unsupportedCodec == "VP9")
        #expect(!log.events.contains(.playing))
        #expect(log.frames == 0)
        #expect(VlcRuntime.shared.undecodableCodecs.contains("vp09"))
    }

    @Test func aFailedNegotiationIsAnError() async throws {
        let player = LivestreamVideoPlayerController { _, _ in
            throw ProtectAPIError.notSignedIn("no console")
        }
        defer { player.release() }
        let log = PlayerEventLog(player)
        let window = PlayerWindow(player)
        defer { window.close() }

        player.play(.livestream(cameraId: "cam-1", channel: 0))

        #expect(await log.wait(for: .seconds(5)) { $0.events.contains(.error) })
    }

    @Test func theConsoleHangingUpIsAnError() async throws {
        let video = try Fmp4.resource("livestream-h264")
        let server = try await serve(video.protectFrames(codec: "avc1.42c00d"), thenClose: true)
        defer { server.stop() }
        let player = controller(for: server)
        defer { player.release() }
        let log = PlayerEventLog(player)
        let window = PlayerWindow(player)
        defer { window.close() }

        player.play(.livestream(cameraId: "cam-1", channel: 0))

        #expect(await log.wait { $0.events.contains(.error) })
    }

    @Test func aStoppedSessionReportsNothingMore() async throws {
        let video = try Fmp4.resource("livestream-h264")
        let server = try await serve(video.protectFrames(codec: "avc1.42c00d"))
        defer { server.stop() }
        let player = controller(for: server)
        defer { player.release() }
        let log = PlayerEventLog(player)
        let window = PlayerWindow(player)
        defer { window.close() }
        player.play(.livestream(cameraId: "cam-1", channel: 0))
        #expect(await log.wait { $0.frames >= 1 })

        player.stop()
        try await Task.sleep(for: .milliseconds(300))
        let framesAtStop = log.frames
        try await Task.sleep(for: .seconds(1))

        #expect(log.frames == framesAtStop)
        #expect(!log.events.contains(.error))
    }

    @Test func aHiddenPictureDecodesNothingUntilShownAgain() async throws {
        let video = try Fmp4.resource("livestream-h264")
        let server = try await serve(video.protectFrames(codec: "avc1.42c00d"))
        defer { server.stop() }
        let player = controller(for: server)
        defer { player.release() }
        let log = PlayerEventLog(player)
        let window = PlayerWindow(player)
        defer { window.close() }

        player.setVideoEnabled(false)
        player.play(.livestream(cameraId: "cam-1", channel: 0))
        try await Task.sleep(for: .seconds(1.5))
        #expect(log.frames == 0)
        #expect(!log.events.contains(.error))

        player.setVideoEnabled(true)
        #expect(await log.wait(for: .seconds(5)) { $0.frames >= 1 })
        #expect(log.events.contains(.playing))
    }
}

extension PlayerEvent {
    var isTimeChanged: Bool { if case .timeChanged = self { true } else { false } }
}

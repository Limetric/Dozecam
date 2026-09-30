import Foundation
import Testing

@testable import Dozecam

/// The per-camera session (player + watchdog) and the registry that decides
/// which cameras hold one, driven by fake players on a manual clock.
@MainActor
struct CameraSessionsTests {
    let scheduler = ManualScheduler()
    let factory = PlayerFactory()

    static let nursery = StreamSource.rtsp(url: "rtsp://cam/nursery")
    static let playroom = StreamSource.rtsp(url: "rtsp://cam/playroom")

    func registry() -> CameraSessions {
        let factory = factory
        return CameraSessions(makePlayer: { factory.make($0) }, scheduler: scheduler)
    }

    // MARK: - One session

    @Test func aStallGoesReconnectingThenOfflineOnNetworkLossAndRecovers() throws {
        let sessions = registry()
        sessions.update(active: true, wanted: ["n": Self.nursery], warm: [], audible: [])
        let session = try #require(sessions["n"])
        let player = try #require(factory.player(for: "rtsp://cam/nursery"))
        #expect(player.plays == [Self.nursery])
        #expect(session.connection == .connecting)

        player.emit(.playing)
        #expect(session.connection == .live)

        // The picture freezes: no frame for the stall timeout, then a backoff,
        // then the player is restarted on the same source.
        let config = PlaybackWatchdog.Config()
        scheduler.advance(by: config.stallTimeoutMs + 1)
        #expect(session.connection == .reconnecting(attempt: 1))
        scheduler.advance(by: config.backoffMs(forAttempt: 1))
        #expect(player.plays == [Self.nursery, Self.nursery])
        #expect(player.stops == 1)
        // The restart's own teardown echo changes nothing.
        player.emit(.stopped)
        #expect(session.connection == .reconnecting(attempt: 1))

        sessions.setOnline(false)
        #expect(session.connection == .offline)
        // A buffered frame after the drop dates the picture, never goes live.
        player.emit(.timeChanged(milliseconds: 5_000))
        #expect(session.connection == .offline)
        scheduler.advance(by: 60_000)
        #expect(player.plays.count == 2, "no retries while offline")

        sessions.setOnline(true)
        #expect(session.connection == .reconnecting(attempt: 1))
        #expect(player.plays.count == 3, "reconnects at once when the network returns")

        player.emit(.playing)
        #expect(session.connection == .live)
    }

    @Test func aFrozenFrameIsNeverLive() throws {
        let sessions = registry()
        sessions.update(active: true, wanted: ["n": Self.nursery], warm: [], audible: [])
        let session = try #require(sessions["n"])
        let player = try #require(factory.player(for: "rtsp://cam/nursery"))
        player.emit(.playing)
        // Buffering notices keep coming, but no frame does.
        for _ in 0..<5 {
            scheduler.advance(by: 500)
            player.emit(.buffering)
        }
        #expect(session.connection != .live)
    }

    @Test func aSessionStartsSilentAndIsAskedForSoundAfter() throws {
        let sessions = registry()
        sessions.update(active: true, wanted: ["n": Self.nursery], warm: [], audible: ["n"])
        let player = try #require(factory.player(for: "rtsp://cam/nursery"))
        #expect(player.mutedHistory == [true, false])

        sessions.update(active: true, wanted: ["n": Self.nursery], warm: [], audible: [])
        #expect(player.muted == true)
    }

    @Test func anUnsupportedCodecIsShownAndNotRetried() throws {
        let sessions = registry()
        sessions.update(active: true, wanted: ["n": Self.nursery], warm: [], audible: [])
        let session = try #require(sessions["n"])
        let player = try #require(factory.player(for: "rtsp://cam/nursery"))

        player.emit(.unsupportedCodec("AV1"))
        // The audio clock ticking over a black picture is not a frame.
        player.emit(.timeChanged(milliseconds: 1_000))
        scheduler.advance(by: 60_000)

        #expect(session.unsupportedCodec == "AV1")
        #expect(session.tileState == .unsupported(codec: "AV1"))
        #expect(session.connection != .live)
        #expect(player.plays.count == 1)
    }

    @Test func anUnsupportedCodecStillReconnectsItsSound() throws {
        let sessions = registry()
        sessions.update(active: true, wanted: ["n": Self.nursery], warm: [], audible: ["n"])
        let session = try #require(sessions["n"])
        let player = try #require(factory.player(for: "rtsp://cam/nursery"))
        player.emit(.unsupportedCodec("AV1"))

        // The connection drops: the room is reconnected, after its backoff.
        let config = PlaybackWatchdog.Config()
        player.emit(.error)
        scheduler.advance(by: config.backoffMs(forAttempt: 1))
        #expect(player.plays.count == 2)
        // The new session says the same, and is left to play its sound.
        player.emit(.unsupportedCodec("AV1"))
        scheduler.advance(by: 60_000)
        #expect(player.plays.count == 2)

        // A network blip reconnects it once the network is back.
        sessions.setOnline(false)
        sessions.setOnline(true)
        #expect(player.plays.count == 3)
        #expect(session.tileState == .unsupported(codec: "AV1"))
        #expect(player.muted == false)
    }

    @Test func thePictureShapeIsKept() throws {
        let sessions = registry()
        sessions.update(active: true, wanted: ["n": Self.nursery], warm: [], audible: [])
        try #require(factory.player(for: "rtsp://cam/nursery")).emit(.videoAspect(4.0 / 3.0))
        #expect(sessions["n"]?.videoAspect == 4.0 / 3.0)
    }

    @Test func aSessionStartedWithNoNetworkIsOffline() throws {
        let sessions = registry()
        sessions.setOnline(false)
        sessions.update(active: true, wanted: ["n": Self.nursery], warm: [], audible: [])
        #expect(sessions["n"]?.connection == .offline)
    }

    // MARK: - The registry

    @Test func warmCamerasKeepTheirSessionWithoutVideo() throws {
        let sessions = registry()
        sessions.update(active: true, wanted: ["n": Self.nursery, "p": Self.playroom], warm: [], audible: [])
        let playroom = try #require(factory.player(for: "rtsp://cam/playroom"))
        playroom.emit(.playing)

        // The nursery opens on its own; the playroom is kept warm behind it.
        sessions.update(active: true, wanted: ["n": Self.nursery], warm: ["p"], audible: ["n"])
        #expect(!playroom.released)
        #expect(!playroom.videoEnabled)
        #expect(playroom.muted == true)
        scheduler.advance(by: 60_000)
        #expect(playroom.plays.count == 1, "a warm camera is not stalled")

        // Back to the grid: the same session, video restored, no new player.
        sessions.update(active: true, wanted: ["n": Self.nursery, "p": Self.playroom], warm: [], audible: [])
        #expect(playroom.videoEnabled)
        #expect(factory.players(for: "rtsp://cam/playroom").count == 1)
        #expect(sessions["p"]?.connection == .connecting, "not live until it paints again")
    }

    @Test func warmthNeverConjuresASession() {
        let sessions = registry()
        sessions.update(active: true, wanted: ["n": Self.nursery], warm: ["p"], audible: [])
        #expect(sessions["p"] == nil)
        #expect(factory.players(for: "rtsp://cam/playroom").isEmpty)
    }

    @Test func aCameraNoLongerWantedIsReleased() throws {
        let sessions = registry()
        sessions.update(active: true, wanted: ["n": Self.nursery, "p": Self.playroom], warm: [], audible: [])
        sessions.update(active: true, wanted: ["n": Self.nursery], warm: [], audible: [])
        #expect(sessions["p"] == nil)
        #expect(try #require(factory.player(for: "rtsp://cam/playroom")).released)
    }

    @Test func aSourceChangeReplacesTheSession() throws {
        let sessions = registry()
        sessions.update(active: true, wanted: ["n": Self.nursery], warm: [], audible: [])
        let old = try #require(factory.player(for: "rtsp://cam/nursery"))
        let moved = StreamSource.rtsp(url: "rtsp://cam/nursery-2")
        sessions.update(active: true, wanted: ["n": moved], warm: [], audible: [])
        #expect(old.released)
        #expect(sessions["n"]?.source == moved)
    }

    @Test func goingInactiveReleasesEverySessionAndComingBackRebuildsThem() throws {
        let sessions = registry()
        sessions.update(active: true, wanted: ["n": Self.nursery], warm: [], audible: [])
        let first = try #require(factory.player(for: "rtsp://cam/nursery"))
        first.emit(.playing)

        sessions.update(active: false, wanted: ["n": Self.nursery], warm: [], audible: [])
        #expect(first.released)
        #expect(sessions.sessions.isEmpty)
        // Events from the released player reach nothing.
        #expect(first.onEvent == nil)

        sessions.update(active: true, wanted: ["n": Self.nursery], warm: [], audible: [])
        let second = try #require(factory.player(for: "rtsp://cam/nursery"))
        #expect(second !== first)
        #expect(sessions["n"]?.connection == .connecting)
    }
}

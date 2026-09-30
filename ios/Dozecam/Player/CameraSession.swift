import Foundation
import Observation

/// One camera's live session: a player and the watchdog judging it, the port
/// of Android's `CameraStream`. Kept apart from whatever shows it, so a camera
/// can move between the grid and a screen of its own without negotiating the
/// stream again (`CameraSessions`).
@MainActor
@Observable
final class CameraSession {
    let cameraId: String
    let source: StreamSource
    @ObservationIgnored let player: any VideoPlayerController
    @ObservationIgnored private let watchdog: PlaybackWatchdog

    /// The picture's width over height, or nil until the player says. Not a
    /// liveness signal: it tells a zoom where the letterboxing starts.
    private(set) var videoAspect: Double?
    /// The codec this device cannot decode, once the player says so. The tile
    /// shows that instead of a connection state: a picture that can never come
    /// is not "reconnecting", and must never be a black "live" tile.
    private(set) var unsupportedCodec: String?
    private(set) var isMuted = true
    private(set) var isVideoEnabled = true

    /// What the watchdog has seen; never live on a frozen frame.
    var connection: ConnectionState { watchdog.state }
    var lastFrameAt: Date? { watchdog.lastFrameAt }

    init(
        cameraId: String,
        source: StreamSource,
        player: any VideoPlayerController,
        scheduler: any MonotonicScheduler,
        wallClock: @escaping () -> Date = Date.init,
        config: PlaybackWatchdog.Config = .init()
    ) {
        self.cameraId = cameraId
        self.source = source
        self.player = player
        // A reconnect restarts the player on the same source. Stopping first
        // makes it a restart whatever `play` does with a player already
        // playing; the `stopped` that echoes back is the watchdog's own.
        watchdog = PlaybackWatchdog(config: config, scheduler: scheduler, wallClock: wallClock) {
            player.stop()
            player.play(source)
        }
    }

    /// Starts silent, always: whichever tile is entitled to be heard says so
    /// once it is up, so a camera joining the grid can never blurt out a burst
    /// of room audio first.
    func start(networkOnline: Bool) {
        player.onEvent = { [weak self] event in self?.handle(event) }
        player.setMuted(true)
        isMuted = true
        watchdog.start()
        if !networkOnline { watchdog.onNetworkLost() }
        player.play(source)
    }

    func setMuted(_ muted: Bool) {
        guard isMuted != muted else { return }
        isMuted = muted
        player.setMuted(muted)
    }

    /// Drops or restores the video track of a camera kept connected behind the
    /// one on screen; see `PlaybackWatchdog.onVideoDisabled()`.
    func setVideoEnabled(_ enabled: Bool) {
        guard isVideoEnabled != enabled else { return }
        isVideoEnabled = enabled
        player.setVideoEnabled(enabled)
        if enabled { watchdog.onVideoEnabled() } else { watchdog.onVideoDisabled() }
    }

    func onNetworkAvailable() { watchdog.onNetworkAvailable() }
    func onNetworkLost() { watchdog.onNetworkLost() }

    func release() {
        watchdog.stop()
        player.onEvent = nil
        player.stop()
        player.release()
    }

    private func handle(_ event: PlayerEvent) {
        switch event {
        case .videoAspect(let ratio):
            videoAspect = ratio
        case .unsupportedCodec(let codec):
            unsupportedCodec = codec
            // Retrying cannot make a codec decodable, and a watchdog left
            // running would read an audio clock ticking over a black picture
            // as frames. The session stays up (its sound can still be heard);
            // the tile says what is wrong instead of a connection state.
            watchdog.stop()
            return
        default:
            break
        }
        watchdog.onPlayerEvent(event)
    }
}

import UIKit

/// A player that never produces a frame: `AppModel`'s default for tests and
/// previews, where no real stream exists. The app passes `LivePlayers`
/// (`DozecamApp`). Every tile it backs says so honestly: connecting, then
/// reconnecting, never live.
enum PendingLivePlayers {
    @MainActor
    static func make(for source: StreamSource) -> any VideoPlayerController {
        SilentVideoPlayer()
    }
}

@MainActor
private final class SilentVideoPlayer: VideoPlayerController {
    var onEvent: ((PlayerEvent) -> Void)?
    let view = UIView()

    func play(_ source: StreamSource) {}
    func setMuted(_ muted: Bool) {}
    func setVideoEnabled(_ enabled: Bool) {}
    func stop() {}
    func release() {}
}

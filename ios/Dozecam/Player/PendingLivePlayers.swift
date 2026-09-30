import UIKit

/// Stands in for the real players (VLCKit for RTSP, the livestream pipeline
/// for Protect) until they are wired in at `DozecamApp` (#66). It never
/// produces a frame, so every tile says so honestly: connecting, then
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

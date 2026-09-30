import UIKit
import VLCKit

/// Plays a camera's RTSP URL with VLCKit, tuned for low latency on the LAN by
/// `VlcRuntime`'s flags. The counterpart of Android's
/// `VlcVideoPlayerController`; one per camera on screen, all sharing the
/// runtime's library.
///
/// `rtsps://` does not play here: VLCKit's live555 has no TLS (#59). Protect
/// cameras are stored with their plain `rtsp://…:7447` link, and the in-app
/// TLS proxy for hand-entered `rtsps://` sources is future work.
@MainActor
final class VlcVideoPlayerController: VideoPlayerController {
    private let core: VlcPlayerCore

    init(runtime: VlcRuntime = .shared) {
        core = VlcPlayerCore(runtime: runtime)
    }

    var onEvent: ((PlayerEvent) -> Void)? {
        get { core.onEvent }
        set { core.onEvent = newValue }
    }

    var view: UIView { core.view }

    /// Only `.rtsp` plays here; a livestream belongs to
    /// `LivestreamVideoPlayerController`.
    func play(_ source: StreamSource) {
        guard case .rtsp(let url) = source else { return }
        guard let url = URL(string: url), let media = VLCMedia(url: url) else {
            VlcRuntime.log.error("unplayable RTSP URL")
            core.onEvent?(.error)
            return
        }
        core.play(media)
    }

    func setMuted(_ muted: Bool) { core.setMuted(muted) }

    func setVideoEnabled(_ enabled: Bool) { core.setVideoEnabled(enabled) }

    func stop() { core.stop() }

    /// Releases this player only; the shared library outlives it.
    func release() { core.release() }
}

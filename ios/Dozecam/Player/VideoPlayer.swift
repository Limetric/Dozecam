import UIKit

/// What a player reports. The watchdog reads these at the frame level: a
/// frozen frame must never pretend to be live (shared/spec/connection-state.md).
enum PlayerEvent: Equatable, Sendable {
    case playing
    case buffering
    case stopped
    case error
    /// Playback time advanced: a new frame was decoded.
    case timeChanged(milliseconds: Int64)
    /// The decoded picture's shape, width over height with anamorphic pixels
    /// applied. Not a liveness signal: it tells the screen where the picture
    /// ends and letterboxing begins.
    case videoAspect(Double)
    /// The device cannot decode this camera's video (e.g. a codec without a
    /// decoder here). Shown as such on the tile, never as a black live tile.
    case unsupportedCodec(String)
}

/// Where a camera's live video comes from. RTSP hands out raw RTP; Protect's
/// livestream wraps whatever the camera encodes in fMP4 and so carries any
/// codec (shared/spec/protect.md). A camera added by URL has no console, so
/// it can only ever be RTSP.
enum StreamSource: Equatable, Hashable, Sendable {
    case rtsp(url: String)
    case livestream(cameraId: String, channel: Int)

    /// `consoleHost` is the console currently signed in. A camera issued by
    /// another console cannot be negotiated here, so it plays its own RTSP URL.
    /// Cameras stored without a `protect` identity recover it from their
    /// `protect-<cameraId>-<channel>` id, as on Android.
    static func of(_ camera: Camera, consoleHost: String?) -> StreamSource {
        if let protect = camera.protect {
            let ours = protect.consoleHost == nil || protect.consoleHost == consoleHost
            return ours ? .livestream(cameraId: protect.cameraId, channel: protect.channel) : .rtsp(url: camera.url)
        }
        return legacyProtectIdentity(camera.id) ?? .rtsp(url: camera.url)
    }

    private static func legacyProtectIdentity(_ id: String) -> StreamSource? {
        let parts = id.split(separator: "-", omittingEmptySubsequences: false)
        // Android's `^protect-([^-]+)-(\d+)$`: ASCII digits only.
        guard parts.count == 3, parts[0] == "protect", !parts[1].isEmpty, !parts[2].isEmpty,
            parts[2].allSatisfy({ ("0"..."9").contains($0) }), let channel = Int(parts[2])
        else { return nil }
        return .livestream(cameraId: String(parts[1]), channel: channel)
    }
}

/// One camera's player: VLCKit for RTSP, the livestream pipeline for Protect.
/// Main-actor bound, like the views it feeds; implementations hop their
/// library's callbacks onto the main actor before calling `onEvent`.
@MainActor
protocol VideoPlayerController: AnyObject {
    var onEvent: ((PlayerEvent) -> Void)? { get set }
    /// The view the picture is drawn into; the tile hosts it.
    var view: UIView { get }
    /// Plays the picture only. A camera's sound comes out of the monitor's
    /// mix (`MonitoringService`), never out of its video player.
    func play(_ source: StreamSource)
    /// Drops or restores the video track without tearing the session down: a
    /// camera nobody is looking at keeps its stream but decodes no picture.
    func setVideoEnabled(_ enabled: Bool)
    func stop()
    func release()
}

/// The LIVE / RECONNECTING / OFFLINE model the status overlay draws
/// (shared/spec/connection-state.md).
enum ConnectionState: Equatable, Sendable {
    /// Initial startup; nothing received yet.
    case connecting
    /// Frames are arriving.
    case live
    /// The stream failed or stalled; attempt N is pending or in flight.
    case reconnecting(attempt: Int)
    /// No network; reconnecting is pointless until it returns.
    case offline
}

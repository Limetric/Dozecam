import Foundation

/// The console-side identity of a camera onboarded through Protect. Present
/// only for cameras Protect discovered: a stream URL entered by hand has no
/// console behind it, so it can only ever be played over RTSP.
struct ProtectStream: Codable, Equatable, Hashable, Sendable {
    let cameraId: String
    /// Quality channel on the console; 1 is Medium, the nursery default.
    let channel: Int
    /// The console that issued `cameraId`. One console is signed in at a
    /// time, so a camera from another console is played over its own RTSP
    /// URL, never through a livestream negotiated with the wrong console.
    let consoleHost: String?

    init(cameraId: String, channel: Int, consoleHost: String? = nil) {
        self.cameraId = cameraId
        self.channel = channel
        self.consoleHost = consoleHost
    }
}

/// A camera Dozecam shows and listens to. Same shape and ids as Android's
/// `Camera` (shared/spec/protect.md: a Protect camera's id is
/// `protect-<console camera id>-<channel>`; one added by hand gets a random
/// id).
struct Camera: Codable, Equatable, Hashable, Identifiable, Sendable {
    let id: String
    var name: String
    /// Always plain `rtsp://` once stored (shared/spec/protect.md).
    var url: String
    var protect: ProtectStream?
    /// Whether this camera takes part at all: enabled cameras are the ones the
    /// viewer shows and the monitor listens to. The user's choice; a fresh
    /// import arrives enabled.
    var enabled: Bool

    init(id: String, name: String, url: String, protect: ProtectStream? = nil, enabled: Bool = true) {
        self.id = id
        self.name = name
        self.url = url
        self.protect = protect
        self.enabled = enabled
    }

    /// `protect-<console camera id>-<channel>`: the same id from the public
    /// and the legacy API, and on Android.
    static func protectID(cameraId: String, channel: Int) -> String {
        "protect-\(cameraId)-\(channel)"
    }
}

import Foundation

/// What a tile's status pill says, kept apart from the view so the wording can
/// be checked without a screen. The port of Android's `StatusOverlay` strings
/// and `frameAge`.
enum StatusText {
    /// What a tile is showing, in the order the pill prefers them: a codec
    /// that cannot be decoded says so over any connection state.
    enum TileState: Equatable {
        case connection(ConnectionState)
        case unsupported(codec: String)
    }

    /// The pill's word: short, upper case, readable across a dark room.
    static func label(_ state: TileState) -> String {
        switch state {
        case .connection(.connecting): "CONNECTING"
        case .connection(.live): "LIVE"
        case .connection(.reconnecting(let attempt)): "RECONNECTING (attempt \(attempt))"
        case .connection(.offline): "OFFLINE"
        case .unsupported: "CAN'T PLAY"
        }
    }

    /// The same, in a sentence for VoiceOver.
    static func spokenLabel(_ state: TileState) -> String {
        switch state {
        case .connection(.connecting): "Connecting"
        case .connection(.live): "Live"
        case .connection(.reconnecting(let attempt)): "Reconnecting, attempt \(attempt)"
        case .connection(.offline): "Offline"
        case .unsupported(let codec): "Cannot play \(codec) video on this device"
        }
    }

    /// The full pill: a frozen picture always carries its age, so anything
    /// but live with a frame behind it says how old that frame is.
    static func text(_ state: TileState, lastFrameAt: Date?, now: Date) -> String {
        guard state != .connection(.live), let lastFrameAt else { return label(state) }
        return "\(label(state)) · last frame \(age(from: lastFrameAt, to: now))"
    }

    static func spokenText(_ state: TileState, lastFrameAt: Date?, now: Date) -> String {
        guard state != .connection(.live), let lastFrameAt else { return spokenLabel(state) }
        return "\(spokenLabel(state)), last frame \(age(from: lastFrameAt, to: now))"
    }

    /// How long ago, in the largest unit that still counts at least one,
    /// rounded down: a picture 59 seconds old is "59 seconds ago", not a
    /// minute. A negative age (the clock was set back) reads as zero seconds.
    static func age(from then: Date, to now: Date) -> String {
        let seconds = max(Int64(now.timeIntervalSince(then)), 0)
        let (count, unit): (Int64, String) =
            switch seconds {
            case ..<60: (seconds, "second")
            case ..<3_600: (seconds / 60, "minute")
            case ..<86_400: (seconds / 3_600, "hour")
            default: (seconds / 86_400, "day")
            }
        return "\(count) \(unit)\(count == 1 ? "" : "s") ago"
    }
}

/// Decides when a camera's monitor should stop trying a transport and take
/// the next one: the port of Android's `TransportFallback`
/// (shared/spec/connection-state.md, "The monitor's transports").
///
/// A stream that cannot be decoded does not say so: it looks exactly like a
/// camera in a quiet room, and the reconnect loop it provokes looks exactly
/// like a flaky network. The one signal that separates them is whether a
/// single audio buffer has ever arrived: if none has after several restarts,
/// no number of further restarts will change that.
///
/// Restarts are counted here rather than read off the watchdog's attempt
/// number, which is the whole point: a session that reaches "live" and only
/// then fails to decode resets that number every time round, so it never
/// climbs and the camera would stay uncovered forever.
///
/// Transports are taken in turn and then come round again, because a fallback
/// can be just as unusable as what it replaced (stale credentials, a console
/// that will not serve a livestream), and stopping at the last one would pin a
/// camera to it while the stream it started on quietly recovered. The cycling
/// stops the moment anything decodes: that transport is then kept through any
/// later trouble, because by then the trouble really is the network, and the
/// others would fare no better.
///
/// Fixtures: `shared/fixtures/transport-fallback/fallback.json`.
struct TransportFallback: Sendable {
    /// Enough restarts to rule out a console that was merely busy or a network
    /// that blinked, few enough that a room is not left uncovered for long.
    /// The watchdog's backoff caps at 4 s, so this is seconds.
    static let defaultRestartsBeforeFallback = 3

    let transportCount: Int
    let restartsBeforeFallback: Int

    /// The transport in use, 0-based in order of preference.
    private(set) var index = 0

    private var decoded = false
    private var failedRestarts = 0

    init(transportCount: Int, restartsBeforeFallback: Int = defaultRestartsBeforeFallback) {
        self.transportCount = transportCount
        self.restartsBeforeFallback = restartsBeforeFallback
    }

    /// Audio arrived: this transport works, and is now kept for good.
    mutating func onAudioDecoded() {
        decoded = true
    }

    /// Called as a restart is made. Returns true when `index` has moved on.
    mutating func onRestart() -> Bool {
        if decoded { return false }
        failedRestarts += 1
        if failedRestarts < restartsBeforeFallback { return false }
        if transportCount <= 1 { return false }  // nowhere else to go
        index = (index + 1) % transportCount
        failedRestarts = 0
        return true
    }
}

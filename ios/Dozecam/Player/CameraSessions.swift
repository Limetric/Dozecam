import Foundation
import Observation

/// Every camera session the viewer holds, and the one place that decides
/// which cameras deserve one: the port of Android's `CameraStreams`.
///
/// The viewer names the cameras it shows (`wanted`) and, while one camera has
/// the screen to itself, the grid cameras behind it (`warm`). A warm camera
/// keeps its session and drops its video track: it costs a socket rather than
/// a decoder, and returning to the grid costs a keyframe rather than a fresh
/// negotiation. Warmth never conjures a session; it only spares one.
///
/// Nothing plays unless `active`: the viewer on screen with its scene in the
/// foreground. Going inactive releases every session, so a backgrounded app
/// streams no house's worth of cameras into nothing; background audio belongs
/// to the monitor (#67), not to tiles.
@MainActor
@Observable
final class CameraSessions {
    typealias MakePlayer = @MainActor (StreamSource) -> any VideoPlayerController

    private(set) var sessions: [String: CameraSession] = [:]

    @ObservationIgnored private let makePlayer: MakePlayer
    @ObservationIgnored private let scheduler: any MonotonicScheduler
    @ObservationIgnored private let wallClock: () -> Date
    @ObservationIgnored private let config: PlaybackWatchdog.Config
    @ObservationIgnored private(set) var isActive = false
    @ObservationIgnored private(set) var isOnline = true
    @ObservationIgnored private var wanted: [String: StreamSource] = [:]
    @ObservationIgnored private var warm: Set<String> = []
    @ObservationIgnored private var audible: Set<String> = []

    init(
        makePlayer: @escaping MakePlayer,
        scheduler: any MonotonicScheduler,
        wallClock: @escaping () -> Date = Date.init,
        config: PlaybackWatchdog.Config = .init()
    ) {
        self.makePlayer = makePlayer
        self.scheduler = scheduler
        self.wallClock = wallClock
        self.config = config
    }

    subscript(cameraId: String) -> CameraSession? { sessions[cameraId] }

    /// Settles every session at once, so opening a camera (its claim, the
    /// grid's loss of it and the warm set that came with it) is reconciled in
    /// one step rather than with a teardown in between.
    func update(active: Bool, wanted: [String: StreamSource], warm: Set<String>, audible: Set<String>) {
        isActive = active
        self.wanted = wanted
        self.warm = warm
        self.audible = audible
        reconcile()
    }

    /// Feeds connectivity to every watchdog rather than tearing sessions down:
    /// a flapping Wi-Fi is what they exist to absorb.
    func setOnline(_ online: Bool) {
        guard isOnline != online else { return }
        isOnline = online
        for session in sessions.values {
            if online { session.onNetworkAvailable() } else { session.onNetworkLost() }
        }
    }

    func releaseAll() {
        for session in sessions.values { session.release() }
        sessions.removeAll()
    }

    private func reconcile() {
        guard isActive else {
            releaseAll()
            return
        }
        let keep = Set(wanted.keys).union(warm.intersection(sessions.keys))
        for id in sessions.keys where !keep.contains(id) {
            sessions.removeValue(forKey: id)?.release()
        }
        for (id, source) in wanted.sorted(by: { $0.key < $1.key }) {
            if let existing = sessions[id], existing.source == source {
                existing.setVideoEnabled(true)
            } else {
                // A source change (a URL edit, a console swap putting a camera
                // back on RTSP) means the running session is for the wrong
                // stream.
                sessions[id]?.release()
                let session = CameraSession(
                    cameraId: id, source: source, player: makePlayer(source),
                    scheduler: scheduler, wallClock: wallClock, config: config)
                sessions[id] = session
                session.start(networkOnline: isOnline)
            }
        }
        for id in keep.subtracting(wanted.keys) {
            // Nothing on screen can say where a sound comes from while the
            // camera making it is not on it.
            sessions[id]?.setMuted(true)
            sessions[id]?.setVideoEnabled(false)
        }
        for (id, session) in sessions where wanted[id] != nil {
            session.setMuted(!audible.contains(id))
        }
    }
}

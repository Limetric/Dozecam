import Foundation
import UIKit

@testable import Dozecam

/// A monotonic clock the test moves by hand, the counterpart of the virtual
/// time `kotlinx-coroutines-test` gives Android's tests: nothing fires until
/// `advance(by:)` passes its deadline, and timers due at the same moment fire
/// in the order they were set.
@MainActor
final class ManualScheduler: MonotonicScheduler {
    private(set) var nowMs: Int64 = 0
    private var pending: [(id: Int, at: Int64, action: @MainActor () -> Void)] = []
    private var nextID = 0

    func schedule(at deadlineMs: Int64, _ action: @escaping @MainActor () -> Void) -> ScheduledAction {
        nextID += 1
        let id = nextID
        pending.append((id, deadlineMs, action))
        return ScheduledAction { [weak self] in self?.pending.removeAll { $0.id == id } }
    }

    /// Moves the clock on by `ms`, firing every timer that comes due on the
    /// way, at its own time (including those set by timers that fired).
    func advance(by ms: Int64) {
        let target = nowMs + ms
        while let next = pending.filter({ $0.at <= target }).min(by: { ($0.at, $0.id) < ($1.at, $1.id) }) {
            pending.removeAll { $0.id == next.id }
            nowMs = max(nowMs, next.at)
            next.action()
        }
        nowMs = target
    }

    /// Fires whatever is due now.
    func runCurrent() { advance(by: 0) }

    var pendingCount: Int { pending.count }
}

/// A player that plays nothing and records what it was asked to do.
@MainActor
final class RecordingPlayer: VideoPlayerController {
    var onEvent: ((PlayerEvent) -> Void)?
    let view = UIView()
    let source: StreamSource?
    private(set) var plays: [StreamSource] = []
    private(set) var stops = 0
    private(set) var released = false
    private(set) var videoEnabled = true

    init(source: StreamSource? = nil) {
        self.source = source
    }

    func play(_ source: StreamSource) { plays.append(source) }
    func setVideoEnabled(_ enabled: Bool) { videoEnabled = enabled }
    func stop() { stops += 1 }
    func release() { released = true }

    func emit(_ event: PlayerEvent) { onEvent?(event) }
}

/// Every player a registry built, in order, by the source it was built for.
@MainActor
final class PlayerFactory {
    private(set) var built: [RecordingPlayer] = []

    func make(_ source: StreamSource) -> any VideoPlayerController {
        let player = RecordingPlayer(source: source)
        built.append(player)
        return player
    }

    /// The newest player built for `url`.
    func player(for url: String) -> RecordingPlayer? {
        built.last { $0.source == .rtsp(url: url) }
    }

    func players(for url: String) -> [RecordingPlayer] {
        built.filter { $0.source == .rtsp(url: url) }
    }
}

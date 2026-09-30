import Foundation

/// Which camera the grid is listening to in rotating mode, the port of
/// Android's `SoundRotation` and `rememberAudibleCameraId`.
///
/// Only ever one: several rooms at once is noise, and a grid with no sound at
/// all wastes the microphones. So the sound goes round the cameras on screen
/// in the order they are shown, a turn each
/// (shared/spec/alerts-and-sound-modes.md#sound-modes).
@MainActor
final class SoundRotation {
    /// Long enough to tell whether a room is settled, short enough to feel
    /// like a round.
    static let intervalMs: Int64 = 10_000

    /// The camera after `current`, wrapping; the first one when `current` has
    /// gone (switched off or deleted mid-round), a fresh start rather than a
    /// silent turn on a camera that is no longer there.
    nonisolated static func next(after current: String?, in cameraIds: [String]) -> String? {
        guard !cameraIds.isEmpty else { return nil }
        let index = current.flatMap { cameraIds.firstIndex(of: $0) } ?? -1
        return cameraIds[(index + 1) % cameraIds.count]
    }

    /// The camera audible now, or nil for silence.
    private(set) var current: String?

    private let intervalMs: Int64
    private let scheduler: any MonotonicScheduler
    private let onChange: @MainActor () -> Void
    private var cameraIds: [String] = []
    private var enabled = false
    private var timer: ScheduledAction?

    init(
        intervalMs: Int64 = SoundRotation.intervalMs,
        scheduler: any MonotonicScheduler,
        onChange: @escaping @MainActor () -> Void
    ) {
        self.intervalMs = intervalMs
        self.scheduler = scheduler
        self.onChange = onChange
    }

    /// The cameras taking turns and whether the round is on. The current
    /// camera keeps its turn across an unrelated update, but the turn's clock
    /// starts over whenever either changes, as Android's effect restarts.
    func update(cameraIds: [String], enabled: Bool) {
        guard cameraIds != self.cameraIds || enabled != self.enabled else { return }
        self.cameraIds = cameraIds
        self.enabled = enabled
        timer?.cancel()
        timer = nil
        guard enabled, !cameraIds.isEmpty else {
            current = nil
            return
        }
        if current.map({ !cameraIds.contains($0) }) ?? true { current = cameraIds.first }
        scheduleTurn()
    }

    func stop() {
        update(cameraIds: [], enabled: false)
    }

    private func scheduleTurn() {
        timer = scheduler.schedule(after: intervalMs) { [weak self] in
            guard let self else { return }
            current = Self.next(after: current, in: cameraIds)
            scheduleTurn()
            onChange()
        }
    }
}

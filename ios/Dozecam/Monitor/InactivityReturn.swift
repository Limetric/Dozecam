import Foundation
import Observation

/// The wait before a single camera hands the screen back to the grid, the port
/// of Android's `InactivityCountdown`.
///
/// A single camera is a detour, not the resting state: the viewer exists to
/// watch every room, and a phone left face up on a camera someone opened an
/// hour ago has stopped showing the rest of the house without saying so. Any
/// touch on the screen starts the minute over.
@MainActor
@Observable
final class InactivityCountdown {
    /// Long enough to look, short enough that walking away puts it back.
    static let timeoutMs: Int64 = 60_000
    /// The readout counts whole seconds; anything finer is work nobody sees.
    static let tickMs: Int64 = 1_000

    let timeoutMs: Int64
    private(set) var remainingMs: Int64

    @ObservationIgnored private let scheduler: any MonotonicScheduler
    @ObservationIgnored private let onExpired: @MainActor () -> Void
    @ObservationIgnored private var timer: ScheduledAction?

    init(
        timeoutMs: Int64 = InactivityCountdown.timeoutMs,
        scheduler: any MonotonicScheduler,
        onExpired: @escaping @MainActor () -> Void
    ) {
        self.timeoutMs = timeoutMs
        self.scheduler = scheduler
        self.onExpired = onExpired
        remainingMs = timeoutMs
    }

    /// How much of the wait is left, for the draining bar.
    var fraction: Double {
        timeoutMs <= 0 ? 0 : min(max(Double(remainingMs) / Double(timeoutMs), 0), 1)
    }

    /// Rounded up, so the readout reaches zero only when the grid returns.
    var remainingSeconds: Int {
        Int((remainingMs + 999) / 1_000)
    }

    var isRunning: Bool { timer != nil }

    /// Counts from the whole minute. Called when the camera opens and when the
    /// app comes back to the front: time in the background does not count
    /// against the viewer, and coming back gives a fresh minute rather than
    /// an immediate bounce to the grid.
    func start() {
        timer?.cancel()
        remainingMs = timeoutMs
        tick()
    }

    /// Someone is here after all: the whole minute again.
    func reset() {
        guard isRunning else { return }
        start()
    }

    func stop() {
        timer?.cancel()
        timer = nil
    }

    /// Stepped rather than measured against the clock: a few frames out over
    /// a minute is nobody's problem, and counting down is the same arithmetic
    /// in a test as at 3 am.
    private func tick() {
        guard remainingMs > 0 else {
            timer = nil
            onExpired()
            return
        }
        let step = min(Self.tickMs, remainingMs)
        timer = scheduler.schedule(after: step) { [weak self] in
            guard let self else { return }
            remainingMs -= step
            tick()
        }
    }
}

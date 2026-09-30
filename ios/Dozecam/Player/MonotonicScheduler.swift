import Foundation

/// Milliseconds on a monotonic clock, and timers on it. Every deadline the
/// viewer keeps (stall, connect, backoff, sound rotation, inactivity return)
/// is measured here, so changing the phone's clock moves none of them
/// (shared/spec/connection-state.md). Tests drive a manual one and so decide
/// exactly when each timer fires.
@MainActor
protocol MonotonicScheduler: AnyObject {
    /// Milliseconds since an arbitrary origin; never goes backwards.
    var nowMs: Int64 { get }
    /// Runs `action` once `nowMs` reaches `deadlineMs`, unless cancelled first.
    func schedule(at deadlineMs: Int64, _ action: @escaping @MainActor () -> Void) -> ScheduledAction
}

extension MonotonicScheduler {
    func schedule(after delayMs: Int64, _ action: @escaping @MainActor () -> Void) -> ScheduledAction {
        schedule(at: nowMs + delayMs, action)
    }
}

/// A timer that has been set; cancelling it more than once is harmless.
@MainActor
final class ScheduledAction {
    private var onCancel: (() -> Void)?

    init(onCancel: @escaping () -> Void) {
        self.onCancel = onCancel
    }

    func cancel() {
        onCancel?()
        onCancel = nil
    }
}

/// The real clock: `ContinuousClock`, which keeps counting while the device
/// sleeps, as Android's `elapsedRealtime` does.
@MainActor
final class ContinuousScheduler: MonotonicScheduler {
    static let shared = ContinuousScheduler()

    /// One origin for the process, so a time taken off the main actor (a
    /// decoded audio buffer's, on libVLC's thread) is on the same clock as
    /// the deadlines here.
    private nonisolated static let origin = ContinuousClock.now

    /// `nowMs`, readable from any thread.
    nonisolated static var monotonicNowMs: Int64 {
        let elapsed = origin.duration(to: .now).components
        return elapsed.seconds * 1_000 + elapsed.attoseconds / 1_000_000_000_000_000
    }

    var nowMs: Int64 { Self.monotonicNowMs }

    func schedule(at deadlineMs: Int64, _ action: @escaping @MainActor () -> Void) -> ScheduledAction {
        let deadline = Self.origin.advanced(by: .milliseconds(deadlineMs))
        let task = Task { @MainActor in
            try? await Task.sleep(until: deadline, clock: .continuous)
            guard !Task.isCancelled else { return }
            action()
        }
        return ScheduledAction { task.cancel() }
    }
}

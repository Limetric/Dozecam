import Synchronization

/// A current value and the streams that follow it: the counterpart of the
/// `StateFlow`s Android's repositories expose.
///
/// Readable synchronously from any isolation (a `@MainActor` model can take
/// `value` without awaiting), and every stream it hands out starts with the
/// value current at subscription, then follows every change. Each stream keeps
/// only the newest value it has not delivered yet: a slow reader of state
/// wants where things stand, not the history.
///
/// Lock-protected rather than an actor so that no caller needs an `await` to
/// read, and so the callbacks of `NWPathMonitor` and `NWConnection`, which run
/// on their own queues, can publish without hopping anywhere.
final class Broadcast<Value: Sendable>: Sendable {
    private struct State {
        var value: Value
        var subscribers: [UInt64: AsyncStream<Value>.Continuation] = [:]
        var nextID: UInt64 = 0
    }

    private let state: Mutex<State>

    init(_ initial: Value) {
        state = Mutex(State(value: initial))
    }

    deinit {
        state.withLock { state in
            for continuation in state.subscribers.values { continuation.finish() }
        }
    }

    var value: Value { state.withLock { $0.value } }

    /// Stores `value` and delivers it to every stream. Delivery happens under
    /// the lock, so two concurrent sends reach every subscriber in one order.
    func send(_ value: Value) {
        state.withLock { state in
            state.value = value
            for continuation in state.subscribers.values { continuation.yield(value) }
        }
    }

    /// A stream of changes. With `replayingCurrent` (the default) it opens
    /// with the value current now; without, it carries only later sends, which
    /// is what an event (rather than a state) wants.
    func stream(replayingCurrent: Bool = true) -> AsyncStream<Value> {
        let (stream, continuation) = AsyncStream.makeStream(of: Value.self, bufferingPolicy: .bufferingNewest(1))
        state.withLock { state in
            let id = state.nextID
            state.nextID += 1
            state.subscribers[id] = continuation
            if replayingCurrent { continuation.yield(state.value) }
            // Set under the lock but only ever run later (on cancellation or
            // finish), never from inside a `withLock` of ours.
            continuation.onTermination = { [weak self] _ in
                self?.state.withLock { _ = $0.subscribers.removeValue(forKey: id) }
            }
        }
        return stream
    }

    /// The number of live streams; for tests.
    var subscriberCount: Int { state.withLock { $0.subscribers.count } }
}

extension Broadcast where Value: Equatable {
    /// Sends `value` unless it equals the current one, the `distinctUntilChanged`
    /// of Android's flows.
    func sendIfChanged(_ value: Value) {
        state.withLock { state in
            guard state.value != value else { return }
            state.value = value
            for continuation in state.subscribers.values { continuation.yield(value) }
        }
    }
}

import Synchronization

/// A value tests share with the closures they hand to the code under test,
/// which may run on other threads. A class, unlike `Mutex` itself, can be
/// captured and held by a test suite struct.
final class Locked<Value: Sendable>: Sendable {
    private let mutex: Mutex<Value>

    init(_ value: Value) {
        mutex = Mutex(value)
    }

    var value: Value {
        get { mutex.withLock { $0 } }
        set { mutex.withLock { $0 = newValue } }
    }
}

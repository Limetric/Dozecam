import Foundation

@testable import Dozecam

/// AlarmKit, driven by hand: it records every call, keeps the list of alarms
/// AlarmKit would report, and can hold a schedule call open to test what
/// happens while one is in flight.
@MainActor
final class FakeAlarmScheduler: AlarmScheduling {
    struct Refused: Error {}

    enum Call: Equatable {
        case schedule(UUID, AlarmSpec)
        case end(UUID)
    }

    private(set) var calls: [Call] = []
    private(set) var alarms: [AlarmSnapshot] = []
    /// Schedule calls throw.
    var refuse = false
    /// Schedule calls wait for `release()`.
    var holding = false
    private var held: [CheckedContinuation<Void, Never>] = []
    private var subscribers: [AsyncStream<[AlarmSnapshot]>.Continuation] = []

    var scheduledIds: [UUID] {
        calls.compactMap { if case .schedule(let id, _) = $0 { id } else { nil } }
    }
    var scheduledSpecs: [AlarmSpec] {
        calls.compactMap { if case .schedule(_, let spec) = $0 { spec } else { nil } }
    }
    var endedIds: [UUID] {
        calls.compactMap { if case .end(let id) = $0 { id } else { nil } }
    }
    var liveIds: [UUID] { alarms.map(\.id) }
    var heldCount: Int { held.count }

    func schedule(id: UUID, _ spec: AlarmSpec) async throws {
        calls.append(.schedule(id, spec))
        if holding { await withCheckedContinuation { held.append($0) } }
        if refuse { throw Refused() }
        alarms.append(AlarmSnapshot(id: id, phase: .scheduled))
        publish()
    }

    /// Lets every held schedule call finish.
    func release() {
        let waiting = held
        held = []
        for continuation in waiting { continuation.resume() }
    }

    func end(id: UUID) throws {
        calls.append(.end(id))
        alarms.removeAll { $0.id == id }
        publish()
    }

    func current() -> [AlarmSnapshot]? { alarms }

    func updates() -> AsyncStream<[AlarmSnapshot]> {
        let (stream, continuation) = AsyncStream.makeStream(of: [AlarmSnapshot].self)
        subscribers.append(continuation)
        continuation.yield(alarms)
        return stream
    }

    /// The alarm's time came.
    func ring(_ id: UUID) {
        guard let index = alarms.firstIndex(where: { $0.id == id }) else { return }
        alarms[index].phase = .alerting
        publish()
    }

    /// The user pressed Stop on the lock screen.
    func userStops(_ id: UUID) {
        alarms.removeAll { $0.id == id }
        publish()
    }

    /// An alarm from somewhere else in the app (the dead-man, say).
    func add(_ snapshot: AlarmSnapshot) {
        alarms.append(snapshot)
        publish()
    }

    private func publish() {
        for subscriber in subscribers { subscriber.yield(alarms) }
    }
}

/// The primary alert, as a record.
@MainActor
final class FakeAlarmAlerting: AlarmAlerting {
    private(set) var ringing: AlertSubject?
    private(set) var raised: [AlertSubject] = []
    private(set) var stops = 0
    var refuse = false
    let acknowledgements: AsyncStream<AlertSubject>
    private let continuation: AsyncStream<AlertSubject>.Continuation

    init() {
        (acknowledgements, continuation) = AsyncStream.makeStream(of: AlertSubject.self)
    }

    func raise(_ subject: AlertSubject) async throws {
        if refuse { throw FakeAlarmScheduler.Refused() }
        raised.append(subject)
        ringing = subject
    }

    func stop() {
        stops += 1
        ringing = nil
    }

    /// The user pressed Stop on the lock screen.
    func userStops() {
        guard let subject = ringing else { return }
        ringing = nil
        continuation.yield(subject)
    }
}

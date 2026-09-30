import Foundation
import Observation
import os

/// What an alarm is for, and so what it says and sounds like.
enum AlertSubject: Equatable, Sendable {
    /// A room got loud: named by its current name.
    case room(cameraId: String, name: String)
    /// The monitor cannot do its job. The title is `FailureWording`'s.
    case failure(title: String)
    /// The bedtime test: the real alert, with only the wording changed.
    case test

    /// The alarm's full-screen title.
    var alarmTitle: String {
        switch self {
        case .room(_, let name): "\(name) is loud"
        case .failure(let title): title
        case .test: "Dozecam test alert"
        }
    }

    /// The system alarm sound (nil) for a room and the test; the failure's
    /// own tone for a failure, so the two are never confused.
    var alarmKitTone: AlarmTone? {
        switch self {
        case .room, .test: nil
        case .failure: .failure
        }
    }

    /// The tone the fallback plays through the engine.
    var fallbackTone: AlarmTone {
        switch self {
        case .room, .test: .room
        case .failure: .failure
        }
    }

    var purpose: AlarmSpec.Purpose {
        switch self {
        case .room: .room
        case .failure: .failure
        case .test: .test
        }
    }

    var cameraId: String? {
        if case .room(let cameraId, _) = self { return cameraId }
        return nil
    }
}

/// The primary alert: one AlarmKit alarm, rung a second from now, which rings
/// full-screen through silent mode and Sleep Focus from the background (#58).
@MainActor
protocol AlarmAlerting: AnyObject {
    /// What the alarm is ringing (or about to ring) for; nil when none.
    var ringing: AlertSubject? { get }
    /// Rings for `subject`. One alarm at a time: an alarm already up for
    /// something else is replaced, so the new title shows. Asking again for
    /// what is already ringing does nothing. Throws when AlarmKit refuses, and
    /// leaves whatever was ringing as it was: the caller falls back.
    func raise(_ subject: AlertSubject) async throws
    /// Silences it: an acknowledgement in the app, the give-up, the alert's
    /// room leaving the monitored set, alerts switched off, or Exit.
    func stop()
    /// The user stopped the alarm from the alarm's own UI (lock screen,
    /// Dynamic Island, StandBy): a person is here. Once per alarm. One reader.
    var acknowledgements: AsyncStream<AlertSubject> { get }
}

/// `AlarmAlerting` on AlarmKit.
///
/// **What maps onto AlarmKit and what cannot** (shared/spec/alerts-and-sound-
/// modes.md, "The sound alert"):
/// - *Latched*: yes. An AlarmKit alarm rings until someone stops it; the
///   detector re-arming changes nothing.
/// - *Acknowledgement*: the system's Stop button (and the side button, as for
///   any alarm). AlarmKit reports it as the alarm leaving `alarmUpdates`,
///   which this turns into `acknowledgements`.
/// - *One alarm at a time, re-pointed*: AlarmKit cannot retitle a ringing
///   alarm, so re-pointing schedules the new room's alarm and ends the old
///   one. The sound restarts about a second later, under the new name; with
///   no ramp there is nothing to restart.
/// - *Ramp and ceiling*: no. AlarmKit plays at the system's alarm volume
///   with its own behaviour; an app sets neither a volume nor a curve.
/// - *Repeat interval*: no. It rings continuously, not in bursts.
/// - *Chime and vibration switches*: no. It always sounds and vibrates as
///   the system's alarms do.
/// - *Give-up after 5 min*: only as the app's own `stop()`, which the caller
///   times. While an alarm rings the audio session is interrupted (#58), so
///   whether the app is still running to call it is a device check.
@MainActor
@Observable
final class AlarmKitAlerts: AlarmAlerting {
    private(set) var ringing: AlertSubject?
    /// Whether the current alarm has started ringing, as AlarmKit last said.
    private(set) var isAlerting = false

    @ObservationIgnored let acknowledgements: AsyncStream<AlertSubject>
    @ObservationIgnored private let acknowledged: AsyncStream<AlertSubject>.Continuation
    @ObservationIgnored private let scheduler: any AlarmScheduling
    @ObservationIgnored private let now: () -> Date
    @ObservationIgnored private var alarmId: UUID?
    /// Whether AlarmKit's updates have listed the current alarm yet: only an
    /// alarm seen can be seen to go.
    @ObservationIgnored private var listed = false
    /// The alarm being scheduled, and whether an update has already listed
    /// it: AlarmKit can publish it before `schedule` returns.
    @ObservationIgnored private var scheduling: (id: UUID, seen: Bool)?
    /// Retires a raise still waiting on AlarmKit when a stop or a newer raise
    /// overtakes it.
    @ObservationIgnored private var generation = 0
    @ObservationIgnored private var observer: Task<Void, Never>?

    /// A second ahead: AlarmKit refuses "now" (#58).
    static let lead: TimeInterval = 1

    private static let log = Logger(subsystem: "app.dozecam", category: "alerts")

    init(scheduler: any AlarmScheduling, now: @escaping () -> Date = Date.init) {
        self.scheduler = scheduler
        self.now = now
        (acknowledgements, acknowledged) = AsyncStream.makeStream(of: AlertSubject.self)
        let updates = scheduler.updates()
        observer = Task { [weak self] in
            for await alarms in updates { self?.receive(alarms) }
        }
    }

    isolated deinit {
        observer?.cancel()
        acknowledged.finish()
    }

    func raise(_ subject: AlertSubject) async throws {
        guard subject != ringing else { return }
        generation += 1
        let token = generation
        let id = UUID()
        let spec = AlarmSpec(
            title: subject.alarmTitle, fireDate: now().addingTimeInterval(Self.lead), tone: subject.alarmKitTone,
            purpose: subject.purpose, cameraId: subject.cameraId)
        scheduling = (id, false)
        defer { if scheduling?.id == id { scheduling = nil } }
        try await scheduler.schedule(id: id, spec)
        let seen = scheduling?.id == id && scheduling?.seen == true
        guard token == generation else {
            // Stopped, or replaced by a newer raise, while AlarmKit answered.
            end(id)
            return
        }
        let previous = alarmId
        alarmId = id
        ringing = subject
        isAlerting = false
        // Learnt from the updates alone, in their order: a list AlarmKit sent
        // before this alarm existed, still queued, must not read as it gone;
        // one that already listed it while it was being scheduled counts.
        listed = seen
        if let previous { end(previous) }
    }

    func stop() {
        generation += 1
        guard let id = alarmId else { return }
        clear()
        end(id)
    }

    private func receive(_ alarms: [AlarmSnapshot]) {
        if let pending = scheduling, alarms.contains(where: { $0.id == pending.id }) {
            scheduling?.seen = true
        }
        guard let id = alarmId else { return }
        if let alarm = alarms.first(where: { $0.id == id }) {
            listed = true
            let alerting = alarm.phase == .alerting
            if isAlerting != alerting { isAlerting = alerting }
            return
        }
        guard listed, let subject = ringing else { return }
        // Gone without the app ending it: the user stopped it.
        Self.log.notice("alarm stopped by the user")
        clear()
        acknowledged.yield(subject)
    }

    private func clear() {
        alarmId = nil
        ringing = nil
        isAlerting = false
        listed = false
    }

    private func end(_ id: UUID) {
        do {
            try scheduler.end(id: id)
        } catch {
            Self.log.error("could not end alarm \(id, privacy: .public): \(error, privacy: .public)")
        }
    }
}

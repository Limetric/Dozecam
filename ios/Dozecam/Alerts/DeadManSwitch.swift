import Foundation
import os

/// The dead-man: how iOS learns that Dozecam died.
@MainActor
protocol DeadManArming: AnyObject {
    /// Pushes the deadline back to `lead` from now. Call it on every
    /// monitoring heartbeat, and never less often than `lead`.
    func heartbeat() async
    /// Takes the dead-man down, alarm and notice: Exit, since an alarm for an
    /// app the user closed would be a false one.
    func disarm() async
}

/// The one failure the ledger cannot see is the app itself dying: iOS can
/// terminate it, and nothing restarts it (shared/spec/failure-alerts.md,
/// "Platform differences"). So the app keeps an alarm set a few minutes
/// ahead and pushes it back on every heartbeat; if the heartbeats stop, it
/// rings (#58).
///
/// - **An AlarmKit alarm** at `lead` from the latest heartbeat, when AlarmKit
///   is authorised, which rings full-screen through silent mode and Sleep
///   Focus. The new one is scheduled before the old one is ended, so there is
///   never a moment without one.
/// - **A time-sensitive notification** at the same deadline, always, as the
///   backup: it lights the screen and sounds outside silent mode, but Sleep
///   Focus suppresses it. Posting under the same id replaces the pending one.
///   The two do not stack: the notice shows once the alarm is stopped.
///
/// **It must never ring while the app is alive.** If AlarmKit refuses the new
/// alarm, the old one is ended anyway rather than left to ring at a deadline
/// the app is still alive past; the notice stays the dead-man until the next
/// heartbeat manages an alarm again.
///
/// The alarm's id is kept in `UserDefaults`, so the first heartbeat or disarm
/// after a relaunch takes the previous run's alarm down.
@MainActor
final class DeadManSwitch: DeadManArming {
    static let noticeId = "dozecam.dead-man"
    static let title = "Dozecam stopped monitoring"
    static let body = "Nobody will be told if a room gets loud. Open Dozecam to start monitoring again."
    /// 3 min is what #58 tested; every heartbeat was well inside 45 s.
    static let defaultLead: TimeInterval = 180

    private let alarms: any AlarmScheduling
    private let notices: any NoticeCenter
    private let access: any AlertAccess
    private let lead: TimeInterval
    private let now: () -> Date
    private let defaults: UserDefaults
    /// Off after `disarm()` until the next heartbeat, so a heartbeat still
    /// waiting on AlarmKit cannot leave an alarm behind an exit.
    private(set) var isArmed = false
    /// The deadline of the latest heartbeat.
    private(set) var deadline: Date?
    /// A heartbeat is waiting on AlarmKit; `pending` asks for another after it.
    private var beating = false
    private var pending = false

    private static let alarmKey = "deadMan.alarmId"
    private static let log = Logger(subsystem: "app.dozecam", category: "alerts")

    init(
        alarms: any AlarmScheduling, notices: any NoticeCenter, access: any AlertAccess,
        lead: TimeInterval = DeadManSwitch.defaultLead, now: @escaping () -> Date = Date.init,
        defaults: UserDefaults = .standard
    ) {
        self.alarms = alarms
        self.notices = notices
        self.access = access
        self.lead = lead
        self.now = now
        self.defaults = defaults
    }

    /// The alarm set now, if any.
    var alarmId: UUID? {
        get { defaults.string(forKey: Self.alarmKey).flatMap(UUID.init(uuidString:)) }
        set { defaults.set(newValue?.uuidString, forKey: Self.alarmKey) }
    }

    func heartbeat() async {
        isArmed = true
        // Heartbeats never overlap: one arriving mid-flight is folded into a
        // single follow-up, so alarms are always replaced in order.
        guard !beating else {
            pending = true
            return
        }
        beating = true
        defer { beating = false }
        repeat {
            pending = false
            await beat()
        } while pending && isArmed
    }

    func disarm() async {
        isArmed = false
        deadline = nil
        notices.remove(ids: [Self.noticeId])
        if let id = alarmId { end(id) }
        alarmId = nil
    }

    private func beat() async {
        let fireDate = now().addingTimeInterval(lead)
        deadline = fireDate
        do {
            try await notices.post(Self.notice(lead: lead))
        } catch {
            Self.log.error("dead-man notice not scheduled: \(error, privacy: .public)")
        }
        guard isArmed else { return disarmLeftovers() }

        let previous = alarmId
        guard access.alarms == .authorized else {
            if let previous { end(previous) }
            alarmId = nil
            return
        }
        let id = UUID()
        do {
            try await alarms.schedule(id: id, Self.alarm(at: fireDate))
        } catch {
            Self.log.error("dead-man alarm not scheduled: \(error, privacy: .public)")
            // The old one would ring at a deadline this live app has passed.
            if let previous = alarmId { end(previous) }
            alarmId = nil
            return
        }
        guard isArmed else {
            end(id)
            return disarmLeftovers()
        }
        alarmId = id
        if let previous { end(previous) }
    }

    /// A heartbeat that finished after `disarm()` takes down what it posted.
    private func disarmLeftovers() {
        notices.remove(ids: [Self.noticeId])
    }

    private func end(_ id: UUID) {
        do {
            try alarms.end(id: id)
        } catch {
            Self.log.error("dead-man alarm \(id, privacy: .public) not ended: \(error, privacy: .public)")
        }
    }

    static func alarm(at fireDate: Date) -> AlarmSpec {
        AlarmSpec(title: title, fireDate: fireDate, tone: .failure, purpose: .deadMan)
    }

    static func notice(lead: TimeInterval) -> LocalNotice {
        LocalNotice(
            id: noticeId, title: title, body: body, level: .timeSensitive,
            sound: .named(AlarmTone.failure.fileName), route: .failure, delay: lead)
    }
}

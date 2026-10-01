import Foundation

@testable import Dozecam

// Stand-ins for the alert machinery's system seams, for these tests and for
// the monitor's.

/// Lets the main actor run whatever was queued: stream deliveries, tasks.
@MainActor
func settleAlerts() async {
    for _ in 0..<10 { await Task.yield() }
}

/// The notification centre, as a record of what is posted and pending.
@MainActor
final class FakeNoticeCenter: NoticeCenter {
    struct Refused: Error {}

    private(set) var posted: [LocalNotice] = []
    private(set) var removed: [[String]] = []
    /// What is up now (delivered or pending), by id.
    private(set) var showing: [String: LocalNotice] = [:]
    var refuse = false

    func post(_ notice: LocalNotice) async throws {
        if refuse { throw Refused() }
        posted.append(notice)
        showing[notice.id] = notice
    }

    func remove(ids: [String]) {
        removed.append(ids)
        for id in ids { showing[id] = nil }
    }
}

/// The grants, set by hand.
@MainActor
final class FakeAlertAccess: AlertAccess {
    var alarms: AlarmAuthorization = .authorized
    var notificationGrant: NotificationGrant = .allowed
    /// What the prompts answer.
    var alarmAnswer: AlarmAuthorization = .authorized
    var notificationAnswer: NotificationGrant = .allowed
    private(set) var alarmRequests = 0
    private(set) var notificationRequests = 0

    func requestAlarms() async -> AlarmAuthorization {
        alarmRequests += 1
        if alarms == .notDetermined { alarms = alarmAnswer }
        return alarms
    }

    func notifications() async -> NotificationGrant { notificationGrant }

    func requestNotifications() async -> NotificationGrant {
        notificationRequests += 1
        if notificationGrant.status == .notDetermined { notificationGrant = notificationAnswer }
        return notificationGrant
    }
}

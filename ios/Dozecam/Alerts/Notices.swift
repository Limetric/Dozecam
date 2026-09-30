import UserNotifications

/// Where tapping a notification should take the user.
enum AlertRoute: Equatable, Sendable {
    /// That camera, full screen: a sound alert's card.
    case room(cameraId: String)
    /// The viewer on its failure notice, no camera: a failure's card, and the
    /// dead-man's.
    case failure
    /// The viewer as it is: the quiet notices.
    case viewer

    /// Carried in the notification's `userInfo`: plain strings, so it
    /// survives the app being gone between posting and tapping.
    var userInfo: [String: String] {
        switch self {
        case .room(let cameraId): [Self.routeKey: "room", Self.cameraKey: cameraId]
        case .failure: [Self.routeKey: "failure"]
        case .viewer: [Self.routeKey: "viewer"]
        }
    }

    /// Nil for a notification Dozecam did not post, or posted without a route.
    init?(userInfo: [AnyHashable: Any]) {
        switch userInfo[Self.routeKey] as? String {
        case "room":
            guard let cameraId = userInfo[Self.cameraKey] as? String else { return nil }
            self = .room(cameraId: cameraId)
        case "failure": self = .failure
        case "viewer": self = .viewer
        default: return nil
        }
    }

    private static let routeKey = "dozecam.route"
    private static let cameraKey = "dozecam.cameraId"
}

/// One local notification, as Dozecam describes it: a value, so what is
/// posted can be checked in a test without the system.
struct LocalNotice: Equatable, Sendable {
    enum Level: Equatable, Sendable {
        /// Into the list only: no banner, no sound, no lit screen. The quiet
        /// status channel's counterpart.
        case passive
        /// An ordinary notification.
        case active
        /// Breaks through Focus (except where the user withheld it) and stays
        /// on the lock screen: an alert card. Needs the time-sensitive
        /// entitlement, which `project.yml` grants.
        case timeSensitive
    }

    enum Sound: Equatable, Sendable {
        case none
        /// A bundled file, by name (`AlarmTone.fileName`).
        case named(String)
    }

    /// Posting again under the same id replaces the notice, delivered or
    /// pending.
    var id: String
    var title: String
    var body: String
    var level: Level
    var sound: Sound = .none
    var route: AlertRoute?
    /// Delivered this long from now; now when nil.
    var delay: TimeInterval?
    /// `NotificationRouter.alertCategory` for the cards whose dismissal is an
    /// acknowledgement.
    var category: String?

    /// The system's request for this notice.
    func request() -> UNNotificationRequest {
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        content.interruptionLevel =
            switch level {
            case .passive: .passive
            case .active: .active
            case .timeSensitive: .timeSensitive
            }
        switch sound {
        case .none: content.sound = nil
        case .named(let file): content.sound = UNNotificationSound(named: UNNotificationSoundName(file))
        }
        if let route { content.userInfo = route.userInfo }
        if let category { content.categoryIdentifier = category }
        if level == .timeSensitive { content.relevanceScore = 1 }
        // Grouped apart from anything else Dozecam may one day post.
        content.threadIdentifier = "monitoring"
        let trigger = delay.map { UNTimeIntervalNotificationTrigger(timeInterval: max($0, 1), repeats: false) }
        return UNNotificationRequest(identifier: id, content: content, trigger: trigger)
    }
}

/// The notification centre, behind a seam so what Dozecam posts can be tested
/// without the system.
@MainActor
protocol NoticeCenter: AnyObject {
    /// Posts or replaces `notice`. Throws when the system refuses, which it
    /// does when notifications are not allowed.
    func post(_ notice: LocalNotice) async throws
    /// Takes down these notices, whether delivered or still pending.
    func remove(ids: [String])
}

/// `UNUserNotificationCenter.current()`.
@MainActor
final class SystemNoticeCenter: NoticeCenter {
    func post(_ notice: LocalNotice) async throws {
        try await Self.add(notice)
    }

    func remove(ids: [String]) {
        let center = UNUserNotificationCenter.current()
        center.removePendingNotificationRequests(withIdentifiers: ids)
        center.removeDeliveredNotifications(withIdentifiers: ids)
    }

    /// The request is made here, off the main actor, so the non-Sendable
    /// system objects never cross an isolation boundary.
    private nonisolated static func add(_ notice: LocalNotice) async throws {
        try await UNUserNotificationCenter.current().add(notice.request())
    }
}

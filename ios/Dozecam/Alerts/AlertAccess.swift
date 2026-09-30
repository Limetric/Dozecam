@preconcurrency import AlarmKit
import UserNotifications

/// Whether AlarmKit may ring: the grant that decides whether an alert gets
/// through silent mode and Sleep Focus, and so iOS's nearest equivalent of
/// Android's full-screen access ("screen wake withdrawn",
/// shared/spec/failure-alerts.md, "Platform differences").
enum AlarmAuthorization: Equatable, Sendable {
    /// Never asked; `requestAlarms()` will show the system prompt.
    case notDetermined
    case denied
    case authorized
}

/// What the notification settings allow, as far as an alert card goes.
struct NotificationGrant: Equatable, Sendable {
    enum Status: Equatable, Sendable {
        /// Never asked; `requestNotifications()` will show the system prompt.
        case notDetermined
        case denied
        /// Allowed, provisionally included (quietly, to the list): a card can
        /// be posted, if not seen.
        case allowed
    }

    var status: Status
    /// Whether a time-sensitive notification breaks through Focus and the
    /// summary. Only meaningful while `status` is `.allowed`.
    var timeSensitive: Bool
    /// Whether the notification may play a sound.
    var sound: Bool

    /// The spec's "notifications blocked" reads this: no card can be shown.
    var canPost: Bool { status == .allowed }

    static let denied = NotificationGrant(status: .denied, timeSensitive: false, sound: false)
    static let allowed = NotificationGrant(status: .allowed, timeSensitive: true, sound: true)
}

/// Whether an alert could reach anyone, as far as the system's grants go: the
/// counterpart of Android's `AlertAccess`.
///
/// Both can be withdrawn in the Settings app at any time without the app being
/// told, so they are read afresh on every judgement, never cached
/// (shared/spec/failure-alerts.md, "Causes").
///
/// **Asking** is only ever the answer to something the user did (the night
/// checklist, #69), never part of arming: a baby monitor that greets the
/// parent with two system prompts at bedtime has some explaining to do, and
/// a prompt dismissed half-asleep is a grant lost.
@MainActor
protocol AlertAccess: AnyObject {
    /// AlarmKit's grant, read now.
    var alarms: AlarmAuthorization { get }
    /// Shows AlarmKit's prompt if it has not been answered, and returns the
    /// grant.
    func requestAlarms() async -> AlarmAuthorization
    /// The notification settings, read now.
    func notifications() async -> NotificationGrant
    /// Shows the notification prompt if it has not been answered, and returns
    /// the grant.
    func requestNotifications() async -> NotificationGrant
}

/// `AlarmManager` and `UNUserNotificationCenter`.
@MainActor
final class SystemAlertAccess: AlertAccess {
    var alarms: AlarmAuthorization { AlarmAuthorization(AlarmManager.shared.authorizationState) }

    func requestAlarms() async -> AlarmAuthorization {
        await Self.requestAlarmKit()
    }

    func notifications() async -> NotificationGrant {
        await Self.readNotifications()
    }

    func requestNotifications() async -> NotificationGrant {
        await Self.requestNotificationCenter()
    }

    // Nonisolated so the system's callbacks and the non-Sendable settings
    // object stay off the main actor (#58).

    private nonisolated static func requestAlarmKit() async -> AlarmAuthorization {
        do {
            return AlarmAuthorization(try await AlarmManager.shared.requestAuthorization())
        } catch {
            return AlarmAuthorization(AlarmManager.shared.authorizationState)
        }
    }

    private nonisolated static func readNotifications() async -> NotificationGrant {
        NotificationGrant(await UNUserNotificationCenter.current().notificationSettings())
    }

    private nonisolated static func requestNotificationCenter() async -> NotificationGrant {
        // Sound is asked for with the card: the fallback's card is the one
        // surface left when AlarmKit is not authorised. No badge: nothing
        // Dozecam posts is a count. Not `.timeSensitive`: that option was
        // deprecated in iOS 15.0 itself ("Use time-sensitive entitlement");
        // the entitlement in project.yml is what allows the level.
        _ = try? await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound])
        return await readNotifications()
    }
}

extension AlarmAuthorization {
    init(_ state: AlarmManager.AuthorizationState) {
        switch state {
        case .authorized: self = .authorized
        case .denied: self = .denied
        case .notDetermined: self = .notDetermined
        @unknown default: self = .notDetermined
        }
    }
}

extension NotificationGrant {
    init(_ settings: UNNotificationSettings) {
        let status: Status =
            switch settings.authorizationStatus {
            case .authorized, .provisional, .ephemeral: .allowed
            case .denied: .denied
            case .notDetermined: .notDetermined
            @unknown default: .denied
            }
        self.init(
            status: status,
            timeSensitive: status == .allowed && settings.timeSensitiveSetting == .enabled,
            sound: status == .allowed && settings.soundSetting == .enabled
        )
    }
}

import UserNotifications

/// What the user did with one of Dozecam's notifications.
enum NoticeResponse: Equatable, Sendable {
    /// Tapped it: open the app on `route`. A person is here, so this is also
    /// an acknowledgement of the alarm (Android's alert tap).
    case opened(AlertRoute)
    /// Swiped it away: acknowledges the alarm, as dismissing Android's alert
    /// card does. Reported only for cards in `NotificationRouter.alertCategory`.
    case dismissed(AlertRoute)

    /// Nil for anything that is not Dozecam's, or not one of these two.
    init?(actionIdentifier: String, userInfo: [AnyHashable: Any]) {
        guard let route = AlertRoute(userInfo: userInfo) else { return nil }
        switch actionIdentifier {
        case UNNotificationDefaultActionIdentifier: self = .opened(route)
        case UNNotificationDismissActionIdentifier: self = .dismissed(route)
        default: return nil
        }
    }
}

/// The notification centre's delegate: turns taps and dismissals into
/// `NoticeResponse`s for the app to route, and decides how a notice shows
/// while Dozecam is in front.
///
/// Install it with `install()` before the app finishes launching (from the
/// `App`'s initialiser), or a tap that launches the app is lost. The centre
/// holds its delegate weakly: keep this alive for the life of the app
/// (`shared`). Responses queue until someone reads `responses`, so the tap
/// that launched the app is waiting when the viewer comes up. One reader.
final class NotificationRouter: NSObject, UNUserNotificationCenterDelegate, Sendable {
    /// Cards whose dismissal acknowledges the alarm: the sound alert's and the
    /// failure's. Registered with `.customDismissAction`, without which iOS
    /// never reports a swipe.
    static let alertCategory = "dozecam.alert"

    static let shared = NotificationRouter()

    let responses: AsyncStream<NoticeResponse>
    private let continuation: AsyncStream<NoticeResponse>.Continuation

    override init() {
        (responses, continuation) = AsyncStream.makeStream(of: NoticeResponse.self)
        super.init()
    }

    /// Becomes the centre's delegate and registers the alert category.
    func install() {
        let center = UNUserNotificationCenter.current()
        center.delegate = self
        center.setNotificationCategories([
            UNNotificationCategory(
                identifier: Self.alertCategory, actions: [], intentIdentifiers: [], options: [.customDismissAction])
        ])
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse) async {
        let content = response.notification.request.content
        if let routed = NoticeResponse(actionIdentifier: response.actionIdentifier, userInfo: content.userInfo) {
            continuation.yield(routed)
        }
    }

    func userNotificationCenter(
        _ center: UNUserNotificationCenter, willPresent notification: UNNotification
    ) async -> UNNotificationPresentationOptions {
        Self.presentation(for: notification.request.content.interruptionLevel)
    }

    /// In front, an alert card still shows as a banner: the alarm may be for
    /// a room the viewer is not showing. A passive notice goes to the list
    /// only, as it would with the app in the background.
    static func presentation(for level: UNNotificationInterruptionLevel) -> UNNotificationPresentationOptions {
        level == .passive ? [.list] : [.banner, .list, .sound]
    }
}

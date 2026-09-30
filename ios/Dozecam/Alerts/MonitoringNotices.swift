import os

/// The cards monitoring posts: the counterpart of Android's
/// `MonitoringNotifications`, less the ongoing card, which iOS has no place
/// for (shared/spec/monitoring-lifecycle.md, iOS table).
///
/// - The **sound alert card** names the room and opens it on tap. It is
///   time-sensitive, so it breaks through Focus where the user allows it, and
///   silent: the alarm is the whole audible surface
///   (shared/spec/alerts-and-sound-modes.md, "The sound alert").
/// - The **failure card** lists every failure past grace. Announcing is
///   time-sensitive; a later update as failures join or clear is passive, so
///   it changes what the card says without lighting the screen or sounding
///   (shared/spec/failure-alerts.md, "Recovery").
/// - The **unplugged notice** is passive: no screen, no sound.
///
/// Each has one id, so posting again replaces it: one card of each at a time.
/// `removeAll()` takes every one down, as Exit must.
///
/// Wording of failures is `FailureWording`'s and comes in from the caller.
@MainActor
final class MonitoringNotices {
    static let alertId = "dozecam.alert"
    static let failureId = "dozecam.failure"
    static let unpluggedId = "dozecam.unplugged"

    private let center: any NoticeCenter
    private static let log = Logger(subsystem: "app.dozecam", category: "alerts")

    init(center: any NoticeCenter) {
        self.center = center
    }

    // MARK: - The sound alert

    /// The sound alert's card for `cameraId`, named `roomName`, or the
    /// bedtime test's: the same card, changed only in what it says.
    static func soundAlert(cameraId: String, roomName: String, test: Bool = false) -> LocalNotice {
        LocalNotice(
            id: alertId,
            title: test ? "Dozecam test alert" : "Sound detected — \(roomName)",
            body: test ? "This is the bedtime test. A real alert names the room." : "Tap to open the live view.",
            level: .timeSensitive,
            route: .room(cameraId: cameraId),
            category: NotificationRouter.alertCategory
        )
    }

    /// Returns false when the system refused (notifications not allowed),
    /// which the failure ledger learns separately from `AlertAccess`.
    @discardableResult
    func postSoundAlert(cameraId: String, roomName: String, test: Bool = false) async -> Bool {
        await post(Self.soundAlert(cameraId: cameraId, roomName: roomName, test: test))
    }

    func removeSoundAlert() {
        center.remove(ids: [Self.alertId])
    }

    // MARK: - Failures

    /// The failure card: `title` names every failure (Android joins their
    /// titles with " · "), `lines` are their details, one per line.
    /// `announce` for the posting that crosses the grace period; later
    /// updates are passive.
    static func failure(title: String, lines: [String], announce: Bool) -> LocalNotice {
        LocalNotice(
            id: failureId,
            title: title,
            body: lines.joined(separator: "\n"),
            level: announce ? .timeSensitive : .passive,
            route: .failure,
            category: NotificationRouter.alertCategory
        )
    }

    @discardableResult
    func postFailure(title: String, lines: [String], announce: Bool) async -> Bool {
        await post(Self.failure(title: title, lines: lines, announce: announce))
    }

    func removeFailure() {
        center.remove(ids: [Self.failureId])
    }

    // MARK: - Unplugged

    /// The charger came out while armed: the level now and the level at which
    /// the battery alarm will sound.
    static func unplugged(percent: Int, alarmPercent: Int = 25) -> LocalNotice {
        LocalNotice(
            id: unpluggedId,
            title: "Unplugged while monitoring",
            body: "Dozecam is running on battery (\(percent)%). It will sound an alarm at \(alarmPercent)%.",
            level: .passive,
            route: .viewer
        )
    }

    @discardableResult
    func postUnplugged(percent: Int, alarmPercent: Int = 25) async -> Bool {
        await post(Self.unplugged(percent: percent, alarmPercent: alarmPercent))
    }

    // MARK: - Exit

    /// Every card monitoring posts, taken down by id, so nothing else the app
    /// may one day post goes with them. The dead-man's notice is
    /// `DeadManSwitch.disarm()`'s.
    func removeAll() {
        center.remove(ids: [Self.alertId, Self.failureId, Self.unpluggedId])
    }

    private func post(_ notice: LocalNotice) async -> Bool {
        do {
            try await center.post(notice)
            return true
        } catch {
            Self.log.error("notice \(notice.id, privacy: .public) not posted: \(error, privacy: .public)")
            return false
        }
    }
}

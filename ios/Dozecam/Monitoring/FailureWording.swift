import Foundation

/// How a failure is named, in one place, so the alert card, the ongoing status
/// line and the viewer's notice all say the same thing about it: the port of
/// Android's `FailureWording` and the strings it uses
/// (shared/spec/failure-alerts.md#how-it-is-said). The English is Android's,
/// word for word, except where it names something only Android has.
///
/// Times are formatted by `time`, given in, so tests are deterministic and the
/// app shows the user's own short time format (Android's
/// `DateFormat.getTimeFormat`).
struct FailureWording: Sendable {
    /// Formats a wall-clock time (milliseconds since 1970) as a time of day.
    let time: @Sendable (Int64) -> String

    /// The user's short time format, in the current locale and time zone.
    static let system = FailureWording { ms in
        Date(timeIntervalSince1970: TimeInterval(ms) / 1_000).formatted(date: .omitted, time: .shortened)
    }

    /// Short enough for a status line or a notice: what is wrong, and where.
    func title(_ reason: FailureReason) -> String {
        switch reason {
        case .cameraUnreachable(_, let name, let networkDown):
            networkDown ? "No network — can't reach \(name)" : "Can't reach \(name)"
        case .lowBattery(let percent): "Battery low — \(percent)%"
        case .notificationsBlocked: "Alerts can't be shown"
        // Android: "Alerts can't wake the screen". On iOS the grant is
        // AlarmKit's, and without it an alert still lights the screen as a
        // notification; what it loses is ringing through silent mode and a
        // Focus.
        case .screenWakeBlocked: "Alerts can't ring on silent"
        case .audioSessionLost: "Can't listen with the screen locked"
        }
    }

    /// A sentence for the card: why it matters, and what to do.
    func detail(_ failure: MonitoringFailure) -> String {
        switch failure.reason {
        case .cameraUnreachable(_, _, let networkDown):
            networkDown
                ? "This phone has had no network since \(time(failure.sinceMs)). Nobody will be told if a room gets loud."
                : "The camera has not answered since \(time(failure.sinceMs)). Nobody will be told if that room gets loud."
        case .lowBattery: "Plug the phone in. Dozecam may not last the night on what is left."
        // Android: "…, so an alert cannot wake the screen." On iOS an AlarmKit
        // alarm still rings full-screen without notifications; what is lost is
        // the card (shared/spec/failure-alerts.md: "no alert card can be
        // shown").
        case .notificationsBlocked:
            "Notifications are turned off for Dozecam in system settings, so an alert card cannot be shown."
        // Android names full-screen notifications, which iOS does not have.
        case .screenWakeBlocked:
            "Alarms are turned off for Dozecam in system settings, so an alert cannot ring through silent mode or a Focus."
        case .audioSessionLost:
            "iOS stopped Dozecam's audio and it could not be restarted, so monitoring stops when the screen locks. Open Dozecam to restart it."
        }
    }

    /// One failure on the viewer's notice.
    func viewerNotice(_ failure: MonitoringFailure) -> String {
        "\(title(failure.reason)) · since \(time(failure.sinceMs))"
    }

    /// The failure card's title: every failure, by name.
    func cardTitle(_ failures: [MonitoringFailure]) -> String {
        failures.map { title($0.reason) }.joined(separator: " · ")
    }

    /// The failure card's text: a sentence for each failure, one per line.
    func cardBody(_ failures: [MonitoringFailure]) -> String {
        failures.map(detail).joined(separator: "\n")
    }

    /// The milder notice for a charger pulled while armed
    /// (shared/spec/failure-alerts.md#unplugging).
    static let unpluggedTitle = "Unplugged while monitoring"

    func unpluggedText(percent: Int) -> String {
        "Dozecam is running on battery (\(percent)%). It will sound an alarm at \(BatteryStatus.lowPercent)%."
    }
}

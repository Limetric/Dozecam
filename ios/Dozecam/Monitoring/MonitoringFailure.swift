import UIKit

/// One way Dozecam can stop being a baby monitor while it is armed: the port
/// of Android's `FailureReason` (shared/spec/failure-alerts.md#causes).
///
/// Each reason is keyed so it can be told apart from the last time it
/// happened: a camera that drops, comes back, and drops again is two failures,
/// and the second one is announced afresh.
enum FailureReason: Equatable, Sendable {
    /// A monitored camera the monitor has not heard from: not live, whatever
    /// the watchdog is doing about it. `networkDown` says why, when the phone
    /// itself has no network: the camera is not the thing that failed.
    case cameraUnreachable(cameraId: String, name: String, networkDown: Bool)
    /// The phone is running down with nothing to charge it.
    case lowBattery(percent: Int)
    /// Notifications are not authorised, so no alert card can be shown.
    case notificationsBlocked
    /// No alert can ring through silent mode and Sleep Focus. Android's
    /// full-screen intent access; on iOS its nearest equivalent, AlarmKit
    /// authorisation (shared/spec/failure-alerts.md#platform-differences). The
    /// Android name is kept because the shared fixtures use it.
    case screenWakeBlocked
    /// iOS only: the audio session could not be brought back (after an
    /// interruption, or it would not activate), and it is the running session
    /// that keeps the app alive with the screen locked
    /// (shared/spec/alerts-and-sound-modes.md#the-speaker-audio-focus: "a
    /// reactivation that fails is lost for good, and a failure"). No Android
    /// counterpart: its foreground service does not depend on audio.
    case audioSessionLost

    /// What identifies this failure across evaluations.
    var key: String {
        switch self {
        case .cameraUnreachable(let cameraId, _, _): "camera:\(cameraId)"
        case .lowBattery: "battery"
        case .notificationsBlocked: "notifications"
        case .screenWakeBlocked: "screen-wake"
        case .audioSessionLost: "audio-session"
        }
    }
}

/// A failure that has outlasted its grace period: what is wrong and since
/// when. `sinceMs` is wall-clock time (milliseconds since 1970), for telling
/// the user; every deadline is measured on the monotonic clock inside
/// `FailureLedger`.
struct MonitoringFailure: Equatable, Sendable {
    let reason: FailureReason
    let sinceMs: Int64
}

/// A failure that has cleared, left as a note that it happened. Both times are
/// wall-clock.
struct RecoveredFailure: Equatable, Sendable {
    let reason: FailureReason
    let sinceMs: Int64
    let clearedAtMs: Int64
}

/// The phone's battery as last reported.
struct BatteryStatus: Equatable, Sendable {
    /// Well above the point where the phone starts shutting things down: a
    /// monitor that dies at 20 % having warned at 25 % is a monitor that
    /// worked.
    static let lowPercent = 25
    static let clearMargin = 5

    let percent: Int
    let plugged: Bool

    /// Whether the battery is the problem. With hysteresis: once low, it stays
    /// low until it has climbed clear of the line or a charger is connected,
    /// so a reading that hovers on the threshold cannot raise the same alarm
    /// over and over.
    func isLow(wasLow: Bool) -> Bool {
        if plugged { return false }
        return wasLow ? percent < Self.lowPercent + Self.clearMargin : percent <= Self.lowPercent
    }

    /// Reads `UIDevice`'s battery (with battery monitoring enabled), or nil if
    /// it says nothing usable: the simulator, or monitoring not enabled, report
    /// a level of -1 and an unknown state. Full counts as plugged: iOS reports
    /// `.full` only while on a charger. iPadOS reports the level in 5 % steps
    /// (#58); the thresholds are unchanged.
    static func of(level: Float, state: UIDevice.BatteryState) -> BatteryStatus? {
        guard level >= 0, state != .unknown else { return nil }
        return BatteryStatus(
            percent: Int((level * 100).rounded()).clamped(to: 0...100),
            plugged: state == .charging || state == .full
        )
    }
}

/// Everything the ledger judges the monitor by, as of one moment. Grants can
/// be withdrawn in Settings without the app being told, so the caller asks for
/// them afresh on every judgement.
struct MonitoringHealth: Equatable, Sendable {
    /// The monitored cameras: enabled and not paused.
    var cameras: [CameraMonitorState]
    var networkOnline: Bool
    /// Nil until the first battery reading arrives.
    var battery: BatteryStatus?
    /// Notification authorisation.
    var notificationsAllowed: Bool
    /// AlarmKit authorisation on iOS (see `FailureReason.screenWakeBlocked`).
    var screenWakeAllowed: Bool
    /// iOS only: the audio session is lost for good (`Speaker`'s
    /// `.failed(.resumeFailed)` or `.failed(.refused)`). Off by default, which
    /// is what the shared fixtures, written for both platforms, assume.
    var audioSessionLost = false
}

/// Keeps the book on what is wrong, and decides when to say so: the port of
/// Android's `FailureLedger` (shared/spec/failure-alerts.md; timelines in
/// `shared/fixtures/failure-ledger/`).
///
/// Two rules are the whole design. A failure has to last the grace period
/// before it counts at all, so an ordinary reconnect never fires, and neither
/// does a permission the user is a screen away from granting; and it is
/// announced exactly once for as long as it lasts, however many times the
/// ledger is asked. An alarm for every brief reconnect would train people to
/// ignore it, which is worse than no alarm.
///
/// A value type with no clocks of its own, like `SoundDetector`: each
/// evaluation is given the monotonic time (for deadlines, so setting the
/// phone's clock moves none) and the wall time (only for the "since" the user
/// is shown). Android takes the two clocks in its constructor instead.
struct FailureLedger: Sendable {
    /// The user's range for the grace period (shared/spec/failure-alerts.md);
    /// `AppSettings.failureGraceMsRange` enforces it.
    static let minGraceMs: Int64 = 30_000
    static let maxGraceMs: Int64 = 5 * 60_000

    /// What one evaluation changed.
    struct Update: Equatable, Sendable {
        /// Every failure past its grace period, oldest first.
        let active: [MonitoringFailure]
        /// The failures that crossed into `active` on this evaluation: say
        /// these out loud.
        let announce: [MonitoringFailure]
        /// Failures that were active and are no longer.
        let recovered: [RecoveredFailure]
        /// The charger was pulled since the last evaluation, with the monitor
        /// armed.
        let unplugged: Bool

        /// The recovery worth a note on the status line, if any: the latest,
        /// less any camera that has simply stopped being monitored (paused,
        /// switched off, deleted). "Back" said of a room that may well still
        /// be dark is the wrong reassurance (shared/spec/failure-alerts.md#recovery).
        /// Android: `MonitoringService.judge`.
        func recoveryNote(monitoredCameraIds: Set<String>) -> RecoveredFailure? {
            recovered.last { recovered in
                guard case .cameraUnreachable(let cameraId, _, _) = recovered.reason else { return true }
                return monitoredCameraIds.contains(cameraId)
            }
        }
    }

    private struct Entry: Sendable {
        var reason: FailureReason
        let sinceMonotonicMs: Int64
        let sinceMs: Int64
        var announced = false
    }

    /// Insertion-ordered, so `active` is oldest first.
    private var entries: [(key: String, entry: Entry)] = []
    private var batteryLow = false
    private var plugged: Bool?

    /// Judges `health` at monotonic `nowMs` (wall time `wallNowMs`), against
    /// what was true last time.
    mutating func evaluate(_ health: MonitoringHealth, graceMs: Int64, nowMs: Int64, wallNowMs: Int64)
        -> Update
    {
        let current = reasons(health)
        let currentKeys = Set(current.map(\.key))

        var recovered: [RecoveredFailure] = []
        for (key, entry) in entries where !currentKeys.contains(key) {
            // Only a failure that was ever counted leaves a note; a flap
            // inside the grace period never happened, as far as anyone is told.
            if entry.announced {
                recovered.append(RecoveredFailure(reason: entry.reason, sinceMs: entry.sinceMs, clearedAtMs: wallNowMs))
            }
        }
        entries.removeAll { !currentKeys.contains($0.key) }

        for reason in current {
            // The name follows renames and the battery reading follows the
            // battery; the start of the failure does not move.
            if let index = entries.firstIndex(where: { $0.key == reason.key }) {
                entries[index].entry.reason = reason
            } else {
                entries.append((reason.key, Entry(reason: reason, sinceMonotonicMs: nowMs, sinceMs: wallNowMs)))
            }
        }

        var announce: [MonitoringFailure] = []
        var active: [MonitoringFailure] = []
        for index in entries.indices where nowMs - entries[index].entry.sinceMonotonicMs >= graceMs {
            let entry = entries[index].entry
            let failure = MonitoringFailure(reason: entry.reason, sinceMs: entry.sinceMs)
            if !entry.announced {
                entries[index].entry.announced = true
                announce.append(failure)
            }
            active.append(failure)
        }

        let wasPlugged = plugged
        let nowPlugged = health.battery?.plugged
        if let nowPlugged { plugged = nowPlugged }
        let unplugged = wasPlugged == true && nowPlugged == false

        return Update(active: active, announce: announce, recovered: recovered, unplugged: unplugged)
    }

    /// In a fixed order (cameras, battery, grants, then the iOS-only causes),
    /// so failures that start together are listed the same way every time.
    private mutating func reasons(_ health: MonitoringHealth) -> [FailureReason] {
        var reasons: [FailureReason] = []
        for camera in health.cameras where !camera.isLive {
            reasons.append(
                .cameraUnreachable(cameraId: camera.cameraId, name: camera.name, networkDown: !health.networkOnline)
            )
        }
        if let battery = health.battery {
            batteryLow = battery.isLow(wasLow: batteryLow)
            if batteryLow { reasons.append(.lowBattery(percent: battery.percent)) }
        }
        if !health.notificationsAllowed { reasons.append(.notificationsBlocked) }
        if !health.screenWakeAllowed { reasons.append(.screenWakeBlocked) }
        if health.audioSessionLost { reasons.append(.audioSessionLost) }
        return reasons
    }
}

/// What the failure alert does about each of the ledger's judgements, and
/// about alerts being switched off and on: the bookkeeping Android keeps in
/// `MonitoringService` (`judge`, `raiseFailureAlert`, `clearFailureAlert`,
/// `dropAlert` and its `failureAnnounced` / `failureAnnouncementPending`
/// flags), pulled out so it can be tested without the delivery around it
/// (shared/spec/failure-alerts.md#grace-and-announcement, #recovery).
///
/// Gated by alertsEnabled, like any alert. A failure that crossed its grace
/// period with alerts off, or whose card was removed by switching alerts off,
/// is still owed an announcement: the ledger will not offer it again, since it
/// announces once, and alerts coming back on is the parent settling in for
/// the night.
struct FailureAnnouncer: Sendable {
    enum Action: Equatable, Sendable {
        /// Nothing to do.
        case none
        /// Announce: post the failure card listing `failures`, waking the
        /// screen, and signal the failure alarm (`AlertSignalerState.signal`
        /// with `AlertSignalerState.monitoringFailure`), which leaves a room's
        /// alarm as it is.
        case raise([MonitoringFailure])
        /// Something cleared and something else is still wrong: update the
        /// card to list what is left, without waking the screen or sounding.
        case refresh([MonitoringFailure])
        /// Nothing is wrong any more: remove the card, and stop the alarm only
        /// if it is the failure's own (`AlertSignalerState.stopFailure`). A
        /// room's alarm is never silenced by a camera coming back.
        case clear
    }

    /// A failure card and alarm are up.
    private(set) var announced = false
    /// A failure crossed its grace period with alerts off, or lost its card to
    /// alerts being switched off, and is owed an announcement.
    private(set) var pending = false

    /// One evaluation of the ledger, carried out. The caller writes
    /// `update.active` and the recovery note where the status line and the
    /// viewer read them first, so they say what the alarm is about before it
    /// sounds.
    mutating func judge(_ update: FailureLedger.Update, alertsEnabled: Bool) -> Action {
        if !update.announce.isEmpty { return raise(update.active, alertsEnabled: alertsEnabled) }
        if update.active.isEmpty { return clear() }
        if !update.recovered.isEmpty && announced { return .refresh(update.active) }
        return .none
    }

    /// The alerts switch, on every settings change. Off: the caller silences
    /// whatever alarm is up and takes its cards down (Android's `dropAlert`);
    /// a failure still standing is owed its announcement for later, and the
    /// action is always `.none`, since the caller has removed the card with
    /// everything else. On: a failure owed an announcement is announced now,
    /// the whole set, because the card lists everything that is wrong.
    mutating func alertsChanged(enabled: Bool, active: [MonitoringFailure]) -> Action {
        guard enabled else {
            announced = false
            pending = !active.isEmpty
            return .none
        }
        guard pending, !active.isEmpty else { return .none }
        return raise(active, alertsEnabled: true)
    }

    private mutating func raise(_ failures: [MonitoringFailure], alertsEnabled: Bool) -> Action {
        guard alertsEnabled else {
            pending = true
            return .none
        }
        pending = false
        announced = true
        return .raise(failures)
    }

    private mutating func clear() -> Action {
        pending = false
        guard announced else { return .none }
        announced = false
        return .clear
    }
}

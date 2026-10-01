import Foundation
import Testing

@testable import Dozecam

/// The port of Android's `FailureLedgerTest`: the two rules that keep the
/// failure alarm from crying wolf. Nothing counts until it has lasted the grace
/// period, and what does count is announced exactly once.
///
/// The timelines live in `shared/fixtures/failure-ledger/timelines.json`; this
/// supplies the clocks and turns each step into a `MonitoringHealth`.
struct FailureLedgerTests {
    private struct Camera: Codable {
        let id: String
        let name: String
        let connection: String
        let reconnectAttempt: Int?
    }

    private struct Battery: Codable {
        let percent: Int
        let plugged: Bool
    }

    private struct Health: Codable {
        let cameras: [Camera]
        let networkOnline: Bool
        let battery: Battery?
        let notificationsAllowed: Bool
        let screenWakeAllowed: Bool
    }

    /// A failure as the fixture spells it; times are relative to the case's
    /// start.
    private struct Failure: Codable, Equatable, CustomStringConvertible {
        var reason: String
        var cameraId: String?
        var name: String?
        var networkDown: Bool?
        var percent: Int?
        var sinceMs: Int64
        var clearedAtMs: Int64?

        var description: String {
            let details = [
                cameraId.map { "cameraId: \($0)" }, name.map { "name: \($0)" },
                networkDown.map { "networkDown: \($0)" }, percent.map { "percent: \($0)" },
                "sinceMs: \(sinceMs)", clearedAtMs.map { "clearedAtMs: \($0)" },
            ]
            return "\(reason)(\(details.compactMap(\.self).joined(separator: ", ")))"
        }
    }

    private struct Expect: Codable {
        let active: [Failure]?
        let announce: [Failure]?
        let recovered: [Failure]?
        let unplugged: Bool?
    }

    private struct Step: Codable {
        let atMs: Int64
        let health: Health
        let expect: Expect?
    }

    private struct Case: Codable {
        let name: String
        let steps: [Step]
    }

    private struct Table: Codable {
        let graceMs: Int64
        let cases: [Case]
    }

    // Neither clock starts at zero, so a ledger that confused the two, or
    // measured from zero, would show.
    private let monotonicStartMs: Int64 = 100_000
    private let wallStartMs: Int64 = 1_700_000_000_000

    private func connection(_ camera: Camera) throws -> ConnectionState {
        switch camera.connection {
        case "connecting": return .connecting
        case "live": return .live
        case "reconnecting":
            let attempt = try #require(camera.reconnectAttempt, "reconnecting needs reconnectAttempt")
            return .reconnecting(attempt: attempt)
        case "offline": return .offline
        default:
            Issue.record("unknown connection \"\(camera.connection)\"")
            return .offline
        }
    }

    private func health(_ health: Health) throws -> MonitoringHealth {
        MonitoringHealth(
            cameras: try health.cameras.map {
                CameraMonitorState(cameraId: $0.id, name: $0.name, level: 0, connection: try connection($0))
            },
            networkOnline: health.networkOnline,
            battery: health.battery.map { BatteryStatus(percent: $0.percent, plugged: $0.plugged) },
            notificationsAllowed: health.notificationsAllowed,
            screenWakeAllowed: health.screenWakeAllowed
        )
    }

    private func describe(_ reason: FailureReason, sinceMs: Int64, clearedAtMs: Int64? = nil) -> Failure {
        var failure = Failure(
            reason: "", sinceMs: sinceMs - wallStartMs, clearedAtMs: clearedAtMs.map { $0 - wallStartMs })
        switch reason {
        case .cameraUnreachable(let cameraId, let name, let networkDown):
            failure.reason = "cameraUnreachable"
            failure.cameraId = cameraId
            failure.name = name
            failure.networkDown = networkDown
        case .lowBattery(let percent):
            failure.reason = "lowBattery"
            failure.percent = percent
        case .notificationsBlocked: failure.reason = "notificationsBlocked"
        case .screenWakeBlocked: failure.reason = "screenWakeBlocked"
        case .audioSessionLost: failure.reason = "audioSessionLost"
        }
        return failure
    }

    private func play(_ name: String) throws {
        let table = try Fixtures.decode(Table.self, from: "failure-ledger/timelines.json")
        let cases = table.cases.filter { $0.name == name }
        guard cases.count == 1, let fixture = cases.first else {
            Issue.record("no single fixture case \"\(name)\" in failure-ledger/timelines.json")
            return
        }
        var ledger = FailureLedger()
        var atMs: Int64 = 0
        for (index, step) in fixture.steps.enumerated() {
            try #require(step.atMs >= atMs, "\(fixture.name): step \(index) goes back in time")
            atMs = step.atMs
            let update = ledger.evaluate(
                try health(step.health), graceMs: table.graceMs,
                nowMs: monotonicStartMs + atMs, wallNowMs: wallStartMs + atMs
            )
            guard let expect = step.expect else { continue }
            let at = "\(fixture.name): step \(index) at \(step.atMs)ms"
            if let active = expect.active {
                #expect(update.active.map { describe($0.reason, sinceMs: $0.sinceMs) } == active, "\(at) active")
            }
            if let announce = expect.announce {
                #expect(update.announce.map { describe($0.reason, sinceMs: $0.sinceMs) } == announce, "\(at) announce")
            }
            if let recovered = expect.recovered {
                let actual = update.recovered.map {
                    describe($0.reason, sinceMs: $0.sinceMs, clearedAtMs: $0.clearedAtMs)
                }
                #expect(actual == recovered, "\(at) recovered")
            }
            if let unplugged = expect.unplugged {
                #expect(update.unplugged == unplugged, "\(at) unplugged")
            }
        }
    }

    @Test func aHealthyMonitorHasNothingToSay() throws {
        try play("a healthy monitor has nothing to say")
    }

    @Test func aCameraCrossingTheGracePeriodIsAnnouncedExactlyOnce() throws {
        try play("a camera crossing the grace period is announced exactly once")
    }

    /// The second drop starts its own clock rather than inheriting the last
    /// one's: a second flap is still a flap.
    @Test func aFlapInsideTheGracePeriodFiresNothingAndLeavesNoTrace() throws {
        try play("a flap inside the grace period fires nothing and leaves no trace")
    }

    /// A drop after recovery is a new failure, and is announced afresh.
    @Test func recoveryClearsTheFailureAndLeavesANote() throws {
        try play("recovery clears the failure and leaves a note")
    }

    /// Every camera goes with the network. One alarm, naming them all, rather
    /// than one per room, and the reason is the network, not the cameras.
    @Test func camerasLostTogetherAreAnnouncedTogetherWithTheNetworkAsTheReason() throws {
        try play("cameras lost together are announced together with the network as the reason")
    }

    /// Renamed and now offline: the same failure, under its current name.
    @Test func theFailuresStartDoesNotMoveAsTheReasonIsRefreshed() throws {
        try play("the failure's start does not move as the reason is refreshed")
    }

    /// Hovering just over the line does not clear it; a charger does.
    @Test func aLowBatteryOnNoChargerIsAFailureWithHysteresis() throws {
        try play("a low battery on no charger is a failure with hysteresis")
    }

    @Test func unpluggingWhileArmedIsReportedOnceOnTheTransition() throws {
        try play("unplugging while armed is reported once, on the transition")
    }

    /// A fresh ledger's first reading.
    @Test func startingUnpluggedIsNotBeingUnplugged() throws {
        try play("starting unplugged is not being unplugged")
    }

    @Test func withdrawnGrantsAreFailuresAfterTheSameGrace() throws {
        try play("withdrawn grants are failures after the same grace")
    }

    @Test func anUnknownBatteryIsNotAFailure() throws {
        try play("an unknown battery is not a failure")
    }

    // MARK: - iOS specifics

    /// The iOS-only cause keeps the same two rules as every other: nothing
    /// until the grace period, then exactly once, and a note when it clears.
    @Test func aLostAudioSessionIsAFailureAfterTheSameGrace() {
        var ledger = FailureLedger()
        var health = MonitoringHealth(
            cameras: [], networkOnline: true, battery: nil, notificationsAllowed: true, screenWakeAllowed: true,
            audioSessionLost: true
        )
        #expect(ledger.evaluate(health, graceMs: 60_000, nowMs: 5_000, wallNowMs: 1_000).active.isEmpty)
        let crossed = ledger.evaluate(health, graceMs: 60_000, nowMs: 65_000, wallNowMs: 61_000)
        #expect(crossed.announce == [MonitoringFailure(reason: .audioSessionLost, sinceMs: 1_000)])
        #expect(ledger.evaluate(health, graceMs: 60_000, nowMs: 70_000, wallNowMs: 66_000).announce.isEmpty)
        health.audioSessionLost = false
        let cleared = ledger.evaluate(health, graceMs: 60_000, nowMs: 80_000, wallNowMs: 76_000)
        #expect(cleared.recovered == [RecoveredFailure(reason: .audioSessionLost, sinceMs: 1_000, clearedAtMs: 76_000)])
    }

    /// Deadlines are monotonic: a wall clock set back by an hour mid-failure
    /// neither delays the announcement nor moves the "since".
    @Test func settingTheWallClockMovesNoDeadline() {
        var ledger = FailureLedger()
        let health = MonitoringHealth(
            cameras: [CameraMonitorState(cameraId: "a", name: "Nursery", connection: .offline)],
            networkOnline: true, battery: nil, notificationsAllowed: true, screenWakeAllowed: true
        )
        _ = ledger.evaluate(health, graceMs: 60_000, nowMs: 0, wallNowMs: 7_200_000)
        let update = ledger.evaluate(health, graceMs: 60_000, nowMs: 60_000, wallNowMs: 3_660_000)
        #expect(update.announce.map(\.sinceMs) == [7_200_000])
    }

    @Test func aCameraNoLongerMonitoredLeavesNoRecoveryNote() {
        let update = FailureLedger.Update(
            active: [],
            announce: [],
            recovered: [
                RecoveredFailure(reason: .lowBattery(percent: 20), sinceMs: 0, clearedAtMs: 10),
                RecoveredFailure(
                    reason: .cameraUnreachable(cameraId: "a", name: "Nursery", networkDown: false), sinceMs: 0,
                    clearedAtMs: 10
                ),
            ],
            unplugged: false
        )
        #expect(update.recoveryNote(monitoredCameraIds: ["a"])?.reason.key == "camera:a")
        #expect(update.recoveryNote(monitoredCameraIds: [])?.reason == .lowBattery(percent: 20))
    }

    @Test func batteryReadsUIDevice() {
        #expect(BatteryStatus.of(level: -1, state: .unknown) == nil)
        #expect(BatteryStatus.of(level: 0.25, state: .unplugged) == BatteryStatus(percent: 25, plugged: false))
        #expect(BatteryStatus.of(level: 1, state: .full) == BatteryStatus(percent: 100, plugged: true))
        #expect(BatteryStatus.of(level: 0.5, state: .charging) == BatteryStatus(percent: 50, plugged: true))
    }
}

/// The alert bookkeeping around the ledger (Android's `MonitoringService`
/// `judge`, `raiseFailureAlert`, `clearFailureAlert`, `dropAlert`).
struct FailureAnnouncerTests {
    private let camera = MonitoringFailure(
        reason: .cameraUnreachable(cameraId: "a", name: "Nursery", networkDown: false), sinceMs: 0
    )
    private let battery = MonitoringFailure(reason: .lowBattery(percent: 20), sinceMs: 5)

    private func update(
        active: [MonitoringFailure], announce: [MonitoringFailure] = [], recovered: [RecoveredFailure] = []
    ) -> FailureLedger.Update {
        FailureLedger.Update(active: active, announce: announce, recovered: recovered, unplugged: false)
    }

    @Test func anAnnouncementRaisesEverythingPastGrace() {
        var announcer = FailureAnnouncer()
        let action = announcer.judge(update(active: [camera, battery], announce: [battery]), alertsEnabled: true)
        #expect(action == .raise([camera, battery]))
        #expect(announcer.announced)
    }

    @Test func somethingClearingWhileSomethingRemainsRefreshesTheCardWithoutWaking() {
        var announcer = FailureAnnouncer()
        _ = announcer.judge(update(active: [camera, battery], announce: [camera, battery]), alertsEnabled: true)
        let cleared = RecoveredFailure(reason: camera.reason, sinceMs: 0, clearedAtMs: 9)
        #expect(
            announcer.judge(update(active: [battery], recovered: [cleared]), alertsEnabled: true) == .refresh([battery])
        )
        #expect(announcer.judge(update(active: [battery]), alertsEnabled: true) == .none)
        #expect(announcer.judge(update(active: []), alertsEnabled: true) == .clear)
        #expect(announcer.judge(update(active: []), alertsEnabled: true) == .none)
    }

    /// Nothing announced, nothing to take down.
    @Test func aFlapThatWasNeverAnnouncedClearsNothing() {
        var announcer = FailureAnnouncer()
        #expect(announcer.judge(update(active: []), alertsEnabled: true) == .none)
    }

    @Test func aFailureCrossingGraceWithAlertsOffIsAnnouncedWhenTheyComeBackOn() {
        var announcer = FailureAnnouncer()
        #expect(announcer.judge(update(active: [camera], announce: [camera]), alertsEnabled: false) == .none)
        #expect(announcer.pending)
        #expect(announcer.alertsChanged(enabled: true, active: [camera, battery]) == .raise([camera, battery]))
        #expect(!announcer.pending)
        #expect(announcer.alertsChanged(enabled: true, active: [camera, battery]) == .none)
    }

    /// Switching alerts off removes the card; a failure still standing is owed
    /// its announcement again.
    @Test func switchingAlertsOffAndOnReannouncesAFailureStillStanding() {
        var announcer = FailureAnnouncer()
        _ = announcer.judge(update(active: [camera], announce: [camera]), alertsEnabled: true)
        #expect(announcer.alertsChanged(enabled: false, active: [camera]) == .none)
        #expect(!announcer.announced)
        #expect(announcer.alertsChanged(enabled: true, active: [camera]) == .raise([camera]))
    }

    @Test func aFailureThatClearedWhileAlertsWereOffIsOwedNothing() {
        var announcer = FailureAnnouncer()
        _ = announcer.judge(update(active: [camera], announce: [camera]), alertsEnabled: false)
        _ = announcer.judge(update(active: []), alertsEnabled: false)
        #expect(announcer.alertsChanged(enabled: true, active: []) == .none)
        #expect(!announcer.pending)
    }
}

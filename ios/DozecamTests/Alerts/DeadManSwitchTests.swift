import Foundation
import Testing

@testable import Dozecam

@MainActor
final class DeadManSwitchTests {
    let alarms = FakeAlarmScheduler()
    let notices = FakeNoticeCenter()
    let access = FakeAlertAccess()
    let defaults: UserDefaults
    var clock = Date(timeIntervalSince1970: 2_000_000)

    init() {
        defaults = UserDefaults(suiteName: "dead-man-\(UUID().uuidString)")!
    }

    func makeSwitch(lead: TimeInterval = 180) -> DeadManSwitch {
        DeadManSwitch(
            alarms: alarms, notices: notices, access: access, lead: lead, now: { [unowned self] in clock },
            defaults: defaults)
    }

    // MARK: - Heartbeats

    @Test func aHeartbeatSetsTheAlarmAndTheNoticeLeadAhead() async throws {
        let deadMan = makeSwitch()
        await deadMan.heartbeat()

        let spec = try #require(alarms.scheduledSpecs.first)
        #expect(spec.fireDate == clock.addingTimeInterval(180))
        #expect(spec.purpose == .deadMan)
        #expect(spec.tone == .failure)
        #expect(spec.title == "Dozecam stopped monitoring")
        let notice = try #require(notices.showing[DeadManSwitch.noticeId])
        #expect(notice.delay == 180)
        #expect(notice.level == .timeSensitive)
        #expect(notice.sound == .named("monitoring_failure.caf"))
        #expect(notice.route == .failure)
        #expect(notice.body.contains("Open Dozecam"))
    }

    @Test func theLeadIsConfigurable() async throws {
        let deadMan = makeSwitch(lead: 120)
        await deadMan.heartbeat()
        #expect(try #require(alarms.scheduledSpecs.first).fireDate == clock.addingTimeInterval(120))
        #expect(notices.showing[DeadManSwitch.noticeId]?.delay == 120)
    }

    @Test func everyHeartbeatSchedulesTheNewAlarmBeforeEndingTheOld() async {
        let deadMan = makeSwitch()
        await deadMan.heartbeat()
        clock += 30
        await deadMan.heartbeat()
        clock += 30
        await deadMan.heartbeat()

        let ids = alarms.scheduledIds
        #expect(ids.count == 3)
        let order = alarms.calls.map { call in
            switch call {
            case .schedule(let id, _): "schedule \(ids.firstIndex(of: id)!)"
            case .end(let id): "end \(ids.firstIndex(of: id)!)"
            }
        }
        #expect(order == ["schedule 0", "schedule 1", "end 0", "schedule 2", "end 1"])
        #expect(alarms.liveIds == [ids[2]])
        #expect(alarms.scheduledSpecs[2].fireDate == clock.addingTimeInterval(180))
        #expect(notices.posted.count == 3)
        #expect(deadMan.deadline == clock.addingTimeInterval(180))
    }

    @Test func withoutAlarmKitOnlyTheNoticeIsSet() async {
        access.alarms = .denied
        let deadMan = makeSwitch()
        await deadMan.heartbeat()

        #expect(alarms.calls.isEmpty)
        #expect(notices.showing[DeadManSwitch.noticeId] != nil)
    }

    @Test func alarmKitWithdrawnEndsTheAlarmLeftBehind() async {
        let deadMan = makeSwitch()
        await deadMan.heartbeat()
        let first = alarms.scheduledIds[0]

        access.alarms = .denied
        clock += 30
        await deadMan.heartbeat()

        #expect(alarms.endedIds == [first])
        #expect(alarms.liveIds.isEmpty)
        #expect(deadMan.alarmId == nil)
    }

    /// Left in place, the old alarm would ring at a deadline this live app
    /// has already passed: never ring while healthy.
    @Test func aRefusedAlarmEndsTheOldOne() async {
        let deadMan = makeSwitch()
        await deadMan.heartbeat()
        let first = alarms.scheduledIds[0]

        alarms.refuse = true
        clock += 30
        await deadMan.heartbeat()

        #expect(alarms.endedIds == [first])
        #expect(alarms.liveIds.isEmpty)
        #expect(notices.posted.count == 2)
    }

    @Test func heartbeatsInFlightFoldIntoOneFollowUp() async {
        let deadMan = makeSwitch()
        alarms.holding = true
        let first = Task { await deadMan.heartbeat() }
        await settleAlerts()
        await deadMan.heartbeat()
        await deadMan.heartbeat()
        #expect(alarms.heldCount == 1)

        alarms.holding = false
        alarms.release()
        await first.value

        #expect(alarms.scheduledIds.count == 2)
        #expect(alarms.liveIds == [alarms.scheduledIds[1]])
    }

    // MARK: - Disarm

    @Test func disarmTakesDownTheAlarmAndTheNotice() async {
        let deadMan = makeSwitch()
        await deadMan.heartbeat()

        await deadMan.disarm()

        #expect(alarms.liveIds.isEmpty)
        #expect(notices.showing.isEmpty)
        #expect(deadMan.alarmId == nil)
        #expect(!deadMan.isArmed)
    }

    @Test func aHeartbeatStillWaitingOnAlarmKitLeavesNothingBehindADisarm() async {
        let deadMan = makeSwitch()
        await deadMan.heartbeat()
        alarms.holding = true
        clock += 30
        let beat = Task { await deadMan.heartbeat() }
        await settleAlerts()

        await deadMan.disarm()
        alarms.release()
        await beat.value

        #expect(alarms.liveIds.isEmpty)
        #expect(notices.showing.isEmpty)
        #expect(deadMan.alarmId == nil)
    }

    @Test func thePreviousRunsAlarmIsEndedByTheFirstHeartbeat() async {
        await makeSwitch().heartbeat()
        let leftover = alarms.scheduledIds[0]

        // The app died and was opened again: a new switch, the same defaults.
        let relaunched = makeSwitch()
        await relaunched.heartbeat()

        #expect(alarms.endedIds == [leftover])
        #expect(alarms.liveIds == [alarms.scheduledIds[1]])
    }

    @Test func thePreviousRunsAlarmIsEndedByDisarm() async {
        await makeSwitch().heartbeat()
        await makeSwitch().disarm()
        #expect(alarms.liveIds.isEmpty)
    }
}

/// The dead-man, as a record.
@MainActor
final class FakeDeadMan: DeadManArming {
    private(set) var heartbeats = 0
    private(set) var disarms = 0
    func heartbeat() async { heartbeats += 1 }
    func disarm() async { disarms += 1 }
}

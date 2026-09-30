import Foundation
import Testing

@testable import Dozecam

@MainActor
struct AlarmKitAlertsTests {
    let scheduler = FakeAlarmScheduler()
    let start = Date(timeIntervalSince1970: 1_000_000)
    let alerts: AlarmKitAlerts
    static let nursery = AlertSubject.room(cameraId: "cam-1", name: "Nursery")
    static let porch = AlertSubject.room(cameraId: "cam-2", name: "Porch")

    init() {
        let start = start
        alerts = AlarmKitAlerts(scheduler: scheduler, now: { start })
    }

    // MARK: - Raising

    @Test func aRoomRingsASecondFromNowWithTheSystemSoundAndItsName() async throws {
        try await alerts.raise(Self.nursery)

        let spec = try #require(scheduler.scheduledSpecs.first)
        #expect(spec.title == "Nursery is loud")
        #expect(spec.fireDate == start.addingTimeInterval(1))
        #expect(spec.tone == nil)
        #expect(spec.purpose == .room && spec.cameraId == "cam-1")
        #expect(alerts.ringing == Self.nursery)
    }

    @Test func aFailureRingsWithItsOwnToneAndWording() async throws {
        try await alerts.raise(.failure(title: "Can't reach Nursery"))

        let spec = try #require(scheduler.scheduledSpecs.first)
        #expect(spec.title == "Can't reach Nursery")
        #expect(spec.tone == .failure)
        #expect(spec.purpose == .failure && spec.cameraId == nil)
    }

    @Test func theTestIsTheRealAlarmInOtherWords() async throws {
        try await alerts.raise(.test)
        let spec = try #require(scheduler.scheduledSpecs.first)
        #expect(spec.title == "Dozecam test alert")
        #expect(spec.tone == nil)
    }

    @Test func raisingWhatIsRingingDoesNothing() async throws {
        try await alerts.raise(Self.nursery)
        try await alerts.raise(Self.nursery)
        #expect(scheduler.scheduledIds.count == 1)
    }

    @Test func anotherRoomRePointsTheOneAlarm() async throws {
        try await alerts.raise(Self.nursery)
        let first = scheduler.scheduledIds[0]
        scheduler.ring(first)

        try await alerts.raise(Self.porch)

        let second = scheduler.scheduledIds[1]
        // The new one first, then the old one ended: one alarm at a time.
        #expect(scheduler.calls.suffix(2) == [.schedule(second, scheduler.scheduledSpecs[1]), .end(first)])
        #expect(scheduler.liveIds == [second])
        #expect(alerts.ringing == Self.porch)
        #expect(scheduler.scheduledSpecs[1].title == "Porch is loud")
    }

    @Test func aRefusedRaiseLeavesWhatWasRinging() async throws {
        try await alerts.raise(Self.nursery)
        scheduler.refuse = true

        await #expect(throws: FakeAlarmScheduler.Refused.self) { try await alerts.raise(Self.porch) }

        #expect(alerts.ringing == Self.nursery)
        #expect(scheduler.endedIds.isEmpty)
    }

    @Test func aRefusedFirstRaiseLeavesNothingRinging() async {
        scheduler.refuse = true
        await #expect(throws: FakeAlarmScheduler.Refused.self) { try await alerts.raise(Self.nursery) }
        #expect(alerts.ringing == nil)
    }

    // MARK: - Stopping

    @Test func stoppingEndsTheAlarm() async throws {
        try await alerts.raise(Self.nursery)
        let id = scheduler.scheduledIds[0]
        scheduler.ring(id)

        alerts.stop()

        #expect(scheduler.endedIds == [id])
        #expect(alerts.ringing == nil)
    }

    @Test func stoppingWithNothingRingingDoesNothing() {
        alerts.stop()
        #expect(scheduler.calls.isEmpty)
    }

    @Test func aStopWhileAlarmKitIsAnsweringEndsTheNewAlarm() async throws {
        scheduler.holding = true
        let raise = Task { try await alerts.raise(Self.nursery) }
        await settleAlerts()
        #expect(scheduler.heldCount == 1)

        alerts.stop()
        scheduler.release()
        try await raise.value

        let id = scheduler.scheduledIds[0]
        #expect(scheduler.endedIds == [id])
        #expect(scheduler.liveIds.isEmpty)
        #expect(alerts.ringing == nil)
    }

    @Test func aNewerRaiseWinsOverOneStillInFlight() async throws {
        scheduler.holding = true
        let first = Task { try await alerts.raise(Self.nursery) }
        await settleAlerts()
        let second = Task { try await alerts.raise(Self.porch) }
        await settleAlerts()
        scheduler.release()
        try await first.value
        try await second.value

        #expect(alerts.ringing == Self.porch)
        #expect(scheduler.liveIds.count == 1)
        #expect(scheduler.scheduledSpecs.last?.title == "Porch is loud")
    }

    // MARK: - Acknowledgement

    @Test func theUserStoppingItIsAnAcknowledgement() async throws {
        var acknowledgements = alerts.acknowledgements.makeAsyncIterator()
        try await alerts.raise(Self.nursery)
        let id = scheduler.scheduledIds[0]
        scheduler.ring(id)
        await settleAlerts()
        #expect(alerts.isAlerting)

        scheduler.userStops(id)

        #expect(await acknowledgements.next() == Self.nursery)
        #expect(alerts.ringing == nil)
        #expect(!alerts.isAlerting)
    }

    /// AlarmKit listed the alarm before `schedule` returned, and no later
    /// list shows it: the user's Stop still counts.
    @Test func aStopIsHeardWhenTheAlarmWasListedWhileBeingScheduled() async throws {
        var acknowledgements = alerts.acknowledgements.makeAsyncIterator()
        scheduler.holding = true
        scheduler.listsOnSchedule = false
        let alerts = alerts
        let raising = Task { try await alerts.raise(Self.nursery) }
        #expect(await eventually { scheduler.heldCount == 1 })
        guard case .schedule(let id, _) = scheduler.calls.last else {
            Issue.record("no schedule call")
            return
        }
        scheduler.add(AlarmSnapshot(id: id, phase: .scheduled))
        await settleAlerts()
        scheduler.release()
        try await raising.value

        scheduler.userStops(id)
        #expect(await acknowledgements.next() == Self.nursery)
    }

    @Test func theAppEndingItIsNoAcknowledgement() async throws {
        try await alerts.raise(Self.nursery)
        scheduler.ring(scheduler.scheduledIds[0])
        await settleAlerts()

        try await alerts.raise(Self.porch)
        alerts.stop()
        await settleAlerts()

        let collected = Task { () -> [AlertSubject] in
            var all: [AlertSubject] = []
            for await subject in alerts.acknowledgements { all.append(subject) }
            return all
        }
        await settleAlerts()
        collected.cancel()
        #expect(await collected.value.isEmpty)
    }

    @Test func otherAlarmsComingAndGoingAreIgnored() async throws {
        try await alerts.raise(Self.nursery)
        let deadMan = AlarmSnapshot(id: UUID(), phase: .scheduled)
        scheduler.add(deadMan)
        scheduler.userStops(deadMan.id)
        await settleAlerts()

        #expect(alerts.ringing == Self.nursery)
    }
}

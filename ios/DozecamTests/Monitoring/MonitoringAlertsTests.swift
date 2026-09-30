import Foundation
import Testing

@testable import Dozecam

/// The monitor raising alerts and failures through fake delivery: AlarmKit,
/// the fallback tone, the cards and the dead-man.
@MainActor
private final class Harness {
    let scheduler = ManualScheduler()
    let hardware = FakeSpeakerHardware()
    let speaker: Speaker
    let alarms = FakeAlarmAlerting()
    let access = FakeAlertAccess()
    let tone = FakeAlarmTonePlayer()
    let vibrator = FakeAlarmVibrator()
    let center = FakeNoticeCenter()
    let deadMan = FakeDeadMan()
    let batterySource = FakeBatterySource()
    let dependencies: AppDependencies
    let service: MonitoringService
    private let players = AudioPlayers()

    static let cameras = ["nursery", "twins"].map {
        Camera(id: $0, name: $0.capitalized, url: "rtsp://cam/\($0)")
    }

    init(settings: @escaping @Sendable (inout AppSettings) -> Void = { _ in }) async throws {
        dependencies = AppDependencies.isolated()
        for camera in Self.cameras { try await dependencies.cameras.upsert(camera) }
        await dependencies.appSettings.update { current in
            var next = current
            settings(&next)
            return next
        }
        speaker = Speaker(hardware: hardware, mix: SpeakerMix())
        let alerts = AlertCenter(
            delivery: AlertCenter.Delivery(
                alarms: alarms, access: access, tone: tone, vibrator: vibrator,
                notices: MonitoringNotices(center: center), deadMan: deadMan),
            scheduler: scheduler)
        let players = players
        service = MonitoringService(
            dependencies: dependencies, speaker: speaker, makePlayer: { id, _ in players.make(id) }, alerts: alerts,
            battery: BatteryMonitor(source: batterySource), scheduler: scheduler)
    }

    func player(_ id: String) -> FakeAudioPlayer? { players.players[id] }

    /// Every room decodes a quiet buffer, so it is live.
    func allLive() {
        for camera in Self.cameras { hear(camera.id, rms: 0.01) }
    }

    func hear(_ id: String, rms: Float, atMs: Int64? = nil) {
        player(id)?.emit(.levels([LevelSample(rms: rms, atMs: atMs ?? scheduler.nowMs)]))
    }

    /// The nursery loud for longer than the sustain: a trigger.
    func nurseryCries() {
        let start = scheduler.nowMs
        hear("nursery", rms: 0.3, atMs: start)
        hear("nursery", rms: 0.3, atMs: start + 1_600)
    }

    /// Advances the clock second by second, keeping the rooms live.
    func pass(seconds: Int) {
        for _ in 0..<seconds {
            scheduler.advance(by: 1_000)
            allLive()
        }
    }

    func settle() async {
        for _ in 0..<50 { await Task.yield() }
    }
}

@MainActor
private final class AudioPlayers {
    var players: [String: FakeAudioPlayer] = [:]

    func make(_ id: String) -> any AudioPlayer {
        let player = FakeAudioPlayer()
        players[id] = player
        return player
    }
}

@MainActor
struct MonitoringAlertsTests {
    // MARK: - A loud room

    @Test func aLoudRoomPostsItsCardAndRingsAlarmKit() async throws {
        let harness = try await Harness()
        harness.service.arm()
        harness.allLive()
        harness.nurseryCries()
        #expect(await eventually { harness.alarms.raised == [.room(cameraId: "nursery", name: "Nursery")] })
        #expect(await eventually { harness.center.showing[MonitoringNotices.alertId] != nil })
        let card = try #require(harness.center.showing[MonitoringNotices.alertId])
        #expect(card.route == .room(cameraId: "nursery"))
        #expect(card.level == .timeSensitive)
        #expect(harness.tone.calls.isEmpty, "AlarmKit rings on its own")
    }

    @Test func withoutAlarmKitTheToneRampsAndRepeats() async throws {
        let harness = try await Harness()
        harness.access.alarms = .denied
        harness.service.arm()
        harness.allLive()
        harness.nurseryCries()
        #expect(harness.tone.calls.first == .start(.room, AlarmSchedule.rampStart))
        #expect(harness.vibrator.pulses == 1)
        harness.pass(seconds: 8)
        #expect(harness.tone.calls.filter { if case .start = $0 { true } else { false } }.count == 2)
        #expect(harness.alarms.raised.isEmpty)
    }

    @Test func alertsOffReachNobody() async throws {
        let harness = try await Harness { $0.alertsEnabled = false }
        harness.service.arm()
        harness.allLive()
        harness.nurseryCries()
        await harness.settle()
        #expect(harness.service.lastTrigger?.cameraId == "nursery", "the detector still ran")
        #expect(harness.alarms.raised.isEmpty)
        #expect(harness.center.posted.isEmpty)
    }

    @Test func switchingAlertsOffStopsTheAlarmAndTakesTheCardsDown() async throws {
        let harness = try await Harness()
        harness.service.arm()
        await harness.settle()
        harness.allLive()
        harness.nurseryCries()
        #expect(await eventually { harness.alarms.ringing != nil })
        await harness.dependencies.appSettings.update { current in
            var next = current
            next.alertsEnabled = false
            return next
        }
        #expect(await eventually { harness.alarms.ringing == nil })
        await harness.service.alerts.flushNotices()
        #expect(harness.center.showing[MonitoringNotices.alertId] == nil)
    }

    /// With alerts off the app dying wakes nobody either; back on, the
    /// dead-man is pushed back again.
    @Test func theDeadManFollowsTheAlertsSwitch() async throws {
        let harness = try await Harness()
        harness.service.arm()
        await harness.settle()
        #expect(await eventually { harness.deadMan.heartbeats == 1 })
        await harness.dependencies.appSettings.update { current in
            var next = current
            next.alertsEnabled = false
            return next
        }
        #expect(await eventually { harness.deadMan.disarms == 1 })
        harness.pass(seconds: 30)
        await harness.settle()
        #expect(harness.deadMan.heartbeats == 1, "no heartbeat arms it while alerts are off")

        await harness.dependencies.appSettings.update { current in
            var next = current
            next.alertsEnabled = true
            return next
        }
        #expect(await eventually { harness.deadMan.heartbeats == 2 })
    }

    /// A card withdrawn before its post has gone out does not appear after.
    @Test func aCardWithdrawnWhileBeingPostedStaysDown() async throws {
        let harness = try await Harness()
        harness.service.arm()
        harness.allLive()
        harness.nurseryCries()
        harness.service.pause("nursery")
        await harness.service.alerts.flushNotices()
        #expect(harness.center.showing[MonitoringNotices.alertId] == nil)
    }

    // MARK: - Answering

    @Test func aTouchOnTheViewerStopsTheAlarm() async throws {
        let harness = try await Harness()
        harness.service.arm()
        harness.allLive()
        harness.nurseryCries()
        #expect(await eventually { harness.alarms.ringing != nil })
        harness.service.acknowledge()
        #expect(harness.alarms.ringing == nil)
        #expect(!harness.service.alerts.isAlarming)
    }

    @Test func stopOnTheLockScreenIsAnAnswer() async throws {
        let harness = try await Harness()
        harness.service.arm()
        harness.allLive()
        harness.nurseryCries()
        #expect(await eventually { harness.alarms.ringing != nil })
        harness.alarms.userStops()
        #expect(await eventually { !harness.service.alerts.isAlarming })
    }

    /// AlarmKit refusing leaves the tone to take over at once, not at the
    /// next repeat.
    @Test func aRefusedAlarmKitAlarmFallsBackToTheToneAtOnce() async throws {
        let harness = try await Harness()
        harness.alarms.refuse = true
        harness.service.arm()
        harness.allLive()
        harness.nurseryCries()
        #expect(
            await eventually { harness.tone.calls.contains { if case .start(.room, _) = $0 { true } else { false } } })
        #expect(harness.alarms.stops >= 1, "whatever AlarmKit kept ringing is stopped")
    }

    /// A refusal that arrives for an alarm already answered and replaced
    /// leaves the newer alarm on AlarmKit.
    @Test func aLateRefusalLeavesTheNewerAlarmAlone() async throws {
        let harness = try await Harness()
        harness.service.arm()
        harness.allLive()
        harness.alarms.holding = true
        harness.nurseryCries()
        #expect(await eventually { harness.alarms.heldCount == 1 })
        harness.service.acknowledge()
        harness.alarms.refuseHeld = true
        harness.pass(seconds: 20)
        harness.alarms.holding = false
        harness.nurseryCries()
        #expect(await eventually { harness.alarms.ringing == .room(cameraId: "nursery", name: "Nursery") })
        harness.alarms.releaseHeld()
        await harness.settle()
        #expect(harness.alarms.ringing != nil)
        #expect(!harness.tone.calls.contains { if case .start = $0 { true } else { false } })
    }

    /// Two rooms while AlarmKit is still answering: a refusal of the first
    /// cannot take delivery away from the second.
    @Test func aLateRefusalForAReplacedRoomLeavesTheNewRoomOnAlarmKit() async throws {
        let harness = try await Harness()
        harness.service.arm()
        harness.allLive()
        harness.alarms.holding = true
        harness.alarms.refuseHeld = true
        harness.nurseryCries()
        #expect(await eventually { harness.alarms.heldCount == 1 })
        harness.alarms.holding = false
        let start = harness.scheduler.nowMs
        harness.hear("twins", rms: 0.3, atMs: start)
        harness.hear("twins", rms: 0.3, atMs: start + 1_600)
        #expect(await eventually { harness.alarms.ringing == .room(cameraId: "twins", name: "Twins") })
        harness.alarms.releaseHeld()
        await harness.settle()
        #expect(harness.alarms.ringing == .room(cameraId: "twins", name: "Twins"))
        #expect(!harness.tone.calls.contains { if case .start = $0 { true } else { false } })
    }

    /// An answer before the queued raise runs means nothing rings.
    @Test func anAlarmAnsweredBeforeItIsRaisedNeverRings() async throws {
        let harness = try await Harness()
        harness.service.arm()
        harness.allLive()
        harness.nurseryCries()
        harness.service.acknowledge()
        await harness.settle()
        #expect(harness.alarms.raised.isEmpty)
        #expect(harness.alarms.ringing == nil)
    }

    @Test func theAlarmGivesUpFiveMinutesAfterTheLastTrigger() async throws {
        let harness = try await Harness()
        harness.access.alarms = .denied
        harness.service.arm()
        harness.allLive()
        harness.nurseryCries()
        #expect(harness.service.alerts.isAlarming)
        // AlarmKit denied is itself a failure, announced once past its 60 s
        // grace; a failure announced during a cry extends the give-up, as on
        // Android, so the alarm lasts until 5 min after that.
        harness.pass(seconds: 301)
        #expect(harness.service.alerts.isAlarming)
        harness.pass(seconds: 60)
        #expect(!harness.service.alerts.isAlarming)
        #expect(harness.tone.calls.last == .stop)
    }

    @Test func aRoomLeavingTheSetTakesItsAlertWithIt() async throws {
        let harness = try await Harness()
        harness.service.arm()
        harness.allLive()
        harness.nurseryCries()
        #expect(await eventually { harness.center.showing[MonitoringNotices.alertId] != nil })
        harness.service.pause("nursery")
        #expect(!harness.service.alerts.isAlarming)
        await harness.service.alerts.flushNotices()
        #expect(harness.center.showing[MonitoringNotices.alertId] == nil)
    }

    // MARK: - Listen mode

    /// The only room heard is not sounded, and its card does not interrupt.
    @Test func aRoomAlreadyHeardDoesNotAlarm() async throws {
        let harness = try await Harness { $0.soundMode = .allAloud }
        harness.service.arm()
        await harness.settle()
        harness.hear("nursery", rms: 0.01)
        #expect(await eventually { harness.service.listeningCameraIds == ["nursery"] })
        harness.nurseryCries()
        #expect(await eventually { harness.center.showing[MonitoringNotices.alertId] != nil })
        #expect(harness.center.showing[MonitoringNotices.alertId]?.level == .passive)
        #expect(!harness.service.alerts.isAlarming)
    }

    // MARK: - Failures

    @Test func aCameraDownPastGraceIsAnnouncedOnceAndClearedWhenBack() async throws {
        let harness = try await Harness { $0.failureGraceMs = 30_000 }
        harness.service.arm()
        harness.allLive()
        // The twins go quiet: no buffers, so the room stops being live.
        for _ in 0..<35 {
            harness.scheduler.advance(by: 1_000)
            harness.hear("nursery", rms: 0.01)
        }
        #expect(harness.service.failures.count == 1)
        #expect(await eventually { harness.alarms.raised.count == 1 })
        guard case .failure = harness.alarms.raised.first else {
            Issue.record("expected a failure alarm, got \(harness.alarms.raised)")
            return
        }
        #expect(await eventually { harness.center.showing[MonitoringNotices.failureId] != nil })

        for _ in 0..<30 {
            harness.scheduler.advance(by: 1_000)
            harness.hear("nursery", rms: 0.01)
        }
        #expect(harness.alarms.raised.count == 1, "once for as long as it lasts")

        // Back: the watchdog reconnects and the room decodes again.
        for _ in 0..<10 {
            harness.scheduler.advance(by: 1_000)
            harness.allLive()
        }
        #expect(harness.service.failures.isEmpty)
        #expect(harness.service.recovered != nil)
        await harness.service.alerts.flushNotices()
        #expect(harness.center.showing[MonitoringNotices.failureId] == nil)
    }

    /// The card follows the battery down while it stays low, quietly.
    @Test func theFailureCardFollowsItsDetails() async throws {
        let harness = try await Harness { $0.failureGraceMs = 30_000 }
        harness.batterySource.reading = BatteryReading(level: 0.2, power: .unplugged)
        harness.service.arm()
        harness.pass(seconds: 31)
        await harness.service.alerts.flushNotices()
        let first = try #require(harness.center.showing[MonitoringNotices.failureId])
        #expect(first.title.contains("20"))

        harness.batterySource.set(BatteryReading(level: 0.15, power: .unplugged))
        harness.pass(seconds: 1)
        await harness.service.alerts.flushNotices()
        let updated = try #require(harness.center.showing[MonitoringNotices.failureId])
        #expect(updated.title.contains("15"))
        #expect(updated.level == .passive, "an update never sounds or wakes")
        #expect(harness.alarms.raised.count == 1)
    }

    // MARK: - The dead-man and Exit

    @Test func theDeadManIsPushedBackAndExitTakesEverythingDown() async throws {
        let harness = try await Harness()
        harness.service.arm()
        #expect(await eventually { harness.deadMan.heartbeats == 1 })
        harness.pass(seconds: 30)
        #expect(await eventually { harness.deadMan.heartbeats == 2 })

        harness.nurseryCries()
        #expect(await eventually { harness.alarms.ringing != nil })
        harness.service.exit()
        #expect(harness.alarms.ringing == nil)
        #expect(await eventually { harness.deadMan.disarms == 1 })
        await harness.service.alerts.flushNotices()
        #expect(harness.center.showing.isEmpty)
    }
}

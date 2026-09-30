import Foundation
import Testing

@testable import Dozecam

/// The monitor with fake audio players, a fake speaker and isolated stores.
@MainActor
private final class Harness {
    let scheduler = ManualScheduler()
    let hardware = FakeSpeakerHardware()
    let speaker: Speaker
    let dependencies: AppDependencies
    let service: MonitoringService
    private(set) var players: [String: FakeAudioPlayer] = [:]

    static let cameras = ["nursery", "twins"].map {
        Camera(id: $0, name: $0.capitalized, url: "rtsp://cam/\($0)")
    }

    init(cameras: [Camera] = Harness.cameras, settings: @escaping @Sendable (inout AppSettings) -> Void = { _ in })
        async throws
    {
        dependencies = AppDependencies.isolated()
        for camera in cameras { try await dependencies.cameras.upsert(camera) }
        await dependencies.appSettings.update { current in
            var next = current
            settings(&next)
            return next
        }
        speaker = Speaker(hardware: hardware, mix: SpeakerMix())
        let box = PlayerBox()
        service = MonitoringService(
            dependencies: dependencies, speaker: speaker,
            makePlayer: { id, _ in box.make(id) }, scheduler: scheduler)
        box.harness = self
    }

    func record(_ player: FakeAudioPlayer, for id: String) { players[id] = player }

    /// A room decodes a buffer at `rms`, stamped `atMs` on the monitor's clock.
    func hear(_ id: String, rms: Float, atMs: Int64? = nil) {
        players[id]?.emit(.levels([LevelSample(rms: rms, atMs: atMs ?? scheduler.nowMs)]))
    }

    /// Lets the service's followers and observers run.
    func settle() async {
        for _ in 0..<50 { await Task.yield() }
    }
}

/// Hands each new player to the harness, which is not built yet when the
/// service is.
@MainActor
private final class PlayerBox {
    weak var harness: Harness?

    func make(_ id: String) -> any AudioPlayer {
        let player = FakeAudioPlayer()
        harness?.record(player, for: id)
        return player
    }
}

@MainActor
struct MonitoringServiceTests {
    // MARK: - Arming and exit

    @Test func armingListensToEveryEnabledCameraAndTakesTheSpeaker() async throws {
        let harness = try await Harness()
        #expect(harness.service.arm())
        #expect(harness.service.isRunning)
        #expect(Set(harness.service.monitors.keys) == ["nursery", "twins"])
        #expect(harness.players["nursery"]?.plays == [.rtsp(url: "rtsp://cam/nursery")])
        #expect(harness.speaker.isGranted)
        // Arming again is a no-op.
        #expect(!harness.service.arm())
    }

    @Test func nothingToListenToDoesNotArm() async throws {
        let harness = try await Harness(cameras: [])
        #expect(!harness.service.arm())
        #expect(!harness.service.isRunning)
    }

    @Test func deniedLocalNetworkDoesNotArm() async throws {
        let harness = try await Harness()
        harness.dependencies.localNetwork.record(.denied)
        #expect(!harness.service.arm())
    }

    @Test func exitStopsEverythingAndHoldsOffUntilTheViewerOpensAgain() async throws {
        let harness = try await Harness()
        harness.service.arm()
        harness.service.pause("twins")
        let nursery = try #require(harness.players["nursery"])

        harness.service.exit()
        #expect(!harness.service.isRunning)
        #expect(harness.service.monitors.isEmpty)
        #expect(nursery.released)
        #expect(harness.speaker.status == .stopped)
        #expect(harness.service.pausedIds.isEmpty, "the next open watches every room")
        #expect(!harness.service.arm(), "nothing re-arms on the way out")

        harness.service.clearExit()
        #expect(harness.service.arm())
    }

    @Test func switchingEveryCameraOffStopsTheMonitor() async throws {
        let harness = try await Harness()
        harness.service.arm()
        await harness.settle()
        for camera in Harness.cameras { try await harness.dependencies.cameras.setEnabled(id: camera.id, false) }
        #expect(await eventually { !harness.service.isRunning })
    }

    // MARK: - Which rooms

    @Test func aPausedRoomHasNoMonitorAndComesBackOnResume() async throws {
        let harness = try await Harness()
        harness.service.arm()
        let twins = try #require(harness.players["twins"])
        harness.service.pause("twins")
        #expect(Set(harness.service.monitors.keys) == ["nursery"])
        #expect(twins.released)

        harness.service.resume("twins")
        #expect(Set(harness.service.monitors.keys) == ["nursery", "twins"])
    }

    @Test func aRoomSwitchedOffLeavesAndOneSwitchedOnJoins() async throws {
        let harness = try await Harness()
        harness.service.arm()
        await harness.settle()
        try await harness.dependencies.cameras.setEnabled(id: "twins", false)
        #expect(await eventually { Set(harness.service.monitors.keys) == ["nursery"] })
        try await harness.dependencies.cameras.upsert(Camera(id: "playroom", name: "Playroom", url: "rtsp://cam/p"))
        #expect(await eventually { Set(harness.service.monitors.keys) == ["nursery", "playroom"] })
    }

    // MARK: - Detection

    @Test func aRoomLoudForTheSustainTriggers() async throws {
        let harness = try await Harness()
        harness.service.arm()
        harness.hear("nursery", rms: 0.3, atMs: 0)
        #expect(harness.service.phases["nursery"] == .building)
        #expect(harness.service.lastTrigger == nil)
        harness.hear("nursery", rms: 0.3, atMs: 1_600)
        #expect(harness.service.phases["nursery"] == .triggered)
        #expect(harness.service.lastTrigger?.cameraId == "nursery")
        #expect(harness.service.lastTrigger?.name == "Nursery")
    }

    @Test func theMeterReadsTheLoudestKnownRoom() async throws {
        let harness = try await Harness()
        harness.service.arm()
        #expect(harness.service.peakLevel == nil, "unknown, never 0")
        harness.hear("nursery", rms: 0.05)
        harness.hear("twins", rms: 0.2)
        #expect(harness.service.peakLevel == 0.2)
    }

    // MARK: - Listen mode

    @Test func listenModePlaysEveryAudibleRoom() async throws {
        let harness = try await Harness { $0.soundMode = .allAloud }
        harness.service.arm()
        await harness.settle()
        #expect(harness.service.listeningCameraIds.isEmpty, "nothing decoded yet")

        harness.hear("nursery", rms: 0.01)
        #expect(await eventually { harness.service.listeningCameraIds == ["nursery"] })
        #expect(harness.speaker.aloudCameraIds == ["nursery"])
    }

    @Test func theViewerPlayingStandsListenModeDown() async throws {
        let harness = try await Harness { $0.soundMode = .allAloud }
        harness.service.arm()
        await harness.settle()
        harness.hear("nursery", rms: 0.01)
        harness.hear("twins", rms: 0.01)
        #expect(await eventually { harness.service.listeningCameraIds == ["nursery", "twins"] })

        harness.service.setViewerAloud(["twins"])
        #expect(harness.service.listeningCameraIds.isEmpty)
        #expect(harness.speaker.aloudCameraIds == ["twins"])

        harness.service.setViewerAloud(nil)
        #expect(harness.service.listeningCameraIds == ["nursery", "twins"])
    }

    @Test func listenModeIsOffUnlessAsked() async throws {
        let harness = try await Harness { $0.soundMode = .rotating }
        harness.service.arm()
        harness.hear("nursery", rms: 0.01)
        await harness.settle()
        #expect(harness.service.listeningCameraIds.isEmpty)
        #expect(harness.speaker.aloudCameraIds.isEmpty)
    }

    @Test func everyRoomPausedStandsListenModeDownButKeepsTheSetting() async throws {
        let harness = try await Harness { $0.soundMode = .allAloud }
        harness.service.arm()
        await harness.settle()
        harness.hear("nursery", rms: 0.01)
        #expect(await eventually { !harness.service.listeningCameraIds.isEmpty })
        harness.service.pause("nursery")
        harness.service.pause("twins")
        #expect(harness.service.listeningCameraIds.isEmpty)
        #expect(harness.dependencies.appSettings.settings.soundMode == .allAloud)
    }

    @Test func headphonesUnpluggedDuringListenModeTurnTheSoundOff() async throws {
        let harness = try await Harness { $0.soundMode = .allAloud }
        harness.service.arm()
        await harness.settle()
        harness.hardware.emit(.routeLost)
        #expect(await eventually { harness.dependencies.appSettings.settings.soundMode == .off })
        #expect(harness.service.listeningCameraIds.isEmpty)
    }

    @Test func headphonesUnpluggedLeaveOtherModesAlone() async throws {
        let harness = try await Harness { $0.soundMode = .rotating }
        harness.service.arm()
        await harness.settle()
        harness.hardware.emit(.routeLost)
        await harness.settle()
        #expect(harness.dependencies.appSettings.settings.soundMode == .rotating)
    }

    @Test func aRefusedSpeakerTurnsListenModeOff() async throws {
        let harness = try await Harness { $0.soundMode = .allAloud }
        harness.hardware.refuseActivation = true
        harness.service.arm()
        #expect(harness.service.isRunning, "the detectors need no speaker")
        #expect(await eventually { harness.dependencies.appSettings.settings.soundMode == .off })
    }

    /// A cry heard through listen mode had its alarm withheld; once nobody
    /// can hear it (the volume at zero), it is raised.
    @Test func aTriggeredRoomNoLongerHeardIsEscalated() async throws {
        let harness = try await Harness { $0.soundMode = .allAloud }
        harness.service.arm()
        await harness.settle()
        harness.hear("nursery", rms: 0.3, atMs: 0)
        #expect(await eventually { harness.service.listeningCameraIds == ["nursery"] })
        harness.hear("nursery", rms: 0.3, atMs: 1_600)
        let first = try #require(harness.service.lastTrigger)

        harness.hardware.emit(.outputVolumeChanged(0))
        #expect(await eventually { harness.service.lastTrigger != first })
        #expect(harness.service.lastTrigger?.cameraId == "nursery")
    }
}

import Foundation
import Synchronization
import Testing

@testable import Dozecam

/// A network the test switches by hand.
private final class SwitchedPathSource: NetworkPathSource {
    private let handler = Mutex<(@Sendable (NetworkPathSnapshot) -> Void)?>(nil)

    func start(onUpdate: @escaping @Sendable (NetworkPathSnapshot) -> Void) {
        handler.withLock { $0 = onUpdate }
    }

    func cancel() {}

    func set(online: Bool) {
        let path =
            online
            ? NetworkPathSnapshot(status: .satisfied, interfaces: [.wifi], interfaceNames: ["en0"])
            : NetworkPathSnapshot(status: .unsatisfied, interfaces: [])
        handler.withLock { $0 }?(path)
    }
}

/// The monitor's audio players, by camera id, for the test to make decode.
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
private final class IdleRecorder {
    var values: [Bool] = []
}

/// The viewer's model with fake players, a manual clock and isolated stores,
/// shown and hidden the way `MonitorView` does it.
@MainActor
private final class Harness {
    let scheduler = ManualScheduler()
    let factory = PlayerFactory()
    let network = SwitchedPathSource()
    let hardware = FakeSpeakerHardware()
    let speaker: Speaker
    let dependencies: AppDependencies
    let monitoring: MonitoringService
    let audio = AudioPlayers()
    let model: MonitorModel
    private let idleRecorder = IdleRecorder()
    var idleTimer: [Bool] { idleRecorder.values }
    private var observing: Task<Void, Never>?

    static let cameras = ["nursery", "twins", "playroom"].map {
        Camera(id: $0, name: $0.capitalized, url: "rtsp://cam/\($0)")
    }

    init(cameras: [Camera] = Harness.cameras, settings: @escaping @Sendable (inout AppSettings) -> Void = { _ in })
        async throws
    {
        dependencies = AppDependencies.isolated(networkSource: network)
        for camera in cameras { try await dependencies.cameras.upsert(camera) }
        await dependencies.appSettings.update { current in
            var next = current
            settings(&next)
            return next
        }
        let factory = factory
        let idleRecorder = idleRecorder
        speaker = Speaker(hardware: hardware, mix: SpeakerMix())
        monitoring = MonitoringService(
            dependencies: dependencies, speaker: speaker, makePlayer: { [audio] id, _ in audio.make(id) },
            scheduler: scheduler)
        model = MonitorModel(
            dependencies: dependencies, monitoring: monitoring, makePlayer: { factory.make($0) }, scheduler: scheduler,
            setIdleTimerDisabled: { idleRecorder.values.append($0) })
    }

    /// The viewer comes on screen with `visible` tiles in view.
    func show(visible: [String] = Harness.cameras.map(\.id)) async {
        observing = Task { await model.observe() }
        _ = await eventually { self.model.isOnScreen }
        for id in visible { model.tileVisibilityChanged(id, visible: true) }
    }

    func hide() async {
        observing?.cancel()
        _ = await eventually { !self.model.isOnScreen }
    }

    func player(_ id: String) -> RecordingPlayer? { factory.player(for: "rtsp://cam/\(id)") }

    /// The cameras the viewer asks the speaker for.
    func requested() -> Set<String> { model.requestedAudibleIds }

    /// Headphones pulled out, once somebody is listening for it.
    func unplug() async {
        _ = await eventually { self.speaker.isObservedForLosses }
        hardware.emit(.routeLost)
    }
}

@MainActor
struct MonitorModelTests {
    // MARK: - Sessions and lifecycle

    @Test func onlyTilesOnScreenPlay() async throws {
        let harness = try await Harness()
        await harness.show(visible: ["nursery", "twins"])
        #expect(Set(harness.model.sessions.sessions.keys) == ["nursery", "twins"])
        #expect(harness.player("playroom") == nil)

        harness.model.tileVisibilityChanged("twins", visible: false)
        harness.model.tileVisibilityChanged("playroom", visible: true)
        #expect(Set(harness.model.sessions.sessions.keys) == ["nursery", "playroom"])
        #expect(harness.player("twins")?.released == true)
        await harness.hide()
    }

    @Test func goingToTheBackgroundTearsEveryPlayerDownAndComingBackResumes() async throws {
        let harness = try await Harness()
        await harness.show()
        let before = try #require(harness.player("nursery"))
        before.emit(.playing)

        harness.model.sceneChanged(inForeground: false)
        #expect(harness.model.sessions.sessions.isEmpty)
        #expect(harness.factory.built.allSatisfy { $0.released })

        harness.model.sceneChanged(inForeground: true)
        let after = try #require(harness.player("nursery"))
        #expect(after !== before)
        #expect(harness.model.session(for: "nursery")?.connection == .connecting)
        #expect(harness.model.sessions.sessions.count == 3)
        await harness.hide()
    }

    @Test func theViewerLeavingTheScreenReleasesEverySession() async throws {
        let harness = try await Harness()
        await harness.show()
        #expect(harness.model.sessions.sessions.count == 3)
        await harness.hide()
        #expect(harness.model.sessions.sessions.isEmpty)
        #expect(harness.factory.built.allSatisfy { $0.released })
    }

    @Test func tilesFollowTheNetwork() async throws {
        let harness = try await Harness()
        await harness.show()
        let player = try #require(harness.player("nursery"))
        player.emit(.playing)

        harness.network.set(online: false)
        #expect(await eventually { harness.model.reach == .offline })
        #expect(harness.model.session(for: "nursery")?.connection == .offline)

        harness.network.set(online: true)
        #expect(await eventually { harness.model.reach == .local })
        #expect(harness.model.session(for: "nursery")?.connection == .reconnecting(attempt: 1))
        #expect(player.plays.count == 2)
        await harness.hide()
    }

    @Test func aStalledTileReconnectsAndNeverClaimsLive() async throws {
        let harness = try await Harness()
        await harness.show()
        let player = try #require(harness.player("twins"))
        player.emit(.playing)
        #expect(harness.model.session(for: "twins")?.connection == .live)

        harness.scheduler.advance(by: 2_501)
        #expect(harness.model.session(for: "twins")?.connection == .reconnecting(attempt: 1))
        harness.scheduler.advance(by: 500)
        #expect(player.plays.count == 2)
        player.emit(.playing)
        #expect(harness.model.session(for: "twins")?.connection == .live)
        await harness.hide()
    }

    // MARK: - Fullscreen

    @Test func openingACameraKeepsTheGridWarmBehindIt() async throws {
        let harness = try await Harness()
        await harness.show()
        let twins = try #require(harness.player("twins"))

        harness.model.open("nursery")
        #expect(harness.model.fullscreenCamera?.id == "nursery")
        #expect(harness.player("nursery")?.videoEnabled == true)
        #expect(!twins.videoEnabled)
        #expect(!twins.released)

        harness.model.closeFullscreen()
        #expect(harness.model.fullscreenId == nil)
        #expect(twins.videoEnabled)
        #expect(harness.factory.players(for: "rtsp://cam/twins").count == 1)
        await harness.hide()
    }

    @Test func swappingTheCameraOnScreenKeepsTheOneLeftWarm() async throws {
        let harness = try await Harness()
        await harness.show()
        harness.model.open("nursery")
        harness.model.open("twins")
        let nursery = try #require(harness.player("nursery"))
        #expect(!nursery.released)
        #expect(!nursery.videoEnabled)
        await harness.hide()
    }

    @Test func aMinuteUntouchedReturnsToTheGrid() async throws {
        let harness = try await Harness()
        await harness.show()
        harness.model.open("nursery")
        harness.scheduler.advance(by: 50_000)
        harness.model.userInteracted()
        harness.scheduler.advance(by: 59_000)
        #expect(harness.model.fullscreenId == "nursery")
        harness.scheduler.advance(by: 1_000)
        #expect(harness.model.fullscreenId == nil)
        await harness.hide()
    }

    @Test func timeInTheBackgroundDoesNotCountAgainstTheCamera() async throws {
        let harness = try await Harness()
        await harness.show()
        harness.model.open("nursery")
        harness.scheduler.advance(by: 50_000)
        harness.model.sceneChanged(inForeground: false)
        harness.scheduler.advance(by: 600_000)
        harness.model.sceneChanged(inForeground: true)
        #expect(harness.model.fullscreenId == "nursery")
        #expect(harness.model.countdown?.remainingSeconds == 60)
        await harness.hide()
    }

    @Test func aCameraSwitchedOffWhileOpenReturnsToTheGrid() async throws {
        let harness = try await Harness()
        await harness.show()
        harness.model.open("twins")
        try await harness.dependencies.cameras.setEnabled(id: "twins", false)
        #expect(await eventually { harness.model.fullscreenId == nil })
        #expect(harness.model.cameras.map(\.id) == ["nursery", "playroom"])
        await harness.hide()
    }

    @Test func openingEachCameraStartsWhole() async throws {
        let harness = try await Harness()
        await harness.show()
        harness.model.open("nursery")
        harness.model.zoom.viewportChanged(CGSize(width: 400, height: 800))
        harness.model.zoom.transform(centroid: CGPoint(x: 200, y: 400), pan: .zero, zoom: 3)
        #expect(harness.model.zoom.scale == 3)
        harness.model.open("twins")
        #expect(harness.model.zoom.scale == 1)
        await harness.hide()
    }

    // MARK: - Pause

    @Test func aPausedCameraHasNoSessionAndKeepsItsPlace() async throws {
        let harness = try await Harness()
        await harness.show()
        harness.model.open("twins")
        harness.model.pause("twins")
        #expect(harness.model.fullscreenId == nil)
        #expect(harness.model.session(for: "twins") == nil)
        #expect(harness.player("twins")?.released == true)
        #expect(harness.model.cameras.map(\.id) == ["nursery", "twins", "playroom"])
        #expect(harness.model.announcement?.text.hasPrefix("Twins paused") == true)

        harness.model.resume("twins")
        #expect(harness.model.session(for: "twins") != nil)
        await harness.hide()
    }

    @Test func exitClearsPauses() async throws {
        let harness = try await Harness()
        await harness.show()
        harness.model.pause("twins")
        let exits = IdleRecorder()
        let monitoring = harness.monitoring
        harness.model.exitHandler = {
            exits.values.append(true)
            monitoring.exit()
        }
        harness.model.requestExit()
        #expect(harness.model.isConfirmingExit)
        harness.model.confirmExit()
        #expect(harness.model.pausedIds.isEmpty)
        #expect(exits.values == [true])
        await harness.hide()
    }

    // MARK: - Viewer audio

    @Test func soundOffKeepsEveryTileSilent() async throws {
        let harness = try await Harness()
        await harness.show()
        #expect(harness.requested().isEmpty)
        #expect(harness.requested().isEmpty)
        await harness.hide()
    }

    @Test func rotatingPlaysOneTileAtATimeInGridOrder() async throws {
        let harness = try await Harness { $0.soundMode = .rotating }
        await harness.show()
        #expect(harness.requested() == ["nursery"])
        #expect(harness.requested() == ["nursery"])
        harness.scheduler.advance(by: 10_000)
        #expect(harness.requested() == ["twins"])
        harness.scheduler.advance(by: 10_000)
        #expect(harness.requested() == ["playroom"])
        harness.scheduler.advance(by: 10_000)
        #expect(harness.requested() == ["nursery"])
        await harness.hide()
    }

    @Test func rotationSkipsPausedAndOffScreenCameras() async throws {
        let harness = try await Harness { $0.soundMode = .rotating }
        await harness.show(visible: ["nursery", "twins"])
        harness.model.pause("nursery")
        #expect(harness.requested() == ["twins"])
        harness.scheduler.advance(by: 10_000)
        #expect(harness.requested() == ["twins"])
        await harness.hide()
    }

    @Test func allAloudPlaysEveryTileOnScreen() async throws {
        let harness = try await Harness { $0.soundMode = .allAloud }
        await harness.show(visible: ["nursery", "twins"])
        #expect(harness.requested() == ["nursery", "twins"])
        #expect(harness.requested() == ["nursery", "twins"])
        await harness.hide()
    }

    @Test func aSingleCameraKeepsTheSoundInEitherMode() async throws {
        for mode in [SoundMode.rotating, .allAloud] {
            let harness = try await Harness { $0.soundMode = mode }
            await harness.show()
            harness.model.open("playroom")
            #expect(harness.requested() == ["playroom"], "\(mode)")
            harness.scheduler.advance(by: 30_000)
            #expect(harness.requested() == ["playroom"], "\(mode)")
            await harness.hide()
        }
    }

    @Test func nothingIsAudibleInTheBackground() async throws {
        let harness = try await Harness { $0.soundMode = .allAloud }
        await harness.show()
        harness.model.sceneChanged(inForeground: false)
        #expect(harness.requested().isEmpty)
        await harness.hide()
    }

    @Test func theSoundButtonStepsThroughTheModesAndPersists() async throws {
        let harness = try await Harness()
        await harness.show()
        harness.model.cycleSoundMode()
        #expect(harness.model.settings.soundMode == .rotating)
        #expect(harness.requested() == ["nursery"])
        await harness.model.flush()
        #expect(harness.dependencies.appSettings.settings.soundMode == .rotating)

        harness.model.cycleSoundMode()
        #expect(harness.requested() == ["nursery", "twins", "playroom"])
        harness.model.cycleSoundMode()
        #expect(harness.requested().isEmpty)
        await harness.model.flush()
        #expect(harness.dependencies.appSettings.settings.soundMode == .off)
        #expect(harness.model.announcement?.text == "Sound off")
        await harness.hide()
    }

    /// The badge and border say what the speaker plays, never just the ask:
    /// a room whose sound is not coming through is not marked.
    @Test func onlyRoomsActuallyPlayingAreMarkedAudible() async throws {
        let harness = try await Harness { $0.soundMode = .allAloud }
        await harness.show()
        #expect(harness.requested() == ["nursery", "twins", "playroom"])
        #expect(harness.model.audibleIds.isEmpty)

        harness.audio.players["nursery"]?.emit(.levels([LevelSample(rms: 0.01, atMs: harness.scheduler.nowMs)]))
        #expect(await eventually { harness.model.audibleIds == ["nursery"] })
        await harness.hide()
    }

    @Test func unpluggingHeadphonesTurnsTheSoundOffForGood() async throws {
        let harness = try await Harness { $0.soundMode = .allAloud }
        await harness.show()
        await harness.unplug()
        #expect(await eventually { harness.model.settings.soundMode == .off })
        #expect(harness.requested().isEmpty)
        #expect(harness.model.announcement?.text == "Sound off: the headphones were disconnected")
        await harness.model.flush()
        #expect(harness.dependencies.appSettings.settings.soundMode == .off)
        await harness.hide()
    }

    @Test(arguments: [false, true])
    func unpluggingLeavesTheSettingAloneWhenTheViewerHoldsNoSpeaker(allPaused: Bool) async throws {
        let harness = try await Harness { $0.soundMode = .rotating }
        await harness.show()
        if allPaused {
            for id in Harness.cameras.map(\.id) { harness.model.pause(id) }
        } else {
            harness.model.sceneChanged(inForeground: false)
        }
        await harness.unplug()
        for _ in 0..<100 { await Task.yield() }
        await harness.model.flush()
        #expect(harness.model.settings.soundMode == .rotating)
        #expect(harness.dependencies.appSettings.settings.soundMode == .rotating)
        await harness.hide()
    }

    // MARK: - Alerts and keep awake

    @Test func theAlertsButtonIsTheStoredSetting() async throws {
        let harness = try await Harness()
        await harness.show()
        harness.model.toggleAlerts()
        await harness.model.flush()
        #expect(harness.dependencies.appSettings.settings.alertsEnabled == false)
        harness.model.toggleAlerts()
        await harness.model.flush()
        #expect(harness.dependencies.appSettings.settings.alertsEnabled == true)
        await harness.hide()
    }

    @Test(arguments: [
        (true, 2, true, true),
        (false, 2, true, false),
        (true, 0, true, false),
        (true, 2, false, false),
    ])
    func keepAwakeMapping(keepScreenOn: Bool, watched: Int, showing: Bool, awake: Bool) {
        #expect(
            MonitorModel.keepsScreenAwake(keepScreenOn: keepScreenOn, watchedCameras: watched, viewerShowing: showing)
                == awake)
    }

    @Test func theScreenIsHeldAwakeOnlyWhileCamerasAreWatched() async throws {
        let harness = try await Harness()
        await harness.show()
        #expect(harness.idleTimer.last == true)

        for camera in Harness.cameras { harness.model.pause(camera.id) }
        #expect(harness.idleTimer.last == false)
        harness.model.resume("nursery")
        #expect(harness.idleTimer.last == true)

        harness.model.toggleKeepScreenOn()
        #expect(harness.idleTimer.last == false)
        await harness.model.flush()
        #expect(harness.dependencies.appSettings.settings.keepScreenOn == false)
        harness.model.toggleKeepScreenOn()
        #expect(harness.idleTimer.last == true)

        harness.model.sceneChanged(inForeground: false)
        #expect(harness.idleTimer.last == false)
        harness.model.sceneChanged(inForeground: true)
        #expect(harness.idleTimer.last == true)

        await harness.hide()
        #expect(harness.idleTimer.last == false)
    }

    // MARK: - Screen

    @Test func theNightThemeIsTheDimRedPalette() async throws {
        let harness = try await Harness { $0.nightTheme = true }
        #expect(harness.model.palette == .night)
        let standard = try await Harness()
        #expect(standard.model.palette == .standard)
    }

    @Test func switchedOffCamerasOnlyIsSaidSo() async throws {
        let harness = try await Harness(cameras: [
            Camera(id: "a", name: "A", url: "rtsp://cam/a", enabled: false)
        ])
        #expect(harness.model.cameras.isEmpty)
        #expect(harness.model.hasDisabledOnly)
    }

    @Test func aConfirmationGoesAwayOnItsOwn() async throws {
        let harness = try await Harness()
        await harness.show()
        harness.model.toggleAlerts()
        #expect(harness.model.announcement != nil)
        harness.scheduler.advance(by: MonitorModel.announcementMs)
        #expect(harness.model.announcement == nil)
        await harness.hide()
    }
}

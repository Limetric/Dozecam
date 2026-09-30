import AVFAudio
import Testing

@testable import Dozecam

/// A session and engine the test drives by hand.
@MainActor
final class FakeSpeakerHardware: SpeakerHardware {
    struct Refused: Error {}

    var onEvent: ((SpeakerHardwareEvent) -> Void)?
    var outputVolume: Float = 0.5
    var refuseActivation = false
    var refuseEngine = false
    private(set) var active = false
    private(set) var engineRunning = false
    var isEngineRunning: Bool { engineRunning }
    var isSessionOurs = true
    private(set) var activations = 0
    private(set) var engineStarts = 0

    func activate() throws {
        if refuseActivation { throw Refused() }
        activations += 1
        active = true
        isSessionOurs = true
    }

    func deactivate() { active = false }

    func startEngine() throws {
        if refuseEngine { throw Refused() }
        engineStarts += 1
        engineRunning = true
    }

    func stopEngine() { engineRunning = false }

    /// What libVLC's own audio output does behind the speaker's back.
    func takenOver() {
        isSessionOurs = false
        engineRunning = false
    }

    func emit(_ event: SpeakerHardwareEvent) {
        switch event {
        case .interruptionBegan, .engineStopped, .mediaServicesReset: engineRunning = false
        default: break
        }
        onEvent?(event)
    }
}

@MainActor
struct SpeakerTests {
    let hardware = FakeSpeakerHardware()
    let speaker: Speaker

    init() {
        speaker = Speaker(hardware: hardware, mix: SpeakerMix())
    }

    /// Collects the events the speaker sends from here on. Its stream ends
    /// with the speaker.
    @MainActor
    final class EventLog {
        private(set) var events: [Speaker.Event] = []

        init(_ speaker: Speaker) {
            let stream = speaker.updates()
            Task { [weak self] in
                for await event in stream { self?.events.append(event) }
            }
        }

        func settle() async {
            for _ in 0..<5 { await Task.yield() }
        }
    }

    /// Writes `level` as a constant to `cameraId`'s sink.
    func write(_ level: Float, frames: Int = 256, to cameraId: String) {
        let samples = [Float](repeating: level, count: frames)
        samples.withUnsafeBufferPointer { speaker.sink(for: cameraId).write($0) }
    }

    func render(frames: Int = 256) -> [Float] {
        var out = [Float](repeating: 9, count: frames)
        out.withUnsafeMutableBufferPointer { speaker.mix.render(into: $0.baseAddress!, frames: frames) }
        return out
    }

    // MARK: - Holding

    @Test func startingActivatesTheSessionAndRunsTheEngine() {
        #expect(speaker.start())
        #expect(speaker.status == .running)
        #expect(speaker.isGranted)
        #expect(hardware.active && hardware.engineRunning)
    }

    @Test func aRefusedActivationIsReportedAsLost() async {
        let log = EventLog(speaker)
        hardware.refuseActivation = true

        #expect(!speaker.start())
        await log.settle()

        #expect(speaker.status == .failed(.refused))
        #expect(!speaker.isGranted)
        #expect(log.events == [.lost(.refused)])
    }

    @Test func startingAgainRetriesAfterARefusal() {
        hardware.refuseActivation = true
        speaker.start()
        hardware.refuseActivation = false

        #expect(speaker.start())
        #expect(speaker.status == .running)
    }

    @Test func stoppingReleasesTheSessionAndSilencesTheMix() {
        speaker.start()
        speaker.setAloud(["nursery"])

        speaker.stop()

        #expect(speaker.status == .stopped)
        #expect(!hardware.active && !hardware.engineRunning)
        #expect(speaker.aloudCameraIds.isEmpty)
        #expect(!speaker.sink(for: "nursery").isAloud)
    }

    // MARK: - Aloud

    @Test func theEngineRendersSilenceWhenNothingIsAloud() {
        speaker.start()
        write(0.3, to: "nursery")
        #expect(render().allSatisfy { $0 == 0 })
    }

    @Test func onlyAloudRoomsAreMixedOut() {
        speaker.start()
        speaker.setAloud(["nursery"])
        write(0.3, to: "nursery")
        write(0.2, to: "porch")

        #expect(render().allSatisfy { abs($0 - 0.3) < 0.000_1 })
        #expect(speaker.aloudCameraIds == ["nursery"])
    }

    @Test func roomsThatAreNotAloudAreDrainedSoTheyBuildNoDelay() {
        speaker.start()
        speaker.setAloud(["nursery"])
        write(0.2, to: "porch")
        _ = render()

        #expect(speaker.sink(for: "porch").queued == 0)
    }

    /// Turned up, a room starts from now rather than from what queued while
    /// it was silent.
    @Test func aRoomTurnedAloudStartsFromNow() {
        speaker.start()
        write(0.2, to: "porch")
        speaker.setAloud(["porch"])

        #expect(render().allSatisfy { $0 == 0 })
    }

    @Test func severalAloudRoomsAreAddedAndClippedToFullScale() {
        speaker.start()
        speaker.setAloud(["nursery", "porch"])
        write(0.3, to: "nursery")
        write(0.2, to: "porch")
        #expect(render().allSatisfy { abs($0 - 0.5) < 0.000_1 })

        write(0.8, to: "nursery")
        write(0.8, to: "porch")
        #expect(render().allSatisfy { $0 == 1 })
    }

    @Test func aFullSinkDropsItsOldestSamples() {
        let sink = SpeakerSink(cameraId: "nursery", capacity: 4)
        [Float](arrayLiteral: 1, 2, 3).withUnsafeBufferPointer { sink.write($0) }
        [Float](arrayLiteral: 4, 5, 6).withUnsafeBufferPointer { sink.write($0) }
        #expect(sink.queued == 4)
        var out = [Float](repeating: 0, count: 5)
        out.withUnsafeMutableBufferPointer { sink.mix(into: $0.baseAddress!, frames: 5) }
        #expect(out == [3, 4, 5, 6, 0])
        #expect(sink.queued == 0)
    }

    @Test func aloudIsHeldBackUntilTheSpeakerIsGranted() {
        speaker.setAloud(["nursery"])
        #expect(speaker.aloudCameraIds.isEmpty)

        speaker.start()
        #expect(speaker.aloudCameraIds == ["nursery"])
    }

    // MARK: - Interruptions

    @Test func anInterruptionSilencesAndItsEndBringsTheSameRoomsBack() async {
        speaker.start()
        speaker.setAloud(["nursery"])
        let log = EventLog(speaker)
        let startsBefore = hardware.engineStarts

        hardware.emit(.interruptionBegan)
        #expect(speaker.status == .interrupted)
        #expect(speaker.aloudCameraIds.isEmpty)
        #expect(!speaker.sink(for: "nursery").isAloud)

        hardware.emit(.interruptionEnded(shouldResume: true))
        await log.settle()

        #expect(speaker.status == .running)
        #expect(hardware.engineStarts == startsBefore + 1)
        #expect(speaker.aloudCameraIds == ["nursery"])
        #expect(log.events == [.interrupted, .resumed])
    }

    /// Monitoring lives only while the engine runs, so the system's hint not
    /// to resume is not obeyed.
    @Test func anInterruptionEndResumesEvenWithoutTheHint() {
        speaker.start()
        hardware.emit(.interruptionBegan)
        hardware.emit(.interruptionEnded(shouldResume: false))
        #expect(speaker.status == .running)
        #expect(hardware.engineRunning)
    }

    /// A comeback that fails leaves nothing keeping the app alive: a failure
    /// for #68 to announce.
    @Test func aResumeThatFailsIsReportedAsLost() async {
        speaker.start()
        speaker.setAloud(["nursery"])
        let log = EventLog(speaker)

        hardware.emit(.interruptionBegan)
        hardware.refuseActivation = true
        hardware.emit(.interruptionEnded(shouldResume: true))
        await log.settle()

        #expect(speaker.status == .failed(.resumeFailed))
        #expect(speaker.aloudCameraIds.isEmpty)
        #expect(log.events == [.interrupted, .lost(.resumeFailed)])

        hardware.refuseActivation = false
        #expect(speaker.start())
    }

    @Test func settingAloudDuringAnInterruptionTakesEffectOnResume() {
        speaker.start()
        hardware.emit(.interruptionBegan)
        speaker.setAloud(["porch"])
        #expect(speaker.aloudCameraIds.isEmpty)

        hardware.emit(.interruptionEnded(shouldResume: true))
        #expect(speaker.aloudCameraIds == ["porch"])
    }

    @Test func anInterruptionWhileStoppedIsIgnored() {
        hardware.emit(.interruptionBegan)
        hardware.emit(.interruptionEnded(shouldResume: true))
        #expect(speaker.status == .stopped)
        #expect(!hardware.active)
    }

    // MARK: - Route and engine

    @Test func losingTheOutputDeviceSilencesAtOnceAndIsALossForGood() async {
        speaker.start()
        speaker.setAloud(["nursery"])
        let log = EventLog(speaker)

        hardware.emit(.routeLost)
        await log.settle()

        #expect(speaker.aloudCameraIds.isEmpty)
        #expect(!speaker.sink(for: "nursery").isAloud)
        #expect(log.events == [.lost(.routeLost)])
        // Monitoring still needs the engine.
        #expect(speaker.status == .running)
        #expect(hardware.engineRunning)
    }

    @Test func routeLossesReachTheViewersLossStream() async {
        speaker.start()
        let losses = speaker.losses()
        hardware.emit(.routeLost)
        var iterator = losses.makeAsyncIterator()
        #expect(await iterator.next() != nil)
    }

    @Test func anEngineStoppedByAConfigurationChangeIsStartedAgain() {
        speaker.start()
        hardware.emit(.engineStopped)
        #expect(hardware.engineRunning)
        #expect(speaker.status == .running)
    }

    @Test func anEngineThatWillNotRestartIsReportedAsLost() {
        speaker.start()
        hardware.refuseEngine = true
        hardware.emit(.engineStopped)
        #expect(speaker.status == .failed(.resumeFailed))
    }

    @Test func aMediaServicesResetSetsEverythingUpAgain() {
        speaker.start()
        let activations = hardware.activations
        hardware.emit(.mediaServicesReset)
        #expect(hardware.activations == activations + 1)
        #expect(hardware.engineRunning)
        #expect(speaker.status == .running)
    }

    @Test func anotherCategoryIsPutBack() {
        speaker.start()
        let activations = hardware.activations
        hardware.emit(.categoryChanged)
        #expect(hardware.activations == activations + 1)
    }

    @Test func reassertingRepairsAnEngineStoppedBehindItsBack() {
        speaker.start()
        let activations = hardware.activations
        hardware.takenOver()

        speaker.reassert()

        #expect(hardware.engineRunning && hardware.isSessionOurs)
        #expect(hardware.activations == activations + 1)
        #expect(speaker.status == .running)
    }

    @Test func reassertingAHealthySpeakerTouchesNothing() {
        speaker.start()
        let activations = hardware.activations
        speaker.reassert()
        #expect(hardware.activations == activations)
    }

    @Test func reassertingRetriesAFailedSpeaker() {
        hardware.refuseActivation = true
        speaker.start()
        hardware.refuseActivation = false

        speaker.reassert()
        #expect(speaker.status == .running)
    }

    @Test func reassertingWhileStoppedStaysStopped() {
        speaker.reassert()
        #expect(speaker.status == .stopped)
        #expect(!hardware.active)
    }

    // MARK: - Volume

    @Test func theMediaVolumeIsPublishedAndZeroIsSilenced() async {
        speaker.start()
        let log = EventLog(speaker)
        #expect(speaker.outputVolume == 0.5)
        #expect(!speaker.isMediaSilenced)

        hardware.emit(.outputVolumeChanged(0))
        await log.settle()

        #expect(speaker.outputVolume == 0)
        #expect(speaker.isMediaSilenced)
        #expect(log.events == [.outputVolumeChanged(0)])
    }

    @Test func theVolumeIsReadAgainOnActivation() {
        hardware.outputVolume = 0.25
        speaker.start()
        #expect(speaker.outputVolume == 0.25)
    }

    // MARK: - The system's notifications

    @Test func interruptionNotificationsAreRead() {
        let began = [AVAudioSessionInterruptionTypeKey: AVAudioSession.InterruptionType.began.rawValue]
        #expect(SystemSpeakerHardware.interruptionEvent(began) == .interruptionBegan)
        let ended: [AnyHashable: Any] = [
            AVAudioSessionInterruptionTypeKey: AVAudioSession.InterruptionType.ended.rawValue,
            AVAudioSessionInterruptionOptionKey: AVAudioSession.InterruptionOptions.shouldResume.rawValue,
        ]
        #expect(SystemSpeakerHardware.interruptionEvent(ended) == .interruptionEnded(shouldResume: true))
        let endedPlain = [AVAudioSessionInterruptionTypeKey: AVAudioSession.InterruptionType.ended.rawValue]
        #expect(SystemSpeakerHardware.interruptionEvent(endedPlain) == .interruptionEnded(shouldResume: false))
        #expect(SystemSpeakerHardware.interruptionEvent(nil) == nil)
    }

    @Test func onlyTheOldDeviceGoingAwayIsARouteLoss() {
        let session = AVAudioSession.sharedInstance()
        let reason = { (r: AVAudioSession.RouteChangeReason) in
            [AVAudioSessionRouteChangeReasonKey: r.rawValue] as [AnyHashable: Any]
        }
        #expect(SystemSpeakerHardware.routeChangeEvent(reason(.oldDeviceUnavailable), session: session) == .routeLost)
        #expect(SystemSpeakerHardware.routeChangeEvent(reason(.newDeviceAvailable), session: session) == nil)
        #expect(SystemSpeakerHardware.routeChangeEvent(reason(.override), session: session) == nil)
        #expect(SystemSpeakerHardware.routeChangeEvent(nil, session: session) == nil)
    }
}

/// The real session and engine, on the simulator: the render block runs on
/// the audio I/O thread without trapping (the #58 isolation crash), and it
/// drains every room.
@MainActor
struct SystemSpeakerTests {
    @Test func theRealEngineRendersTheMix() async throws {
        let mix = SpeakerMix()
        let speaker = Speaker(hardware: SystemSpeakerHardware(mix: mix), mix: mix)
        defer { speaker.stop() }
        #expect(speaker.start())
        #expect(speaker.status == .running)

        let sink = speaker.sink(for: "nursery")
        let second = [Float](repeating: 0, count: 24_000)
        second.withUnsafeBufferPointer { sink.write($0) }
        #expect(sink.queued == 24_000)

        let deadline = ContinuousClock.now + .seconds(5)
        while ContinuousClock.now < deadline, sink.queued > 0 {
            try await Task.sleep(for: .milliseconds(50))
        }
        #expect(sink.queued == 0, "the render thread drains a room that is not aloud")
    }
}

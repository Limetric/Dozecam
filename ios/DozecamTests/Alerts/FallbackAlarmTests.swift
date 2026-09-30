import Foundation
import Testing

@testable import Dozecam

/// The fallback alarm through the speaker's mix: the tone, its gain, and the
/// ramp a schedule drives through `AlarmTonePlayer`.
@MainActor
struct FallbackAlarmTests {
    let hardware = FakeSpeakerHardware()
    let speaker: Speaker

    init() {
        speaker = Speaker(hardware: hardware, mix: SpeakerMix())
    }

    /// A tone that is full scale throughout, so the output is the gain.
    static func flat(seconds: Double = 1) -> AlarmToneBuffer {
        AlarmToneBuffer(samples: [Float](repeating: 1, count: Int(seconds * SpeakerMix.sampleRate)))
    }

    func render(frames: Int = 256) -> [Float] {
        var out = [Float](repeating: 9, count: frames)
        out.withUnsafeMutableBufferPointer { speaker.mix.render(into: $0.baseAddress!, frames: frames) }
        return out
    }

    func close(_ a: Float, _ b: Float) -> Bool { abs(a - b) < 0.000_1 }

    // MARK: - The voice in the mix

    @Test func aBurstPlaysAtItsGain() {
        speaker.start()
        #expect(speaker.playAlarm(Self.flat(), gain: 0.15))
        #expect(render().allSatisfy { close($0, 0.15) })
        #expect(speaker.isAlarmPlaying)
    }

    @Test func aGainChangeGlidesAcrossOneBufferThenHolds() {
        speaker.start()
        speaker.playAlarm(Self.flat(), gain: 0.2)
        _ = render()

        speaker.setAlarmGain(0.6)
        let glide = render(frames: 100)
        #expect(glide.first! > 0.2 && glide.first! < 0.21)
        #expect(close(glide.last!, 0.6))
        #expect(zip(glide, glide.dropFirst()).allSatisfy { $0 <= $1 })
        #expect(render().allSatisfy { close($0, 0.6) })
    }

    @Test func aBurstEndsWithItsTone() {
        speaker.start()
        speaker.playAlarm(AlarmToneBuffer(samples: [Float](repeating: 1, count: 300)), gain: 1)

        let first = render(frames: 256)
        let second = render(frames: 256)

        #expect(first.allSatisfy { close($0, 1) })
        #expect(second[0..<44].allSatisfy { close($0, 1) })
        #expect(second[44...].allSatisfy { $0 == 0 })
        #expect(!speaker.isAlarmPlaying)
    }

    @Test func aNewBurstStartsTheToneFromTheTop() {
        speaker.start()
        let tone = AlarmToneBuffer(samples: (0..<1_000).map { Float($0) / 1_000 })
        speaker.playAlarm(tone, gain: 1)
        _ = render(frames: 500)

        speaker.playAlarm(tone, gain: 1)
        #expect(close(render(frames: 1)[0], 0))
    }

    @Test func theAlarmPlaysOverTheRoomsAndIsClipped() {
        speaker.start()
        speaker.setAloud(["nursery"])
        let samples = [Float](repeating: 0.7, count: 256)
        samples.withUnsafeBufferPointer { speaker.sink(for: "nursery").write($0) }
        speaker.playAlarm(Self.flat(), gain: 0.5)

        #expect(render().allSatisfy { close($0, 1) })
    }

    @Test func theAlarmIsHeardWithNothingAloud() {
        speaker.start()
        speaker.playAlarm(Self.flat(), gain: 0.4)
        #expect(render().allSatisfy { close($0, 0.4) })
    }

    @Test func stoppingSilencesIt() {
        speaker.start()
        speaker.playAlarm(Self.flat(), gain: 1)
        speaker.stopAlarm()
        #expect(render().allSatisfy { $0 == 0 })
        #expect(!speaker.isAlarmPlaying)
    }

    @Test func gainIsClampedToFullScale() {
        speaker.start()
        speaker.playAlarm(Self.flat(), gain: 3)
        #expect(render().allSatisfy { close($0, 1) })
        speaker.setAlarmGain(-1)
        _ = render()
        #expect(render().allSatisfy { $0 == 0 })
    }

    @Test func aStoppedSpeakerIsStartedForTheAlarm() {
        #expect(speaker.playAlarm(Self.flat(), gain: 1))
        #expect(speaker.status == .running)
        #expect(hardware.engineRunning)
    }

    @Test func aRefusedSpeakerSaysTheAlarmIsNotHeard() {
        hardware.refuseActivation = true
        #expect(!speaker.playAlarm(Self.flat(), gain: 1))
    }

    @Test func anInterruptedSpeakerKeepsTheBurstForWhenItComesBack() {
        speaker.start()
        hardware.emit(.interruptionBegan)

        #expect(!speaker.playAlarm(Self.flat(), gain: 0.3))
        hardware.emit(.interruptionEnded(shouldResume: true))

        #expect(speaker.isGranted)
        #expect(render().allSatisfy { close($0, 0.3) })
    }

    @Test func stoppingTheSpeakerEndsTheAlarm() {
        speaker.start()
        speaker.playAlarm(Self.flat(), gain: 1)
        speaker.stop()
        #expect(!speaker.isAlarmPlaying)
    }

    // MARK: - The bundled tones

    @Test(arguments: AlarmTone.allCases)
    func theBundledTonesLoad(tone: AlarmTone) throws {
        let buffer = try #require(tone.load())
        #expect(buffer.duration > 1 && buffer.duration < 30)
        let samples = UnsafeBufferPointer(start: buffer.samples, count: buffer.count)
        let peak = samples.map(abs).max() ?? 0
        #expect(peak > 0.3 && peak <= 1)
    }

    @Test func aMissingToneLoadsAsNil() {
        #expect(AlarmTone.room.load(from: Bundle(for: FakeSpeakerHardware.self)) == nil)
    }

    // MARK: - The player a schedule drives

    /// Android's ramp: 15 % of the ceiling, climbing to the ceiling over 5 s,
    /// ticked every 250 ms. The schedule is `AlarmSchedule`'s; this plays one
    /// out by hand to show the speaker follows it.
    @Test func thePlayerFollowsARampUpToTheCeiling() {
        speaker.start()
        let player = SpeakerAlarmPlayer(speaker: speaker)
        let ceiling: Float = 0.8
        func volume(atMs elapsed: Int) -> Float {
            ceiling * min(1, 0.15 + 0.85 * Float(elapsed) / 5_000)
        }

        #expect(player.start(.room, volume: volume(atMs: 0)))
        var gains: [Float] = [speaker.mix.alarm.gain]
        for elapsed in stride(from: 250, through: 6_000, by: 250) {
            player.setVolume(volume(atMs: elapsed))
            gains.append(speaker.mix.alarm.gain)
        }

        #expect(close(gains.first!, 0.12))
        #expect(zip(gains, gains.dropFirst()).allSatisfy { $0 <= $1 })
        #expect(close(gains.last!, ceiling))
        #expect(gains.allSatisfy { $0 <= ceiling })
    }

    @Test func thePlayerPlaysTheBundledToneUnderItsVolume() {
        speaker.start()
        let player = SpeakerAlarmPlayer(speaker: speaker)
        player.preload()

        #expect(player.start(.failure, volume: 0.25))
        let out = render(frames: 4_800)

        let peak = out.map(abs).max() ?? 0
        #expect(peak > 0 && peak <= 0.25 + 0.000_1)
        #expect(player.isPlaying)
        player.stop()
        #expect(!player.isPlaying)
    }
}

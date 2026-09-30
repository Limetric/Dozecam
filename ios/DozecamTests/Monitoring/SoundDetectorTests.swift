import Foundation
import Testing

@testable import Dozecam

/// The port of Android's `SoundDetectorTest`: the level timelines live in
/// `shared/fixtures/sound-detector/detector.json`, one case per test, so both
/// detectors are held to the same trigger and re-arm points.
struct SoundDetectorTests {
    private struct Settings: Codable {
        let threshold: Float
        let sustainMs: Int
        let quietMs: Int

        var detectorSettings: DetectorSettings {
            DetectorSettings(threshold: threshold, sustainMs: sustainMs, quietMs: quietMs)
        }
    }

    private struct Sample: Codable {
        let newSettings: Settings?
        let atMs: Int64
        let rms: Float
        let triggers: Bool
        let phase: String?
    }

    private struct Case: Codable {
        let name: String
        let settings: Settings
        let samples: [Sample]
    }

    private struct Table: Codable {
        let cases: [Case]
    }

    private func phase(_ value: String) -> SoundDetector.Phase? {
        switch value {
        case "armed": .armed
        case "building": .building
        case "triggered": .triggered
        default: nil
        }
    }

    private func play(_ name: String) throws {
        let cases = try Fixtures.decode(Table.self, from: "sound-detector/detector.json").cases
            .filter { $0.name == name }
        guard cases.count == 1, let fixture = cases.first else {
            Issue.record("no single fixture case \"\(name)\" in sound-detector/detector.json")
            return
        }
        var detector = SoundDetector(settings: fixture.settings.detectorSettings)
        for sample in fixture.samples {
            if let next = sample.newSettings { detector.updateSettings(next.detectorSettings) }
            let at = "\(fixture.name): rms \(sample.rms) at \(sample.atMs)ms"
            let triggered = detector.onLevel(sample.rms, nowMs: sample.atMs)
            #expect(triggered == sample.triggers, "\(at) triggers")
            if let expected = sample.phase {
                let expectedPhase = try #require(phase(expected), "\(at): unknown phase \"\(expected)\"")
                #expect(detector.phase == expectedPhase, "\(at) phase")
            }
        }
    }

    @Test func sustainedLoudSoundTriggersExactlyOnce() throws {
        try play("sustained loud sound triggers exactly once")
    }

    @Test func aShortThudDoesNotTrigger() throws {
        try play("a short thud does not trigger")
    }

    @Test func quietLevelsBelowThresholdNeverTrigger() throws {
        try play("quiet levels below threshold never trigger")
    }

    @Test func reArmsOnlyAfterTheFullQuietPeriod() throws {
        try play("re-arms only after the full quiet period")
    }

    @Test func loudSoundDuringTheQuietPeriodRestartsTheQuietTimer() throws {
        try play("loud sound during the quiet period restarts the quiet timer")
    }

    @Test func updatedSettingsApplyToSubsequentSamples() throws {
        try play("updated settings apply to subsequent samples")
    }

    // MARK: - iOS specifics

    /// The threshold is inclusive and compared as a Float: 0.1 as a Float is
    /// not 0.1 as a Double, and a level decoded as exactly the threshold must
    /// count as loud.
    @Test func aLevelExactlyAtTheThresholdIsLoud() {
        var detector = SoundDetector(settings: DetectorSettings(threshold: 0.1, sustainMs: 500, quietMs: 2_000))
        let first = detector.onLevel(0.1, nowMs: 0)
        #expect(!first)
        #expect(detector.phase == .building)
        let sustained = detector.onLevel(0.1, nowMs: 500)
        #expect(sustained)
    }
}

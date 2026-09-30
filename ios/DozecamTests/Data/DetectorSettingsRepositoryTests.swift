import Foundation
import Testing

@testable import Dozecam

struct DetectorSettingsRepositoryTests {
    let storage = TestDefaults()

    /// shared/spec/alerts-and-sound-modes.md#the-detector; Android:
    /// `DetectorSettings` in data/DetectorSettingsRepository.kt.
    @Test func defaultsMatchAndroidAndTheSpec() {
        let settings = DetectorSettingsRepository(defaults: storage.defaults).settings
        #expect(settings.threshold == 0.10)
        #expect(settings.sustainMs == 1_500)
        #expect(settings.quietMs == 10_000)
    }

    /// The spec's user ranges; Android's sliders in DetectionSettings.kt.
    @Test func rangesMatchAndroidAndTheSpec() {
        #expect(DetectorSettings.thresholdRange == 0.01...0.5)
        #expect(DetectorSettings.sustainMsRange == 500...5_000)
        #expect(DetectorSettings.quietMsRange == 2_000...30_000)
    }

    @Test func settingsSurviveARelaunchUnderAndroidsKeys() async {
        let repository = DetectorSettingsRepository(defaults: storage.defaults)
        let changed = DetectorSettings(threshold: 0.25, sustainMs: 3_000, quietMs: 20_000)
        await repository.update { _ in changed }
        #expect(repository.settings == changed)
        #expect(DetectorSettingsRepository(defaults: storage.defaults).settings == changed)
        #expect(storage.defaults.float(forKey: "detector_threshold") == 0.25)
        #expect(storage.defaults.integer(forKey: "detector_sustain_ms") == 3_000)
        #expect(storage.defaults.integer(forKey: "detector_quiet_ms") == 20_000)
    }

    @Test func updatesAreClampedToTheirRanges() async {
        let repository = DetectorSettingsRepository(defaults: storage.defaults)
        await repository.update { _ in DetectorSettings(threshold: 0.9, sustainMs: 0, quietMs: 60_000) }
        #expect(repository.settings == DetectorSettings(threshold: 0.5, sustainMs: 500, quietMs: 30_000))
    }

    @Test func settingsUpdatesStartWithTheCurrentValueAndFollowChanges() async {
        let repository = DetectorSettingsRepository(defaults: storage.defaults)
        var updates = repository.settingsUpdates().makeAsyncIterator()
        #expect(await updates.next() == DetectorSettings())
        await repository.update {
            var settings = $0
            settings.threshold = 0.2
            return settings
        }
        #expect(await updates.next()?.threshold == 0.2)
    }
}

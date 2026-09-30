import Foundation
import Testing

@testable import Dozecam

struct AppSettingsRepositoryTests {
    let storage = TestDefaults()

    /// Android: `AppSettings` in data/AppSettingsRepository.kt; spec:
    /// shared/spec/alerts-and-sound-modes.md and failure-alerts.md.
    @Test func defaultsMatchAndroidAndTheSpec() {
        let settings = AppSettingsRepository(defaults: storage.defaults).settings
        #expect(settings.nightTheme == false)
        #expect(settings.alertChime == true)
        #expect(settings.alertVibrate == true)
        #expect(settings.alertSound == nil)  // the phone's own alarm sound
        #expect(settings.alertRamp == true)
        #expect(settings.alertRepeatIntervalMs == 8_000)  // AlarmSchedule.DEFAULT_REPEAT_INTERVAL_MS
        #expect(settings.alertVolume == 1)
        #expect(settings.failureGraceMs == 60_000)
        #expect(settings.orientationLock == .auto)
        #expect(settings.soundMode == .off)
        #expect(settings.alertsEnabled == true)
        #expect(settings.keepScreenOn == true)
        #expect(settings.talkbackVolume == 1)
        #expect(settings == AppSettings())
    }

    /// Android: `AlarmSchedule.MIN/MAX_REPEAT_INTERVAL_MS`,
    /// `FailureLedger.MIN/MAX_GRACE_MS`, the sliders in AlertsSettings.kt and
    /// DisplaySettings.kt.
    @Test func rangesMatchAndroidAndTheSpec() {
        #expect(AppSettings.alertRepeatIntervalMsRange == 3_000...30_000)
        #expect(AppSettings.alertVolumeRange == 0.1...1)
        #expect(AppSettings.failureGraceMsRange == 30_000...300_000)
        #expect(AppSettings.talkbackVolumeRange == 0...1)
    }

    @Test func soundModesAreStoredUnderAndroidsNames() {
        #expect(SoundMode.allCases.map(\.rawValue) == ["OFF", "ROTATING", "ALL_ALOUD"])
        #expect(OrientationLock.allCases.map(\.rawValue) == ["AUTO", "PORTRAIT", "LANDSCAPE"])
    }

    @Test func everySettingSurvivesARelaunch() async {
        let changed = AppSettings(
            nightTheme: true, alertChime: false, alertVibrate: false, alertSound: "tone.caf", alertRamp: false,
            alertRepeatIntervalMs: 12_000, alertVolume: 0.4, failureGraceMs: 120_000, orientationLock: .landscape,
            soundMode: .allAloud, alertsEnabled: false, keepScreenOn: false, talkbackVolume: 0.25)
        let repository = AppSettingsRepository(defaults: storage.defaults)
        await repository.update { _ in changed }
        #expect(repository.settings == changed)
        #expect(AppSettingsRepository(defaults: storage.defaults).settings == changed)
    }

    @Test func storedUnderAndroidsKeys() async {
        let repository = AppSettingsRepository(defaults: storage.defaults)
        await repository.update {
            var settings = $0
            settings.soundMode = .rotating
            settings.failureGraceMs = 90_000
            return settings
        }
        #expect(storage.defaults.string(forKey: "sound_mode") == "ROTATING")
        #expect(storage.defaults.integer(forKey: "failure_grace_ms") == 90_000)
        #expect(storage.defaults.bool(forKey: "alerts_enabled"))
    }

    @Test func clearingTheToneRemovesItRatherThanStoringEmpty() async {
        let repository = AppSettingsRepository(defaults: storage.defaults)
        await repository.update {
            var settings = $0
            settings.alertSound = "tone.caf"
            return settings
        }
        await repository.update {
            var settings = $0
            settings.alertSound = nil
            return settings
        }
        #expect(storage.defaults.object(forKey: "alert_sound") == nil)
        #expect(AppSettingsRepository(defaults: storage.defaults).settings.alertSound == nil)
    }

    @Test func anUnknownModeReadsAsItsDefault() {
        storage.defaults.set("SHOUTING", forKey: "sound_mode")
        storage.defaults.set("SIDEWAYS", forKey: "orientation_lock")
        let settings = AppSettingsRepository(defaults: storage.defaults).settings
        #expect(settings.soundMode == .off)
        #expect(settings.orientationLock == .auto)
    }

    @Test func updatesAreClampedToTheirRanges() async {
        let repository = AppSettingsRepository(defaults: storage.defaults)
        await repository.update {
            var settings = $0
            settings.alertRepeatIntervalMs = 500
            settings.alertVolume = 0
            settings.failureGraceMs = 3_600_000
            settings.talkbackVolume = 2
            return settings
        }
        let settings = repository.settings
        #expect(settings.alertRepeatIntervalMs == 3_000)
        #expect(settings.alertVolume == 0.1)
        #expect(settings.failureGraceMs == 300_000)
        #expect(settings.talkbackVolume == 1)
    }

    @Test func storedValuesOutsideTheRangesAreClampedOnRead() {
        storage.defaults.set(1_000, forKey: "failure_grace_ms")
        #expect(AppSettingsRepository(defaults: storage.defaults).settings.failureGraceMs == 30_000)
    }

    @Test func updatesAreSerialised() async {
        let repository = AppSettingsRepository(defaults: storage.defaults)
        await withTaskGroup(of: Void.self) { group in
            for _ in 0..<20 {
                group.addTask {
                    await repository.update {
                        var settings = $0
                        settings.alertRepeatIntervalMs += 1_000
                        return settings
                    }
                }
            }
        }
        // 8 s + 20 × 1 s, inside the 30 s ceiling: no update lost another.
        #expect(repository.settings.alertRepeatIntervalMs == 28_000)
    }

    @Test func settingsUpdatesStartWithTheCurrentValueAndFollowChanges() async {
        let repository = AppSettingsRepository(defaults: storage.defaults)
        var updates = repository.settingsUpdates().makeAsyncIterator()
        #expect(await updates.next() == AppSettings())
        await repository.update {
            var settings = $0
            settings.alertsEnabled = false
            return settings
        }
        #expect(await updates.next()?.alertsEnabled == false)
    }
}

import Foundation
import Testing

@testable import Dozecam

@MainActor
struct SettingsModelTests {
    private let dependencies = AppDependencies.isolated()

    private func makeModel() -> SettingsModel {
        SettingsModel(dependencies: dependencies, levelSource: StaticLevelSource(), initialSection: nil)
    }

    private let nursery = Camera(id: "manual-1", name: "Nursery", url: "rtsp://127.0.0.1:18554/nursery")
    private let playroom = Camera(id: "manual-2", name: "Playroom", url: "rtsp://127.0.0.1:18554/playroom")

    // MARK: Reading

    @Test func aFreshModelShowsTheStoredDefaults() {
        let model = makeModel()
        #expect(model.settings == AppSettings())
        #expect(model.detector == DetectorSettings())
        #expect(model.cameras.isEmpty)
        #expect(model.value(.threshold) == 10)
        #expect(model.value(.sustain) == 1.5)
        #expect(model.value(.quiet) == 10)
        #expect(model.value(.alertVolume) == 100)
        #expect(model.value(.alertRepeat) == 8)
        #expect(model.value(.failureGrace) == 60)
        #expect(model.value(.talkbackVolume) == 100)
    }

    // MARK: Toggles

    @Test(arguments: ToggleSetting.allCases)
    func aToggleWritesTheStoreAndAFreshModelReadsItBack(_ toggle: ToggleSetting) async {
        let model = makeModel()
        let flipped = !model.isOn(toggle)
        model.set(toggle, flipped)
        #expect(model.isOn(toggle) == flipped)
        await model.flush()
        #expect(toggle.value(in: dependencies.appSettings.settings) == flipped)
        #expect(makeModel().isOn(toggle) == flipped)
    }

    @Test func eachToggleIsItsOwnField() {
        for toggle in ToggleSetting.allCases {
            let base = AppSettings()
            let changed = toggle.applying(!toggle.value(in: base), to: base)
            let others = ToggleSetting.allCases.filter { $0 != toggle }
            #expect(others.allSatisfy { $0.value(in: changed) == $0.value(in: base) }, "\(toggle)")
        }
    }

    // MARK: Sliders

    /// A value inside every range, on a step, and not the default.
    private static let midValues: [SliderSetting: (shown: Double, stored: Double)] = [
        .threshold: (25, 0.25),
        .sustain: (2.3, 2_300),
        .quiet: (17, 17_000),
        .alertVolume: (40, 0.4),
        .alertRepeat: (12, 12_000),
        .failureGrace: (135, 135_000),
        .talkbackVolume: (35, 0.35),
    ]

    private func stored(_ slider: SliderSetting) -> Double {
        let app = dependencies.appSettings.settings
        let detector = dependencies.detectorSettings.settings
        return switch slider {
        case .threshold: Double(detector.threshold)
        case .sustain: Double(detector.sustainMs)
        case .quiet: Double(detector.quietMs)
        case .alertVolume: Double(app.alertVolume)
        case .alertRepeat: Double(app.alertRepeatIntervalMs)
        case .failureGrace: Double(app.failureGraceMs)
        case .talkbackVolume: Double(app.talkbackVolume)
        }
    }

    @Test(arguments: SliderSetting.allCases)
    func aSliderWritesTheStoreInItsUnitAndAFreshModelReadsItBack(_ slider: SliderSetting) async throws {
        let (shown, stored) = try #require(Self.midValues[slider])
        let model = makeModel()
        model.set(slider, shown)
        #expect(abs(model.value(slider) - shown) < 1e-9)
        await model.flush()
        #expect(abs(self.stored(slider) - stored) < 1e-6)
        #expect(abs(makeModel().value(slider) - shown) < 1e-9)
    }

    @Test(arguments: SliderSetting.allCases)
    func aSliderClampsAtBothEndsOfItsRange(_ slider: SliderSetting) async {
        let model = makeModel()
        model.set(slider, slider.range.lowerBound - 1_000)
        #expect(model.value(slider) == slider.range.lowerBound)
        await model.flush()
        #expect(makeModel().value(slider) == slider.range.lowerBound)

        model.set(slider, slider.range.upperBound + 1_000)
        #expect(model.value(slider) == slider.range.upperBound)
        await model.flush()
        #expect(makeModel().value(slider) == slider.range.upperBound)
    }

    @Test func slidersSnapToTheirStep() async {
        let model = makeModel()
        model.set(.sustain, 2.34)
        model.set(.failureGrace, 62)
        model.set(.threshold, 12.4)
        await model.flush()
        #expect(dependencies.detectorSettings.settings.sustainMs == 2_300)
        #expect(dependencies.appSettings.settings.failureGraceMs == 60_000)
        #expect(abs(dependencies.detectorSettings.settings.threshold - 0.12) < 1e-6)
    }

    /// The shown ranges are the spec's, which the stores also enforce.
    @Test func sliderRangesAreTheSpecsInTheShownUnit() {
        #expect(SliderSetting.threshold.range == 1...50)
        #expect(SliderSetting.sustain.range == 0.5...5)
        #expect(SliderSetting.quiet.range == 2...30)
        #expect(SliderSetting.alertVolume.range == 10...100)
        #expect(SliderSetting.alertRepeat.range == 3...30)
        #expect(SliderSetting.failureGrace.range == 30...300)
        #expect(SliderSetting.talkbackVolume.range == 0...100)
    }

    @Test func sliderLabelsMatchAndroidsFormats() {
        #expect(SliderSetting.threshold.formatted(12) == "12%")
        #expect(SliderSetting.sustain.formatted(1.5) == "1.5 s")
        #expect(SliderSetting.quiet.formatted(10) == "10 s")
        #expect(SliderSetting.alertVolume.formatted(100) == "100%")
        #expect(SliderSetting.failureGrace.formatted(60) == "60 s")
        #expect(SliderSetting.sustain.spokenValue(1.5) == "1.5 seconds of sound")
        #expect(SliderSetting.alertVolume.spokenValue(40) == "40 percent of your alarm volume")
        #expect(SliderSetting.alertRepeat.spokenValue(8) == "8 seconds")
    }

    @Test func rapidSliderChangesLandTheLastValue() async {
        let model = makeModel()
        for percent in stride(from: 1.0, through: 50, by: 1) { model.set(.threshold, percent) }
        model.set(.threshold, 33)
        await model.flush()
        #expect(abs(dependencies.detectorSettings.settings.threshold - 0.33) < 1e-6)
    }

    // MARK: Orientation

    @Test(arguments: OrientationLock.allCases)
    func orientationWritesTheStoreAndAFreshModelReadsItBack(_ lock: OrientationLock) async {
        let model = makeModel()
        model.orientationLock = lock
        await model.flush()
        #expect(dependencies.appSettings.settings.orientationLock == lock)
        #expect(makeModel().orientationLock == lock)
    }

    @Test func settingsLeaveTheAlertSoundAlone() async {
        await dependencies.appSettings.update { current in
            var next = current
            next.alertSound = "tone-1"
            return next
        }
        let model = makeModel()
        for toggle in ToggleSetting.allCases { model.set(toggle, !model.isOn(toggle)) }
        for slider in SliderSetting.allCases { model.set(slider, slider.range.upperBound) }
        model.orientationLock = .landscape
        await model.flush()
        #expect(dependencies.appSettings.settings.alertSound == "tone-1")
        #expect(model.settings.alertSoundTitle == "Chosen sound")
    }

    // MARK: Following the stores

    @Test func aChangeMadeElsewhereShowsUpWhileObserving() async throws {
        let model = makeModel()
        let observing = Task { await model.observe() }
        defer { observing.cancel() }
        await dependencies.appSettings.update { current in
            var next = current
            next.alertsEnabled = false
            return next
        }
        try await dependencies.cameras.upsert(nursery)
        try await waitUntil { !model.isOn(.alertsEnabled) && model.cameras == [nursery] }
    }

    @Test func theMeterShowsTheLevelSourcesLevel() async throws {
        let model = SettingsModel(dependencies: dependencies, levelSource: StaticLevelSource(level: 0.2))
        #expect(model.level == nil)
        let observing = Task { await model.observe() }
        defer { observing.cancel() }
        try await waitUntil { model.level == 0.2 }
    }

    @Test func theStubLevelIsUnknownNotZero() async {
        var levels: [Float?] = []
        for await level in StaticLevelSource().levelUpdates() { levels.append(level) }
        #expect(levels == [nil])
    }

    // MARK: Cameras

    @Test func switchingACameraOffWritesTheStore() async throws {
        try await dependencies.cameras.upsert(nursery)
        try await dependencies.cameras.upsert(playroom)
        let model = makeModel()
        model.setEnabled(nursery, false)
        #expect(model.cameras.first?.enabled == false)
        await model.flush()
        #expect(dependencies.cameras.enabledCameras == [playroom])
        #expect(makeModel().cameras.map(\.enabled) == [false, true])

        model.setEnabled(nursery, true)
        await model.flush()
        #expect(dependencies.cameras.enabledCameras.map(\.id) == [nursery.id, playroom.id])
    }

    @Test func removingACameraAsksFirst() async throws {
        try await dependencies.cameras.upsert(nursery)
        try await dependencies.cameras.upsert(playroom)
        let model = makeModel()

        model.requestRemoval(of: nursery)
        #expect(model.pendingRemoval == nursery)
        model.cancelRemoval()
        #expect(model.pendingRemoval == nil)
        await model.flush()
        #expect(dependencies.cameras.cameras.count == 2)

        model.requestRemoval(of: nursery)
        model.confirmRemoval(of: nursery)
        #expect(model.pendingRemoval == nil)
        #expect(model.cameras == [playroom])
        await model.flush()
        #expect(dependencies.cameras.cameras == [playroom])
        #expect(makeModel().cameras == [playroom])
    }

    /// SwiftUI's order when "Remove" is tapped: the dialog is dismissed first
    /// (its binding clears the pending camera), then the button runs. The
    /// camera the dialog was presented for is still removed.
    @Test func removalSurvivesTheDialogClearingThePendingCameraFirst() async throws {
        try await dependencies.cameras.upsert(nursery)
        let model = makeModel()
        model.requestRemoval(of: nursery)
        model.cancelRemoval()
        model.confirmRemoval(of: nursery)
        await model.flush()
        #expect(dependencies.cameras.cameras.isEmpty)
    }

    // MARK: Search

    @Test func searchFindsTitlesFirstThenDetailsAndKeywords() {
        let model = makeModel()
        model.query = "alarm"
        let ids = model.searchResults.map(\.id)
        // Only the grace period has "alarm" in its title; the master switch
        // says it under its title, and the volume and tone have it as a word.
        #expect(ids.first == "failure-grace")
        #expect(Set(ids.dropFirst()).isSuperset(of: ["alerts", "alert-volume", "alert-sound"]))
    }

    @Test func searchIsCaseInsensitiveAndTrimmed() {
        let model = makeModel()
        model.query = "  NIGHT  "
        #expect(model.searchResults.map(\.id).prefix(2) == ["night-theme", "checklist"])
    }

    @Test func searchFindsCurrentValuesAndKeywords() {
        let model = makeModel()
        model.query = "grace"
        #expect(model.searchResults.map(\.id) == ["failure-grace"])
        model.query = "rtsp"
        #expect(model.searchResults.first?.id == "add-by-url")
        model.query = "60 s"
        #expect(model.searchResults.map(\.id) == ["failure-grace"])
    }

    @Test func anEmptyQueryIsNotASearch() {
        let model = makeModel()
        model.query = "   "
        #expect(!model.isSearching)
        #expect(model.searchResults.isEmpty)
        model.query = "zzzz"
        #expect(model.isSearching)
        #expect(model.searchResults.isEmpty)
    }

    @Test func openingAResultShowsItsPageAndRow() throws {
        let model = makeModel()
        model.query = "repeat"
        let entry = try #require(model.searchResults.first)
        model.open(entry)
        #expect(model.query.isEmpty)
        #expect(model.selection == .alerts)
        #expect(model.focus == "alert-repeat")
    }

    @Test func everySearchEntryIsOnAPageThatHasIt() {
        let entries = SettingsSearch.entries(app: AppSettings(), detector: DetectorSettings())
        #expect(Set(entries.map(\.id)).count == entries.count)
        for slider in SliderSetting.allCases {
            #expect(entries.first { $0.id == slider.id }?.section == slider.section)
        }
        for toggle in ToggleSetting.allCases {
            #expect(entries.first { $0.id == toggle.id }?.section == toggle.section)
        }
    }

    // MARK: Structure

    @Test func sectionsAreGroupedSettingsThenChecklistThenAbout() {
        let groups = SettingsSection.groups
        #expect(groups[0] == [.cameras, .detection, .alerts, .display])
        #expect(groups[1] == [.checklist])
        #expect(groups[2] == [.about])
        #expect(Set(groups.joined()) == Set(SettingsSection.allCases))
        #expect(SettingsSection.defaultDetail == .cameras)
    }

    @Test func aModelOpensOnTheSectionItIsGiven() {
        #expect(SettingsModel(dependencies: dependencies, initialSection: .display).selection == .display)
        #expect(makeModel().selection == nil)
    }

    @Test(arguments: [
        ("alerts", SettingsSection?.some(.alerts)),
        ("checklist", .checklist),
        ("bogus", nil),
    ])
    func theSectionLaunchArgumentOpensThatSection(argument: String, section: SettingsSection?) throws {
        let suite = "SettingsModelTests-\(UUID().uuidString)"
        let defaults = try #require(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        defaults.set(argument, forKey: "settingsSection")
        #expect(SettingsLaunchOptions.section(defaults: defaults) == section)
    }

    // MARK: Support

    private func waitUntil(_ condition: () -> Bool) async throws {
        let deadline = ContinuousClock.now + .seconds(2)
        while !condition() {
            guard ContinuousClock.now < deadline else {
                Issue.record("timed out")
                return
            }
            try await Task.sleep(for: .milliseconds(10))
        }
    }
}

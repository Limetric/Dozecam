import Foundation
import Observation

/// Everything about how Dozecam is set up: which cameras exist and are
/// switched on, how sensitive the detector is, how alerts behave, and the
/// display. The counterpart of Android's `SettingsViewModel`.
///
/// Every control writes through the repositories and shows what they hold.
/// A change is applied here at once, so a control never lags a tap, and
/// written in the order it was made; `observe()` then follows the stores,
/// so a change made anywhere else (the viewer's alerts button, onboarding)
/// shows up here too.
@MainActor
@Observable
final class SettingsModel {
    let dependencies: AppDependencies
    let buildInfo: BuildInfo
    let levelSource: any LevelSource

    private(set) var settings: AppSettings
    private(set) var detector: DetectorSettings
    private(set) var cameras: [Camera]
    /// The loudest monitored camera's level, or nil while it is unknown.
    private(set) var level: Float?

    // MARK: Navigation

    /// The page showing: the iPad's detail, the iPhone's pushed page. On an
    /// iPad, nil shows `SettingsSection.defaultDetail`.
    var selection: SettingsSection?
    var query = ""
    /// The row a search result asked to be brought into view, until it has
    /// been.
    var focus: String?

    // MARK: Cameras

    /// The camera waiting on "Remove?", if any.
    private(set) var pendingRemoval: Camera?
    /// Whether the add-by-stream-URL sheet is up.
    var isAddingByURL = false
    /// A camera change the store refused, until it has been seen.
    var cameraError: String?

    /// The last write handed to a store. Each write waits for the one before,
    /// so a slider dragged quickly cannot land an older value last.
    @ObservationIgnored private var lastWrite: Task<Void, Never>?

    init(
        dependencies: AppDependencies,
        buildInfo: BuildInfo = .current,
        levelSource: any LevelSource = SettingsLaunchOptions.levelSource(),
        initialSection: SettingsSection? = SettingsLaunchOptions.section()
    ) {
        self.dependencies = dependencies
        self.buildInfo = buildInfo
        self.levelSource = levelSource
        settings = dependencies.appSettings.settings
        detector = dependencies.detectorSettings.settings
        cameras = dependencies.cameras.cameras
        selection = initialSection
    }

    /// Follows the stores and the level until cancelled: run it from the
    /// view's `.task`, so it stops when settings is dismissed.
    func observe() async {
        async let app: Void = followAppSettings()
        async let detector: Void = followDetectorSettings()
        async let cameras: Void = followCameras()
        async let level: Void = followLevel()
        _ = await (app, detector, cameras, level)
    }

    private func followAppSettings() async {
        for await next in dependencies.appSettings.settingsUpdates() { settings = next }
    }

    private func followDetectorSettings() async {
        for await next in dependencies.detectorSettings.settingsUpdates() { detector = next }
    }

    private func followCameras() async {
        for await next in dependencies.cameras.cameraUpdates() { cameras = next }
    }

    private func followLevel() async {
        for await next in levelSource.levelUpdates() { level = next }
    }

    /// Waits until every change made so far has reached its store.
    func flush() async {
        await lastWrite?.value
    }

    private func enqueue(_ write: @escaping @Sendable () async -> Void) {
        let previous = lastWrite
        lastWrite = Task {
            await previous?.value
            await write()
        }
    }

    // MARK: Settings

    func isOn(_ toggle: ToggleSetting) -> Bool {
        toggle.value(in: settings)
    }

    func set(_ toggle: ToggleSetting, _ isOn: Bool) {
        settings = toggle.applying(isOn, to: settings)
        let store = dependencies.appSettings
        enqueue { await store.update { toggle.applying(isOn, to: $0) } }
    }

    /// The slider's value in the unit it shows, on a step.
    func value(_ slider: SliderSetting) -> Double {
        slider.snapped(slider.value(app: settings, detector: detector))
    }

    /// Sets the slider, brought inside its range and onto a step.
    func set(_ slider: SliderSetting, _ value: Double) {
        let value = slider.snapped(value)
        if slider.isDetector {
            detector = slider.applying(value, to: detector)
            let store = dependencies.detectorSettings
            enqueue { await store.update { slider.applying(value, to: $0) } }
        } else {
            settings = slider.applying(value, to: settings)
            let store = dependencies.appSettings
            enqueue { await store.update { slider.applying(value, to: $0) } }
        }
    }

    var orientationLock: OrientationLock {
        get { settings.orientationLock }
        set {
            settings.orientationLock = newValue
            let store = dependencies.appSettings
            enqueue {
                await store.update { current in
                    var next = current
                    next.orientationLock = newValue
                    return next
                }
            }
        }
    }

    // MARK: Cameras

    func setEnabled(_ camera: Camera, _ enabled: Bool) {
        if let index = cameras.firstIndex(where: { $0.id == camera.id }) { cameras[index].enabled = enabled }
        let store = dependencies.cameras
        let id = camera.id
        enqueue { [weak self] in
            do {
                try await store.setEnabled(id: id, enabled)
            } catch {
                await self?.cameraChangeFailed()
            }
        }
    }

    func requestRemoval(of camera: Camera) {
        pendingRemoval = camera
    }

    func cancelRemoval() {
        pendingRemoval = nil
    }

    func confirmRemoval() {
        guard let camera = pendingRemoval else { return }
        pendingRemoval = nil
        cameras.removeAll { $0.id == camera.id }
        let store = dependencies.cameras
        let id = camera.id
        enqueue { [weak self] in
            do {
                try await store.remove(id: id)
            } catch {
                await self?.cameraChangeFailed()
            }
        }
    }

    /// Puts back what the store holds and says why the change did not take.
    private func cameraChangeFailed() {
        cameras = dependencies.cameras.cameras
        cameraError = "The camera list could not be saved. Unlock the device and try again."
    }

    // MARK: Search

    var searchEntries: [SettingSearchEntry] {
        SettingsSearch.entries(app: settings, detector: detector)
    }

    var searchResults: [SettingSearchEntry] {
        SettingsSearch.search(query, in: searchEntries)
    }

    var isSearching: Bool {
        !query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    /// Opens the page a result lives on and asks for its row to be shown.
    func open(_ entry: SettingSearchEntry) {
        query = ""
        focus = entry.id
        selection = entry.section
    }
}

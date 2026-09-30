import Foundation
import Observation

/// Which of the three destinations the app shows, the counterpart of
/// Android's three activities: the monitor (the viewer), onboarding (adding a
/// console or cameras) and settings.
@MainActor
@Observable
final class AppModel {
    enum Destination: Equatable {
        case onboarding
        case monitor
        /// The user exited (shared/spec/monitoring-lifecycle.md: "leave
        /// Dozecam"). iOS apps cannot quit themselves, so the viewer is torn
        /// down, every camera session with it, and a screen says Dozecam is
        /// off. The next open of the viewer starts again.
        case exited
    }

    private(set) var destination: Destination
    var isShowingSettings = false

    let dependencies: AppDependencies
    let monitor: MonitorModel
    /// The wake-on-sound monitor, which outlives the viewer that arms it.
    let monitoring: MonitoringService
    let onboarding: OnboardingModel
    let settings: SettingsModel

    /// With no cameras there is nothing to monitor, so the app opens on
    /// onboarding, as Android's viewer does. `makePlayer` builds each camera's
    /// player for the viewer; the default never plays (`PendingLivePlayers`),
    /// and neither does the default monitor's.
    init(
        dependencies: AppDependencies,
        makePlayer: @escaping CameraSessions.MakePlayer = PendingLivePlayers.make(for:),
        monitoring: MonitoringService? = nil,
        destination: Destination? = nil
    ) {
        self.dependencies = dependencies
        let monitoring =
            monitoring
            ?? MonitoringService(dependencies: dependencies, speaker: .shared, makePlayer: PendingAudioPlayer.make)
        self.monitoring = monitoring
        monitor = MonitorModel(dependencies: dependencies, monitoring: monitoring, makePlayer: makePlayer)
        onboarding = OnboardingModel(dependencies: dependencies)
        settings = SettingsModel(
            dependencies: dependencies,
            levelSource: SettingsLaunchOptions.levelSource(fallback: MonitoringLevelSource(monitoring: monitoring)))
        self.destination = destination ?? (dependencies.cameras.cameras.isEmpty ? .onboarding : .monitor)
        monitor.exitHandler = { [weak self] in self?.exit() }
    }

    /// Leaves the viewer, whose sessions end with it, and stops monitoring
    /// (shared/spec/monitoring-lifecycle.md, "Exit"). The dead-man alarm
    /// (#68) stops here too once it exists.
    func exit() {
        monitoring.exit()
        isShowingSettings = false
        destination = .exited
    }

    /// Opening the viewer again after an exit: from the exited screen, or by
    /// coming back to the app, which is how iOS users reopen one.
    func resumeAfterExit() {
        guard destination == .exited else { return }
        destination = monitor.dependencies.cameras.cameras.isEmpty ? .onboarding : .monitor
    }

    func finishOnboarding() {
        destination = .monitor
    }

    func addCameras() {
        isShowingSettings = false
        destination = .onboarding
    }

    func openSettings() {
        isShowingSettings = true
    }
}

#if DEBUG
    extension AppModel {
        /// Debug builds only: `-startOn monitor` or `-startOn settings` as a launch
        /// argument opens that destination directly, since nothing can tap
        /// through onboarding in a simulator run by an agent.
        static func forLaunch(
            dependencies: AppDependencies,
            makePlayer: @escaping CameraSessions.MakePlayer = PendingLivePlayers.make(for:),
            monitoring: MonitoringService? = nil,
            defaults: UserDefaults = .standard
        ) -> AppModel {
            let startOn = defaults.string(forKey: "startOn")
            let destination: Destination? =
                switch startOn {
                case "monitor", "settings": .monitor
                case "onboarding": .onboarding
                default: nil
                }
            let model = AppModel(
                dependencies: dependencies, makePlayer: makePlayer, monitoring: monitoring, destination: destination)
            if startOn == "settings" { model.openSettings() }
            return model
        }
    }
#endif

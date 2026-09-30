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
    }

    private(set) var destination: Destination
    var isShowingSettings = false

    let dependencies: AppDependencies
    let monitor = MonitorModel()
    let onboarding: OnboardingModel
    let settings: SettingsModel

    /// With no cameras there is nothing to monitor, so the app opens on
    /// onboarding, as Android's viewer does.
    init(dependencies: AppDependencies, destination: Destination? = nil) {
        self.dependencies = dependencies
        onboarding = OnboardingModel(dependencies: dependencies)
        settings = SettingsModel(dependencies: dependencies)
        self.destination = destination ?? (dependencies.cameras.cameras.isEmpty ? .onboarding : .monitor)
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
        static func forLaunch(dependencies: AppDependencies, defaults: UserDefaults = .standard) -> AppModel {
            let startOn = defaults.string(forKey: "startOn")
            let destination: Destination? =
                switch startOn {
                case "monitor", "settings": .monitor
                case "onboarding": .onboarding
                default: nil
                }
            let model = AppModel(dependencies: dependencies, destination: destination)
            if startOn == "settings" { model.openSettings() }
            return model
        }
    }
#endif

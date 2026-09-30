import Observation

/// Settings and the night checklist (#65, #69). A stub until then.
@MainActor
@Observable
final class SettingsModel {
    let dependencies: AppDependencies
    let buildInfo: BuildInfo

    init(dependencies: AppDependencies, buildInfo: BuildInfo = .current) {
        self.dependencies = dependencies
        self.buildInfo = buildInfo
    }
}

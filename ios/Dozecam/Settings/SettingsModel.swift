import Observation

/// Settings and the night checklist (#65, #69). A stub until then.
@MainActor
@Observable
final class SettingsModel {
    let buildInfo: BuildInfo

    init(buildInfo: BuildInfo = .current) {
        self.buildInfo = buildInfo
    }
}

import Observation

/// Console sign-in and camera discovery (#64, #65). A stub until then.
@MainActor
@Observable
final class OnboardingModel {
    let dependencies: AppDependencies

    init(dependencies: AppDependencies) {
        self.dependencies = dependencies
    }
}

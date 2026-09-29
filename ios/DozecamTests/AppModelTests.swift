import Foundation
import Testing

@testable import Dozecam

@MainActor
struct AppModelTests {
    @Test func withoutCamerasTheAppOpensOnOnboarding() {
        #expect(AppModel(hasCameras: false).destination == .onboarding)
    }

    @Test func withCamerasTheAppOpensOnTheMonitor() {
        #expect(AppModel(hasCameras: true).destination == .monitor)
    }

    @Test func finishingOnboardingShowsTheMonitor() {
        let model = AppModel(hasCameras: false)
        model.finishOnboarding()
        #expect(model.destination == .monitor)
    }

    @Test func addingCamerasFromSettingsClosesSettingsAndOpensOnboarding() {
        let model = AppModel(hasCameras: true)
        model.openSettings()
        #expect(model.isShowingSettings)
        model.addCameras()
        #expect(!model.isShowingSettings)
        #expect(model.destination == .onboarding)
    }

    @Test(arguments: [
        (nil, AppModel.Destination.onboarding, false),
        ("monitor", .monitor, false),
        ("settings", .monitor, true),
        ("bogus", .onboarding, false),
    ])
    func launchArgumentOpensADestination(startOn: String?, destination: AppModel.Destination, settings: Bool) throws {
        let suite = "AppModelTests-\(UUID().uuidString)"
        let defaults = try #require(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        defaults.set(startOn, forKey: "startOn")
        let model = AppModel.forLaunch(defaults: defaults)
        #expect(model.destination == destination)
        #expect(model.isShowingSettings == settings)
    }
}

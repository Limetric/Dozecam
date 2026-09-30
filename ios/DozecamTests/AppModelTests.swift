import Foundation
import Testing

@testable import Dozecam

@MainActor
struct AppModelTests {
    private func dependencies(cameras: [Camera] = []) async throws -> AppDependencies {
        let dependencies = AppDependencies.isolated()
        for camera in cameras { try await dependencies.cameras.upsert(camera) }
        return dependencies
    }

    private let nursery = Camera(id: "manual-1", name: "Nursery", url: "rtsp://127.0.0.1:18554/nursery")

    @Test func withoutCamerasTheAppOpensOnOnboarding() async throws {
        #expect(AppModel(dependencies: try await dependencies()).destination == .onboarding)
    }

    @Test func withCamerasTheAppOpensOnTheMonitor() async throws {
        #expect(AppModel(dependencies: try await dependencies(cameras: [nursery])).destination == .monitor)
    }

    @Test func finishingOnboardingShowsTheMonitor() async throws {
        let model = AppModel(dependencies: try await dependencies())
        model.finishOnboarding()
        #expect(model.destination == .monitor)
    }

    @Test func addingCamerasFromSettingsClosesSettingsAndOpensOnboarding() async throws {
        let model = AppModel(dependencies: try await dependencies(cameras: [nursery]))
        model.openSettings()
        #expect(model.isShowingSettings)
        model.addCameras()
        #expect(!model.isShowingSettings)
        #expect(model.destination == .onboarding)
    }

    /// Exit leaves the viewer (its sessions go with it) and reopening it
    /// starts again; with every camera gone meanwhile it opens on onboarding.
    @Test func exitingLeavesTheViewerAndReopeningStartsAgain() async throws {
        let dependencies = try await dependencies(cameras: [nursery])
        let model = AppModel(dependencies: dependencies)
        model.openSettings()
        model.monitor.requestExit()
        model.monitor.confirmExit()
        #expect(model.destination == .exited)
        #expect(!model.isShowingSettings)

        model.resumeAfterExit()
        #expect(model.destination == .monitor)

        model.exit()
        try await dependencies.cameras.remove(id: nursery.id)
        model.resumeAfterExit()
        #expect(model.destination == .onboarding)
    }

    @Test func resumingDoesNothingUnlessExited() async throws {
        let model = AppModel(dependencies: try await dependencies(cameras: [nursery]))
        model.resumeAfterExit()
        #expect(model.destination == .monitor)
    }

    @Test(arguments: [
        (nil, AppModel.Destination.onboarding, false),
        ("monitor", .monitor, false),
        ("settings", .monitor, true),
        ("onboarding", .onboarding, false),
        ("bogus", .onboarding, false),
    ])
    func launchArgumentOpensADestination(startOn: String?, destination: AppModel.Destination, settings: Bool)
        async throws
    {
        let suite = "AppModelTests-\(UUID().uuidString)"
        let defaults = try #require(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        defaults.set(startOn, forKey: "startOn")
        let model = AppModel.forLaunch(dependencies: try await dependencies(), defaults: defaults)
        #expect(model.destination == destination)
        #expect(model.isShowingSettings == settings)
    }
}

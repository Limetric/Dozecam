import SwiftUI

@main
struct DozecamApp: App {
    #if DEBUG
        @State private var model = MonitorDebugLaunch.appModel() ?? Self.liveModel()
    #else
        @State private var model = Self.liveModel()
    #endif

    /// The real stores and the real players: VLCKit over RTSP, and the Protect
    /// livestream for cameras of the signed-in console. `LivePlayers` is kept
    /// alive by the `make` it hands over.
    @MainActor
    private static func liveModel() -> AppModel {
        let dependencies = AppDependencies.live()
        let players = LivePlayers(dependencies: dependencies)
        let monitoring = MonitoringService(
            dependencies: dependencies, speaker: .shared, makePlayer: players.makeAudio(cameraId:sink:))
        #if DEBUG
            return AppModel.forLaunch(
                dependencies: dependencies, makePlayer: players.make(for:), monitoring: monitoring)
        #else
            return AppModel(dependencies: dependencies, makePlayer: players.make(for:), monitoring: monitoring)
        #endif
    }

    var body: some Scene {
        WindowGroup {
            #if DEBUG
                if let url = DebugPlayer.launchURL() {
                    DebugPlayerView(url: url)
                } else {
                    RootView(model: model).modifier(DebugWindowShape())
                }
            #else
                RootView(model: model)
            #endif
        }
    }
}

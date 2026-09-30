import SwiftUI

@main
struct DozecamApp: App {
    #if DEBUG
        @State private var model =
            MonitorDebugLaunch.appModel()
            ?? AppModel.forLaunch(dependencies: .live(), makePlayer: PendingLivePlayers.make(for:))
    #else
        @State private var model = AppModel(dependencies: .live(), makePlayer: PendingLivePlayers.make(for:))
    #endif

    var body: some Scene {
        WindowGroup {
            #if DEBUG
                RootView(model: model).modifier(DebugWindowShape())
            #else
                RootView(model: model)
            #endif
        }
    }
}

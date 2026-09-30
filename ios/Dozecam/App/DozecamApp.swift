import SwiftUI

@main
struct DozecamApp: App {
    #if DEBUG
        @State private var model = AppModel.forLaunch(dependencies: .live())
    #else
        @State private var model = AppModel(dependencies: .live())
    #endif

    var body: some Scene {
        WindowGroup {
            RootView(model: model)
        }
    }
}

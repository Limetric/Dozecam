import SwiftUI

@main
struct DozecamApp: App {
    #if DEBUG
        @State private var model = AppModel.forLaunch()
    #else
        @State private var model = AppModel()
    #endif

    var body: some Scene {
        WindowGroup {
            RootView(model: model)
        }
    }
}

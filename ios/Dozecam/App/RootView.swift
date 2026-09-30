import SwiftUI

struct RootView: View {
    @Bindable var model: AppModel
    @Environment(\.scenePhase) private var scenePhase
    @State private var wentToBackground = false

    var body: some View {
        Group {
            switch model.destination {
            case .onboarding:
                OnboardingView(model: model.onboarding, onFinish: model.finishOnboarding)
            case .monitor:
                MonitorView(model: model.monitor, onOpenSettings: model.openSettings, onAddCameras: model.addCameras)
            case .exited:
                ExitedView(onResume: model.resumeAfterExit)
            }
        }
        // Leaving the app after an exit and coming back is reopening it.
        .onChange(of: scenePhase) { _, phase in
            if phase == .background { wentToBackground = true }
            if phase == .active, wentToBackground {
                wentToBackground = false
                model.resumeAfterExit()
            }
        }
        .sheet(isPresented: $model.isShowingSettings) {
            SettingsView(model: model.settings, onAddCameras: model.addCameras)
        }
    }
}

import SwiftUI

struct RootView: View {
    @Bindable var model: AppModel

    var body: some View {
        Group {
            switch model.destination {
            case .onboarding:
                OnboardingView(model: model.onboarding, onFinish: model.finishOnboarding)
            case .monitor:
                MonitorView(model: model.monitor, onOpenSettings: model.openSettings, onAddCameras: model.addCameras)
            }
        }
        .sheet(isPresented: $model.isShowingSettings) {
            SettingsView(model: model.settings, onAddCameras: model.addCameras)
        }
    }
}

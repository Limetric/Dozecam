import SwiftUI

/// What "Add by stream URL" presents: onboarding's manual entry form, which
/// saves through the camera store; the Cameras list follows the store.
struct AddCameraByURLSheet: View {
    let model: SettingsModel
    @State private var entry: ManualCameraEntryModel
    @Environment(\.dismiss) private var dismiss

    init(model: SettingsModel) {
        self.model = model
        _entry = State(initialValue: ManualCameraEntryModel(cameras: model.dependencies.cameras))
    }

    var body: some View {
        NavigationStack {
            ManualCameraEntryView(model: entry) { _ in dismiss() }
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("Cancel") { dismiss() }
                    }
                }
        }
    }
}

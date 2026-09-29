import SwiftUI

struct SettingsView: View {
    let model: SettingsModel
    let onAddCameras: () -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                Section("Cameras") {
                    Button("Add cameras", action: onAddCameras)
                }
                Section("About") {
                    LabeledContent("Version", value: model.buildInfo.summary)
                }
            }
            .navigationTitle("Settings")
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
    }
}

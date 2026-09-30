import SwiftUI

struct SettingsView: View {
    let model: SettingsModel
    let onAddCameras: () -> Void
    @Environment(\.dismiss) private var dismiss
    #if DEBUG
        @State private var consoleDebug = ConsoleDebugModel()
    #endif

    var body: some View {
        NavigationStack {
            Form {
                Section("Cameras") {
                    Button("Add cameras", action: onAddCameras)
                }
                Section("About") {
                    LabeledContent("Version", value: model.buildInfo.summary)
                }
                #if DEBUG
                    Section("Debug") {
                        NavigationLink("Console debug") { ConsoleDebugView(model: consoleDebug) }
                    }
                #endif
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

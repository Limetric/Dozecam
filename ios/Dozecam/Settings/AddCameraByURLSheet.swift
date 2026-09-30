import SwiftUI

/// What "Add by stream URL" presents. A placeholder: the manual entry form is
/// `ManualCameraEntry` in Onboarding/ (#65), and this sheet's content becomes
/// that view, which saves through `dependencies.cameras` and dismisses itself.
struct AddCameraByURLSheet: View {
    let model: SettingsModel
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ContentUnavailableView {
                Label("Add by stream URL", systemImage: "link")
            } description: {
                Text("Entering a camera's rtsp:// address arrives with onboarding.")
            }
            .navigationTitle("Add camera")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
            }
        }
    }
}

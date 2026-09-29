import SwiftUI

struct MonitorView: View {
    let model: MonitorModel
    let onOpenSettings: () -> Void
    let onAddCameras: () -> Void

    var body: some View {
        NavigationStack {
            ContentUnavailableView {
                Label("No cameras yet", systemImage: "video.slash")
            } description: {
                Text("Live view arrives with the camera grid.")
            } actions: {
                Button("Add cameras", action: onAddCameras)
            }
            .navigationTitle("Dozecam")
            .toolbar {
                ToolbarItem(placement: .primaryAction) {
                    Button("Settings", systemImage: "gearshape", action: onOpenSettings)
                }
            }
        }
    }
}

import SwiftUI

/// What is left after Exit: nothing is being watched or listened to, said
/// plainly, with the way back.
struct ExitedView: View {
    let onResume: () -> Void

    var body: some View {
        ContentUnavailableView {
            Label("Dozecam is off", systemImage: "moon.zzz")
        } description: {
            Text("Nothing is being watched or listened to. Open the viewer to start again.")
        } actions: {
            Button("Start Watching", action: onResume)
                .buttonStyle(.borderedProminent)
        }
    }
}

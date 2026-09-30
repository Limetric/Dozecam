import SwiftUI
import UIKit

/// Hosts a player's view. A camera moving between the grid and fullscreen is
/// shown by one surface and then another; the player's view can only have one
/// superview, so a surface takes it when it updates and gives it up only if
/// it still holds it, whichever order SwiftUI builds and dismantles them in
/// (Android's `CameraStream.attach`/`detach`).
struct PlayerSurface: UIViewRepresentable {
    let player: any VideoPlayerController

    func makeUIView(context: Context) -> UIView {
        let container = UIView()
        container.backgroundColor = .clear
        container.clipsToBounds = true
        attach(to: container)
        return container
    }

    func updateUIView(_ container: UIView, context: Context) {
        attach(to: container)
    }

    static func dismantleUIView(_ container: UIView, coordinator: ()) {
        for view in container.subviews { view.removeFromSuperview() }
    }

    private func attach(to container: UIView) {
        let view = player.view
        guard view.superview !== container else { return }
        for stale in container.subviews { stale.removeFromSuperview() }
        view.frame = container.bounds
        view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        container.addSubview(view)
    }
}

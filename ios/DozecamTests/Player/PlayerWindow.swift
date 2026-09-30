import UIKit

@testable import Dozecam

/// Puts a player's view on screen in the test host. VLC builds its video
/// output for a view with a size in a window; a detached, zero-sized view
/// gets none, so nothing would ever be displayed or counted.
@MainActor
final class PlayerWindow {
    private let window: UIWindow

    convenience init(_ controller: any VideoPlayerController) { self.init(view: controller.view) }

    init(view: UIView) {
        let scene = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first
        window = scene.map { UIWindow(windowScene: $0) } ?? UIWindow()
        window.frame = CGRect(x: 0, y: 0, width: 320, height: 180)
        window.windowLevel = .normal + 1
        view.frame = window.bounds
        view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        window.addSubview(view)
        window.isHidden = false
    }

    func close() {
        window.isHidden = true
        for subview in window.subviews { subview.removeFromSuperview() }
    }
}

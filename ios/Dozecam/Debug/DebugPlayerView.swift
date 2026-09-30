#if DEBUG
    import SwiftUI

    /// Debug builds only: `-debugPlayURL rtsp://…` as a launch argument shows
    /// that one stream full-screen instead of the app, with the player's
    /// events overlaid, so an agent can watch a player work without a
    /// console, a camera list or a tap.
    enum DebugPlayer {
        static func launchURL(_ defaults: UserDefaults = .standard) -> String? {
            defaults.string(forKey: "debugPlayURL")
        }
    }

    struct DebugPlayerView: View {
        @State private var model: DebugPlayerModel

        init(url: String) {
            _model = State(initialValue: DebugPlayerModel(url: url))
        }

        var body: some View {
            PlayerHost(controller: model.controller)
                .ignoresSafeArea()
                .overlay(alignment: .bottomLeading) {
                    Text(model.status)
                        .font(.caption.monospaced())
                        .padding(8)
                        .background(.black.opacity(0.6))
                        .foregroundStyle(.white)
                        .padding()
                }
                .onAppear { model.start() }
                .onDisappear { model.stop() }
        }
    }

    @MainActor
    @Observable
    final class DebugPlayerModel {
        let controller: any VideoPlayerController
        private let source: StreamSource
        private(set) var status = "starting"
        private var frames = 0
        private var lastState = "connecting"
        private var aspect: Double?

        init(url: String) {
            source = .rtsp(url: url)
            controller = VlcVideoPlayerController()
        }

        func start() {
            controller.onEvent = { [weak self] event in self?.record(event) }
            controller.setMuted(true)
            controller.play(source)
        }

        func stop() {
            controller.release()
        }

        private func record(_ event: PlayerEvent) {
            switch event {
            case .timeChanged: frames += 1
            case .videoAspect(let ratio): aspect = ratio
            default: lastState = "\(event)"
            }
            let shape = aspect.map { String(format: "%.3f", $0) } ?? "?"
            status = "\(lastState) · frame ticks \(frames) · aspect \(shape)"
        }
    }

    /// Hosts a controller's view, filling the space it is given.
    private struct PlayerHost: UIViewRepresentable {
        let controller: any VideoPlayerController

        func makeUIView(context: Context) -> UIView {
            let container = UIView()
            container.backgroundColor = .black
            let view = controller.view
            view.frame = container.bounds
            view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
            container.addSubview(view)
            return container
        }

        func updateUIView(_ uiView: UIView, context: Context) {}
    }
#endif

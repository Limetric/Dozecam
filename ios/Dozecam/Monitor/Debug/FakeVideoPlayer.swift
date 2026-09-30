#if DEBUG
    import UIKit

    /// Debug builds only: a player that draws a moving test pattern and reports
    /// frames like a real one, following a script, so the viewer can be seen in
    /// every state on a simulator with no camera (`MonitorDebugLaunch`).
    @MainActor
    final class FakeVideoPlayer: VideoPlayerController {
        enum Script: String {
            /// Plays for as long as the network is up.
            case live
            /// Plays briefly, then freezes and never recovers: reconnecting,
            /// attempt after attempt, with the frozen frame's age.
            case stall
            /// Never delivers a frame: connecting, then reconnecting.
            case never
            /// Says its codec cannot be decoded here.
            case unsupported
            /// Plays, freezes, and recovers on the restart that follows.
            case flaky
        }

        var onEvent: ((PlayerEvent) -> Void)?
        var view: UIView { pattern }

        private let script: Script
        private let pattern: TestPatternView
        private var ticker: Task<Void, Never>?
        private var plays = 0
        private var playedMs: Int64 = 0
        private var sessionMs: Int64 = 0

        init(script: Script, name: String, hue: CGFloat) {
            self.script = script
            pattern = TestPatternView(name: name, hue: hue)
        }

        func play(_ source: StreamSource) {
            ticker?.cancel()
            plays += 1
            sessionMs = 0
            ticker = Task { [weak self] in
                try? await Task.sleep(for: .milliseconds(400))
                guard let self, !Task.isCancelled else { return }
                if script == .unsupported {
                    onEvent?(.unsupportedCodec("AV1"))
                    return
                }
                onEvent?(.videoAspect(16.0 / 9.0))
                while !Task.isCancelled {
                    if deliversFrame() {
                        playedMs += 100
                        pattern.show(ms: playedMs)
                        onEvent?(sessionMs == 0 ? .playing : .timeChanged(milliseconds: playedMs))
                    }
                    sessionMs += 100
                    try? await Task.sleep(for: .milliseconds(100))
                }
            }
        }

        private func deliversFrame() -> Bool {
            guard FakeNetwork.isUp else { return false }
            switch script {
            case .live: return true
            case .stall: return plays == 1 && sessionMs < 1_500
            case .never, .unsupported: return false
            case .flaky: return plays > 1 || sessionMs < 6_000
            }
        }

        func setVideoEnabled(_ enabled: Bool) {}

        func stop() {
            ticker?.cancel()
            ticker = nil
        }

        func release() { stop() }
    }

    /// Whether the fake cameras can be reached: goes down with the scripted
    /// network, so a tile freezes when it says offline, as a real one would.
    @MainActor
    enum FakeNetwork {
        static var isUp = true
    }

    /// Colour bars, a sweeping bar and a clock: motion that makes a frozen
    /// frame obvious in a screenshot.
    private final class TestPatternView: UIView {
        private let bars: [CALayer]
        private let sweep = CALayer()
        private let clock = UILabel()
        private let title = UILabel()
        private let speaker = UIImageView(image: UIImage(systemName: "speaker.wave.2.fill"))

        init(name: String, hue: CGFloat) {
            bars = (0..<7).map { index in
                let layer = CALayer()
                let barHue = (hue + CGFloat(index) / 7).truncatingRemainder(dividingBy: 1)
                layer.backgroundColor = UIColor(hue: barHue, saturation: 0.45, brightness: 0.55, alpha: 1).cgColor
                return layer
            }
            super.init(frame: .zero)
            backgroundColor = .clear
            for bar in bars {
                // Black until the first frame: a camera that never plays shows
                // nothing, as a real one would.
                bar.isHidden = true
                layer.addSublayer(bar)
            }
            sweep.backgroundColor = UIColor(white: 1, alpha: 0.35).cgColor
            layer.addSublayer(sweep)
            title.text = name
            title.isHidden = true
            title.font = .systemFont(ofSize: 22, weight: .bold)
            title.textColor = .white
            clock.font = .monospacedDigitSystemFont(ofSize: 18, weight: .medium)
            clock.textColor = .white
            speaker.tintColor = .white
            speaker.isHidden = true
            [title, clock, speaker].forEach(addSubview)
        }

        required init?(coder: NSCoder) { fatalError("init(coder:) is not used") }

        override func layoutSubviews() {
            super.layoutSubviews()
            let width = bounds.width / CGFloat(bars.count)
            for (index, bar) in bars.enumerated() {
                bar.frame = CGRect(x: CGFloat(index) * width, y: 0, width: width + 1, height: bounds.height)
            }
            title.sizeToFit()
            clock.sizeToFit()
            title.center = CGPoint(x: bounds.midX, y: bounds.midY - 12)
            clock.center = CGPoint(x: bounds.midX, y: bounds.midY + 16)
            speaker.center = CGPoint(x: bounds.midX, y: bounds.midY + 44)
        }

        func show(ms: Int64) {
            CATransaction.begin()
            CATransaction.setDisableActions(true)
            for bar in bars { bar.isHidden = false }
            title.isHidden = false
            let phase = CGFloat(ms % 4_000) / 4_000
            sweep.frame = CGRect(x: phase * bounds.width - 20, y: 0, width: 40, height: bounds.height)
            CATransaction.commit()
            clock.text = String(format: "%02lld:%02lld.%lld", ms / 60_000, (ms / 1_000) % 60, (ms / 100) % 10)
            setNeedsLayout()
        }

        func setAudible(_ audible: Bool) { speaker.isHidden = !audible }
    }
#endif

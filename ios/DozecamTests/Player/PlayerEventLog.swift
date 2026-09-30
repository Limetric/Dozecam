import Foundation

@testable import Dozecam

/// Records a controller's events, and waits for the ones a test expects.
@MainActor
final class PlayerEventLog {
    private(set) var events: [PlayerEvent] = []

    init(_ controller: any VideoPlayerController) {
        controller.onEvent = { [weak self] in self?.events.append($0) }
    }

    var frames: Int { events.count(where: { if case .timeChanged = $0 { true } else { false } }) }

    var unsupportedCodec: String? {
        events.lazy.compactMap { if case .unsupportedCodec(let name) = $0 { name } else { nil } }.first
    }

    private var lastFrameCount = 0
    private var lastFrameChange = ContinuousClock.now

    /// Whether no frame has arrived for `duration`, judged across the calls
    /// a `wait` makes.
    func quietFor(_ duration: Duration) -> Bool {
        let now = ContinuousClock.now
        if lastFrameCount != frames {
            lastFrameCount = frames
            lastFrameChange = now
        }
        return frames > 0 && now - lastFrameChange >= duration
    }

    /// Polls until `condition` holds or `timeout` passes; returns whether it held.
    func wait(for timeout: Duration = .seconds(15), until condition: (PlayerEventLog) -> Bool) async -> Bool {
        let deadline = ContinuousClock.now + timeout
        while ContinuousClock.now < deadline {
            if condition(self) { return true }
            try? await Task.sleep(for: .milliseconds(50))
        }
        return condition(self)
    }
}

import AVFAudio
import Synchronization

/// Tells when the speaker is lost for good, the iOS counterpart of Android's
/// permanent audio-focus loss: headphones unplugged, a Bluetooth speaker gone.
/// Playing on would move the rooms onto the device's own speaker unasked
/// (shared/spec/alerts-and-sound-modes.md). The seam lets tests unplug by hand.
protocol SpeakerLossSource: Sendable {
    func losses() -> AsyncStream<Void>
}

struct SystemSpeakerLossSource: SpeakerLossSource {
    func losses() -> AsyncStream<Void> {
        AsyncStream { continuation in
            // Posted on whichever thread changed the route; nothing here may
            // assume the main actor (#58).
            nonisolated(unsafe) let observer = NotificationCenter.default.addObserver(
                forName: AVAudioSession.routeChangeNotification, object: nil, queue: nil
            ) { notification in
                if Self.isLoss(notification.userInfo) { continuation.yield() }
            }
            continuation.onTermination = { _ in NotificationCenter.default.removeObserver(observer) }
        }
    }

    /// Only the device the sound was playing through going away counts. A
    /// new device, a category change or an override is not a loss.
    nonisolated static func isLoss(_ userInfo: [AnyHashable: Any]?) -> Bool {
        guard let raw = userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt else { return false }
        return AVAudioSession.RouteChangeReason(rawValue: raw) == .oldDeviceUnavailable
    }
}

/// A speaker the caller unplugs by hand: for tests and previews.
final class ManualSpeakerLossSource: SpeakerLossSource {
    private let continuations = Mutex<[Int: AsyncStream<Void>.Continuation]>([:])
    private let nextId = Atomic<Int>(0)

    func losses() -> AsyncStream<Void> {
        AsyncStream { continuation in
            let id = nextId.add(1, ordering: .relaxed).newValue
            continuations.withLock { $0[id] = continuation }
            continuation.onTermination = { [weak self] _ in
                _ = self?.continuations.withLock { $0.removeValue(forKey: id) }
            }
        }
    }

    /// Someone is listening, so an unplug now is heard.
    var isObserved: Bool { continuations.withLock { !$0.isEmpty } }

    func unplug() {
        for continuation in continuations.withLock({ Array($0.values) }) { continuation.yield() }
    }
}

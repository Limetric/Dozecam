import AVFAudio
import os

/// What the audio session and engine tell the speaker, reduced to what it acts
/// on. Delivered on the main actor.
enum SpeakerHardwareEvent: Equatable, Sendable {
    /// Another app's audio took the session for a while: a call, Siri, an
    /// alarm (AlarmKit's included, #58). The engine has stopped.
    case interruptionBegan
    /// The interruption is over. `shouldResume` is the system's hint, which
    /// the speaker notes but does not obey: monitoring stays alive only while
    /// the engine runs.
    case interruptionEnded(shouldResume: Bool)
    /// The device the sound was playing through went away: headphones out, a
    /// Bluetooth speaker gone (`.oldDeviceUnavailable`). The loss for good of
    /// shared/spec/alerts-and-sound-modes.md, "The speaker".
    case routeLost
    /// Something else in the process set the session to a category or options
    /// other than ours, as libVLC's own audio output does for the viewer
    /// (`VlcPlayerCore.applyMute`). Without `.mixWithOthers` the session cannot
    /// be reactivated from the background (#58), so the speaker puts it back.
    case categoryChanged
    /// The media volume, 0 to 1. Zero means the mix plays into nothing.
    case outputVolumeChanged(Float)
    /// The media server restarted: the engine and the session's settings are
    /// gone, and everything has to be set up again.
    case mediaServicesReset
    /// The engine stopped itself on a configuration change (a new route, a new
    /// sample rate) and has to be started again.
    case engineStopped
}

/// The audio session and engine, behind a seam so the speaker's rules can be
/// tested without real audio. Main actor: the speaker drives it from there,
/// and implementations hop the system's notifications there before calling
/// `onEvent`.
@MainActor
protocol SpeakerHardware: AnyObject {
    var onEvent: ((SpeakerHardwareEvent) -> Void)? { get set }
    /// The media volume now, 0 to 1.
    var outputVolume: Float { get }
    var isEngineRunning: Bool { get }
    /// Whether the session is still `.playback` with `.mixWithOthers`.
    var isSessionOurs: Bool { get }
    /// Sets the session to `.playback` with `.mixWithOthers` and activates it.
    func activate() throws
    /// Deactivates the session, telling other apps they may resume.
    func deactivate()
    /// Starts the engine, rendering the mix it was built with.
    func startEngine() throws
    func stopEngine()
}

/// `AVAudioSession` and one `AVAudioEngine` whose single source node renders a
/// `SpeakerMix`.
///
/// **The session.** `.playback` with `.mixWithOthers`. Playback keeps the app
/// running with the screen locked (`UIBackgroundModes: audio`), for as long
/// as the engine runs. Mixing is required, not a courtesy: without it the
/// session cannot be reactivated from the background once an interruption such
/// as an alarm ends (`cannotInterruptOthers`), and monitoring ends (#58). It
/// also leaves a parent's podcast alone.
///
/// **Threads.** The render block runs on the audio I/O thread and the
/// notifications arrive on whatever thread posted them. Both are built in
/// nonisolated static functions, so neither inherits the main actor (the #58
/// trap), and the notifications are hopped onto the main queue in order.
@MainActor
final class SystemSpeakerHardware: SpeakerHardware {
    var onEvent: ((SpeakerHardwareEvent) -> Void)?

    private let session = AVAudioSession.sharedInstance()
    private let mix: SpeakerMix
    private var engine: AVAudioEngine?
    private var observers: [any NSObjectProtocol] = []
    private var volumeObservation: NSKeyValueObservation?

    private static let log = Logger(subsystem: "app.dozecam", category: "speaker")

    init(mix: SpeakerMix) {
        self.mix = mix
        observers = Self.observe(session: session, relay: Self.relay(to: self))
        volumeObservation = Self.observeVolume(of: session, relay: Self.relay(to: self))
    }

    isolated deinit {
        for observer in observers { NotificationCenter.default.removeObserver(observer) }
        volumeObservation?.invalidate()
        engine?.stop()
    }

    var outputVolume: Float { session.outputVolume }

    var isEngineRunning: Bool { engine?.isRunning ?? false }

    var isSessionOurs: Bool { Self.isOurs(session) }

    func activate() throws {
        try session.setCategory(.playback, mode: .default, options: [.mixWithOthers])
        try session.setActive(true)
    }

    func deactivate() {
        do {
            try session.setActive(false, options: [.notifyOthersOnDeactivation])
        } catch {
            Self.log.warning("audio session deactivation failed: \(error, privacy: .public)")
        }
    }

    func startEngine() throws {
        let engine = self.engine ?? Self.makeEngine(rendering: mix)
        self.engine = engine
        guard !engine.isRunning else { return }
        engine.prepare()
        try engine.start()
    }

    func stopEngine() {
        engine?.stop()
    }

    private func handle(_ event: SpeakerHardwareEvent) {
        if event == .mediaServicesReset {
            // Every audio object made before the reset is dead; the next start
            // builds a new engine.
            engine = nil
        }
        onEvent?(event)
    }

    // MARK: - Built outside the main actor (#58)

    /// Hands an event from any thread to the main actor, in posting order.
    private nonisolated static func relay(to hardware: SystemSpeakerHardware)
        -> @Sendable (SpeakerHardwareEvent) -> Void
    {
        { [weak hardware] event in
            DispatchQueue.main.async {
                MainActor.assumeIsolated { hardware?.handle(event) }
            }
        }
    }

    private nonisolated static func makeEngine(rendering mix: SpeakerMix) -> AVAudioEngine {
        let engine = AVAudioEngine()
        let format = AVAudioFormat(standardFormatWithSampleRate: SpeakerMix.sampleRate, channels: 1)!
        let source = AVAudioSourceNode(format: format, renderBlock: renderBlock(for: mix))
        engine.attach(source)
        engine.connect(source, to: engine.mainMixerNode, format: format)
        return engine
    }

    private nonisolated static func renderBlock(for mix: SpeakerMix) -> AVAudioSourceNodeRenderBlock {
        { _, _, frames, audioBufferList in
            let buffers = UnsafeMutableAudioBufferListPointer(audioBufferList)
            for buffer in buffers {
                guard let data = buffer.mData?.assumingMemoryBound(to: Float.self) else { continue }
                mix.render(into: data, frames: Int(frames))
            }
            return noErr
        }
    }

    private nonisolated static func observe(
        session: AVAudioSession, relay: @escaping @Sendable (SpeakerHardwareEvent) -> Void
    ) -> [any NSObjectProtocol] {
        let center = NotificationCenter.default
        return [
            center.addObserver(forName: AVAudioSession.interruptionNotification, object: session, queue: nil) {
                if let event = interruptionEvent($0.userInfo) { relay(event) }
            },
            center.addObserver(forName: AVAudioSession.routeChangeNotification, object: session, queue: nil) {
                if let event = routeChangeEvent($0.userInfo, session: session) { relay(event) }
            },
            center.addObserver(forName: AVAudioSession.mediaServicesWereResetNotification, object: session, queue: nil)
            { _ in relay(.mediaServicesReset) },
            center.addObserver(forName: .AVAudioEngineConfigurationChange, object: nil, queue: nil) { _ in
                relay(.engineStopped)
            },
        ]
    }

    private nonisolated static func observeVolume(
        of session: AVAudioSession, relay: @escaping @Sendable (SpeakerHardwareEvent) -> Void
    ) -> NSKeyValueObservation {
        session.observe(\.outputVolume, options: [.new]) { _, change in
            if let volume = change.newValue { relay(.outputVolumeChanged(volume)) }
        }
    }

    nonisolated static func interruptionEvent(_ userInfo: [AnyHashable: Any]?) -> SpeakerHardwareEvent? {
        guard let raw = userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt,
            let type = AVAudioSession.InterruptionType(rawValue: raw)
        else { return nil }
        switch type {
        case .began:
            return .interruptionBegan
        case .ended:
            let options = (userInfo?[AVAudioSessionInterruptionOptionKey] as? UInt).map {
                AVAudioSession.InterruptionOptions(rawValue: $0)
            }
            return .interruptionEnded(shouldResume: options?.contains(.shouldResume) ?? false)
        @unknown default:
            return nil
        }
    }

    private nonisolated static func isOurs(_ session: AVAudioSession) -> Bool {
        session.category == .playback && session.categoryOptions.contains(.mixWithOthers)
    }

    /// Only the device the sound was playing through going away is a loss: a
    /// new device, or an override, is not. A category change is ours to undo
    /// only when it left the session without playback-and-mixing.
    nonisolated static func routeChangeEvent(_ userInfo: [AnyHashable: Any]?, session: AVAudioSession)
        -> SpeakerHardwareEvent?
    {
        guard let raw = userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt,
            let reason = AVAudioSession.RouteChangeReason(rawValue: raw)
        else { return nil }
        switch reason {
        case .oldDeviceUnavailable:
            return .routeLost
        case .categoryChange:
            return isOurs(session) ? nil : .categoryChanged
        default:
            return nil
        }
    }
}

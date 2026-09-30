import Observation
import Synchronization
import os

/// The app's one owner of the speaker: the audio session, the always-running
/// engine, and the mix of every monitored room's sound. The iOS counterpart of
/// Android's `MediaAudioFocus`, and of the foreground service's hold on the
/// process (shared/spec/monitoring-lifecycle.md, "Staying alive").
///
/// **Always running while monitoring.** iOS keeps a locked app alive only
/// while it plays audio, so the engine runs from `start()` to `stop()` and
/// renders silence when nothing is aloud (#58). Which rooms are heard is
/// `setAloud`, which never stops the engine.
///
/// **One owner.** The spec's audio focus rules translate into session events
/// (shared/spec/alerts-and-sound-modes.md, "The speaker"):
/// - an interruption (a call, Siri, an alarm) is a loss for a moment: the mix
///   goes silent with the engine, and the speaker comes back when it ends,
///   with the rooms that were aloud;
/// - the output device going away (headphones out) is a loss for good: the mix
///   goes silent at once, before anyone reacts, and `.lost(.routeLost)` asks
///   the holders to write the sound mode back to off. The engine keeps
///   running, silently, because monitoring still needs it;
/// - an activation the system refuses, or a comeback after an interruption
///   that fails, is reported as lost too, and leaves the speaker `.failed`:
///   nothing is keeping the app alive then, which is a failure to announce
///   (#68). `start()` again retries.
///
/// Ducking has no counterpart: with `.mixWithOthers` another app's audio
/// plays alongside rather than interrupting.
///
/// Main actor, like the models that read it. The sinks it hands out are
/// written from libVLC's audio threads and read by the render thread.
@MainActor
@Observable
final class Speaker {
    enum Status: Equatable, Sendable {
        /// Not started, or stopped on exit: the session is released.
        case stopped
        /// Session active and engine rendering: the speaker is granted.
        case running
        /// Another app's audio has it for a moment.
        case interrupted
        /// The system refused it, or it could not be brought back.
        case failed(Loss)
    }

    /// Why the speaker was lost for good (or never had).
    enum Loss: Equatable, Sendable {
        /// The session would not activate or the engine would not start.
        case refused
        /// Coming back after an interruption or an engine restart failed.
        case resumeFailed
        /// The output device went away.
        case routeLost
    }

    enum Event: Equatable, Sendable {
        /// Silent for a moment; the aloud rooms come back with `resumed`.
        case interrupted
        case resumed
        /// Lost for good: write the sound mode back to off.
        case lost(Loss)
        case outputVolumeChanged(Float)
    }

    private(set) var status: Status = .stopped
    /// The media volume, 0 to 1. Listen mode's "heard" reads zero as the mix
    /// playing into nothing (shared/spec/alerts-and-sound-modes.md).
    private(set) var outputVolume: Float
    /// The rooms the mix plays out, as last chosen, while the speaker is
    /// granted; empty otherwise.
    private(set) var aloudCameraIds: Set<String> = []

    /// Whether Dozecam may make a sound this instant.
    var isGranted: Bool { status == .running }
    /// Media volume at zero: anything aloud is heard by nobody.
    var isMediaSilenced: Bool { outputVolume <= 0 }

    @ObservationIgnored let mix: SpeakerMix
    @ObservationIgnored private let hardware: any SpeakerHardware
    @ObservationIgnored private let events = SpeakerEvents()
    /// What `setAloud` last asked for, kept through an interruption so the
    /// same rooms come back.
    @ObservationIgnored private var wantedAloud: Set<String> = []

    private static let log = Logger(subsystem: "app.dozecam", category: "speaker")

    /// The app's speaker, on the real session and engine.
    static let shared: Speaker = {
        let mix = SpeakerMix()
        return Speaker(hardware: SystemSpeakerHardware(mix: mix), mix: mix)
    }()

    /// `mix` must be the one `hardware` renders.
    init(hardware: any SpeakerHardware, mix: SpeakerMix) {
        self.hardware = hardware
        self.mix = mix
        outputVolume = hardware.outputVolume
        hardware.onEvent = { [weak self] in self?.handle($0) }
    }

    // MARK: - Holding

    /// Activates the session and starts the engine; idempotent while running.
    /// False when the system refused, which is also reported as
    /// `.lost(.refused)`. Also the retry after a failure.
    @discardableResult
    func start() -> Bool {
        switch status {
        case .running:
            return true
        case .interrupted:
            // Comes back when the interruption ends; asking now would fail.
            return false
        case .stopped, .failed:
            break
        }
        if bringUp() {
            setStatus(.running)
            applyAloud()
            return true
        }
        fail(.refused)
        return false
    }

    /// Releases the speaker, as exit does: the engine stops, the session is
    /// deactivated so other apps may resume, and nothing is aloud.
    func stop() {
        guard status != .stopped else { return }
        setStatus(.stopped)
        wantedAloud = []
        applyAloud()
        hardware.stopEngine()
        hardware.deactivate()
    }

    /// Checks the speaker is what it claims, and repairs it: a running
    /// speaker whose engine was stopped behind its back, or whose session lost
    /// its mixing, is set up again. Something else in the process can do
    /// either without a notification, notably libVLC's own audio output for
    /// the viewer, which sets the session to unmixed playback while it plays
    /// and deactivates it when it stops (`VlcPlayerCore.applyMute`). Call it
    /// when the viewer's sound stops and when the app goes to the background.
    /// A failed speaker is retried.
    func reassert() {
        switch status {
        case .running:
            guard !hardware.isEngineRunning || !hardware.isSessionOurs else { return }
            Self.log.notice("speaker reasserted")
            if !bringUp() { fail(.resumeFailed) }
        case .failed:
            start()
        case .stopped, .interrupted:
            break
        }
    }

    // MARK: - The mix

    /// Where `cameraId`'s player writes its PCM. The same sink until removed.
    nonisolated func sink(for cameraId: String) -> SpeakerSink { mix.sink(for: cameraId) }

    /// For a camera no longer monitored.
    func removeSink(for cameraId: String) {
        mix.removeSink(for: cameraId)
        wantedAloud.remove(cameraId)
        applyAloud()
    }

    /// Chooses the rooms mixed out of the speaker; every other room's sound is
    /// thrown away as it arrives, so it builds no delay. Takes effect while
    /// the speaker is granted, and is kept through an interruption.
    func setAloud(_ cameraIds: Set<String>) {
        wantedAloud = cameraIds
        applyAloud()
    }

    private func applyAloud() {
        let aloud = status == .running ? wantedAloud : []
        mix.setAloud(aloud)
        if aloudCameraIds != aloud { aloudCameraIds = aloud }
    }

    // MARK: - Events

    /// Every event from now on, for as long as the stream is iterated.
    nonisolated func updates() -> AsyncStream<Event> { events.stream() }

    private func handle(_ event: SpeakerHardwareEvent) {
        switch event {
        case .interruptionBegan:
            guard status == .running else { return }
            Self.log.notice("speaker interrupted")
            setStatus(.interrupted)
            applyAloud()
            events.send(.interrupted)

        case .interruptionEnded(let shouldResume):
            guard status == .interrupted else { return }
            // Resumed whatever the hint says: monitoring lives only while the
            // engine runs. With `.mixWithOthers` this works from the
            // background (#58).
            Self.log.notice("speaker interruption ended (shouldResume: \(shouldResume, privacy: .public))")
            resume()

        case .engineStopped:
            // The engine stops itself on a configuration change; a running
            // speaker starts it again. An interrupted one waits for the end.
            guard status == .running else { return }
            do {
                try hardware.startEngine()
            } catch {
                Self.log.error("engine restart failed: \(error, privacy: .public)")
                fail(.resumeFailed)
            }

        case .mediaServicesReset:
            guard status == .running || status == .interrupted else { return }
            Self.log.notice("media services reset")
            resume()

        case .categoryChanged:
            guard status == .running else { return }
            do {
                try hardware.activate()
            } catch {
                Self.log.error("could not restore the audio session: \(error, privacy: .public)")
            }

        case .routeLost:
            // Silent at once: the rooms must not move onto the phone's own
            // speaker while the holders react. Reported even while stopped:
            // the viewer's own sound goes out through the same route.
            wantedAloud = []
            applyAloud()
            events.send(.lost(.routeLost))

        case .outputVolumeChanged(let volume):
            guard volume != outputVolume else { return }
            outputVolume = volume
            events.send(.outputVolumeChanged(volume))
        }
    }

    private func resume() {
        if bringUp() {
            setStatus(.running)
            applyAloud()
            events.send(.resumed)
        } else {
            fail(.resumeFailed)
        }
    }

    private func bringUp() -> Bool {
        do {
            try hardware.activate()
            try hardware.startEngine()
        } catch {
            Self.log.error("speaker could not start: \(error, privacy: .public)")
            return false
        }
        // The session reports a volume only while active.
        let volume = hardware.outputVolume
        if volume != outputVolume {
            outputVolume = volume
            events.send(.outputVolumeChanged(volume))
        }
        return true
    }

    private func fail(_ loss: Loss) {
        hardware.stopEngine()
        setStatus(.failed(loss))
        wantedAloud = []
        applyAloud()
        events.send(.lost(loss))
    }

    private func setStatus(_ next: Status) {
        if status != next { status = next }
    }
}

extension Speaker {
    /// Headphones unplugged or another route gone: the viewer's cue to say
    /// its sound went off. Reported even while the speaker is stopped.
    nonisolated func losses() -> AsyncStream<Void> { events.losses() }

    /// Whether anything follows `losses()`, for tests that must not unplug
    /// before a listener is there to hear it.
    nonisolated var isObservedForLosses: Bool { events.hasLossListeners }
}

/// The speaker's events, to every stream that asked. Lock-protected so
/// streams can be opened from any isolation. Unbounded: these are events, not
/// state, and each is rare.
private final class SpeakerEvents: Sendable {
    private struct Subscribers {
        var events: [UInt64: AsyncStream<Speaker.Event>.Continuation] = [:]
        var losses: [UInt64: AsyncStream<Void>.Continuation] = [:]
        var nextId: UInt64 = 0
    }

    private let subscribers = Mutex(Subscribers())

    deinit {
        subscribers.withLock { subscribers in
            for continuation in subscribers.events.values { continuation.finish() }
            for continuation in subscribers.losses.values { continuation.finish() }
        }
    }

    func stream() -> AsyncStream<Speaker.Event> {
        let (stream, continuation) = AsyncStream.makeStream(of: Speaker.Event.self)
        subscribers.withLock { subscribers in
            let id = subscribers.nextId
            subscribers.nextId += 1
            subscribers.events[id] = continuation
            continuation.onTermination = { [weak self] _ in
                self?.subscribers.withLock { _ = $0.events.removeValue(forKey: id) }
            }
        }
        return stream
    }

    func losses() -> AsyncStream<Void> {
        let (stream, continuation) = AsyncStream.makeStream(of: Void.self)
        subscribers.withLock { subscribers in
            let id = subscribers.nextId
            subscribers.nextId += 1
            subscribers.losses[id] = continuation
            continuation.onTermination = { [weak self] _ in
                self?.subscribers.withLock { _ = $0.losses.removeValue(forKey: id) }
            }
        }
        return stream
    }

    var hasLossListeners: Bool { subscribers.withLock { !$0.losses.isEmpty } }

    func send(_ event: Speaker.Event) {
        subscribers.withLock { subscribers in
            for continuation in subscribers.events.values { continuation.yield(event) }
            if event == .lost(.routeLost) {
                for continuation in subscribers.losses.values { continuation.yield() }
            }
        }
    }
}

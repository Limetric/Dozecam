import Foundation
import Synchronization
import VLCKit
import os

/// A decoded audio buffer's level and its time, in milliseconds on
/// `ContinuousScheduler`'s monotonic clock: the input the sound detector is
/// fed, one per buffer (shared/spec/alerts-and-sound-modes.md, "The detector").
///
/// The time is when the buffer is due to be heard on the stream's own
/// timeline (libVLC's play timestamp), not when the callback ran: libVLC hands
/// audio over in bursts (the testbed's arrive as ~0.7 s of audio within a few
/// milliseconds, every ~0.7 s), and stamping a burst with one instant would
/// squeeze its sound into nothing and stretch the silence after it, so the
/// detector's sustain and re-arm would be measured on the network's rhythm
/// instead of the room's. It can therefore run up to `AudioOnlyPlayer.maxLeadMs`
/// ahead of now. Never earlier than a previous sample of the same player, across
/// reconnects too.
struct LevelSample: Equatable, Sendable {
    var rms: Float
    var atMs: Int64
}

/// What a monitor's audio player reports.
enum AudioPlayerEvent: Equatable, Sendable {
    /// libVLC says it is playing. Not a buffer: a player reports playing
    /// before any sample is decoded, and on a stream it cannot decode at all
    /// (shared/spec/connection-state.md, "The monitor").
    case playing
    /// The stream ended, on its own or because the publisher went away.
    case stopped
    case error
    /// Buffers decoded since the last batch, oldest first. The first batch of
    /// a session is the proof that audio decodes on it.
    case levels([LevelSample])
}

/// One camera's audio for the monitor. Main actor, like the monitor that
/// drives it; implementations hop their callbacks there before `onEvent`.
@MainActor
protocol AudioPlayer: AnyObject {
    var onEvent: ((AudioPlayerEvent) -> Void)? { get set }
    /// Starts a new session on `source`, ending any current one: nothing the
    /// old session decoded is reported after this.
    func play(_ source: StreamSource)
    func stop()
    /// Unusable afterwards.
    func release()
}

/// Wake-on-sound's input on iOS: an audio-only libVLC player (#59), the
/// counterpart of Android's `CameraAudioMonitor` ExoPlayer with its tee.
///
/// libVLC's C API, not VLCKit: only it can hand over decoded PCM
/// (`libvlc_audio_set_callbacks`), asked for as 32-bit float, 48 kHz mono. The
/// callback writes each buffer into the camera's `SpeakerSink` (the mix decides
/// whether anyone hears it) and measures its level, so no system audio output
/// is ever opened here and the players never touch the audio session. Video is
/// never decoded (`:no-video`), whichever transport carries it. It runs on the
/// runtime's shared `libvlc_instance_t`, and so with its RTSP-over-TCP and
/// network caching options.
///
/// **One libVLC player per session**, like `VlcPlayerCore`: a reconnect builds
/// a new one, and a retired one is stopped and released on a background queue.
/// Releasing joins libVLC's threads, so it must never run on one of them (a
/// callback) or on the main thread, which they may be waiting for.
///
/// **Threads.** The audio callback runs on libVLC's audio thread, the state
/// callback on its player thread. Both are file-scope functions (#58). Levels
/// are batched and handed to the main actor at most every `batchInterval`,
/// each carrying its own time (`LevelSample`), so the detector sees per-buffer
/// timing without a main-queue hop per buffer.
///
/// Must be `release()`d: a libVLC player still running when this goes away
/// would play on, unheard, until the process ends.
@MainActor
final class AudioOnlyPlayer: AudioPlayer {
    typealias Connect = LivestreamVideoPlayerController.Connect

    var onEvent: ((AudioPlayerEvent) -> Void)?

    /// How long levels wait to be handed to the main actor together.
    nonisolated static let batchInterval: Duration = .milliseconds(30)
    /// The furthest ahead of now a sample's time may be put: libVLC's own
    /// buffering (network caching and the burst it holds) is well within it,
    /// and a play timestamp beyond it is not believed.
    nonisolated static let maxLeadMs: Int64 = 2_000
    nonisolated static let sampleRate = Int(SpeakerMix.sampleRate)

    private let instance: OpaquePointer
    private let sink: SpeakerSink?
    private let connect: Connect?
    private let now: @Sendable () -> Int64
    /// Shared by every session, so times never go back across a reconnect.
    private let stamps = LevelStamps()

    private var current: AudioOnlySession?
    private var negotiation: Task<Void, Never>?
    private var feeding: Task<Void, Never>?
    /// Bumped by every `play` and `stop`, so a late result of an abandoned
    /// session cannot act on the current one.
    private var session = 0
    private var released = false

    private static let log = Logger(subsystem: "app.dozecam", category: "monitor")
    /// Where retired players are stopped and released.
    private static let retirement = DispatchQueue(label: "app.dozecam.audio.retirement")

    /// `sink` receives every decoded sample; nil measures only. `connect`
    /// negotiates a Protect livestream; without it a `.livestream` source
    /// fails as an error.
    init(
        runtime: VlcRuntime = .shared, sink: SpeakerSink?, connect: Connect? = nil,
        now: @escaping @Sendable () -> Int64 = { ContinuousScheduler.monotonicNowMs }
    ) {
        instance = OpaquePointer(runtime.library.instance)
        self.sink = sink
        self.connect = connect
        self.now = now
    }

    func play(_ source: StreamSource) {
        guard !released else { return }
        stop()
        let session = self.session
        switch source {
        case .rtsp(let url):
            guard let media = libvlc_media_new_location(url) else {
                Self.log.error("libVLC refused the RTSP location")
                fail(session)
                return
            }
            start(media, pipe: nil, session: session)
        case .livestream(let cameraId, let channel):
            guard let connect else {
                fail(session)
                return
            }
            negotiation = Task { [weak self] in
                let connection: ProtectLivestreamProvider.Connection
                do {
                    connection = try await connect(cameraId, channel)
                } catch {
                    guard !Task.isCancelled else { return }  // an abandoned attempt is not a failure
                    Self.log.warning("monitor livestream negotiation failed: \(error, privacy: .public)")
                    self?.fail(session)
                    return
                }
                guard !Task.isCancelled else { return }
                self?.startLivestream(connection, session: session)
            }
        }
    }

    func stop() {
        session += 1
        negotiation?.cancel()
        negotiation = nil
        feeding?.cancel()
        feeding = nil
        retire()
    }

    func release() {
        guard !released else { return }
        stop()
        released = true
        onEvent = nil
    }

    // MARK: - Sessions

    private func startLivestream(_ connection: ProtectLivestreamProvider.Connection, session: Int) {
        guard session == self.session else { return }
        let pipe = LivestreamPipe()
        guard let media = LivestreamMedia.makeDescriptor(reading: pipe) else {
            Self.log.error("libVLC refused the livestream media")
            fail(session)
            return
        }
        feeding = LivestreamVideoPlayerController.feed(
            connection, into: pipe, onFailure: Self.failureHandler(for: self, session: session))
        start(media, pipe: pipe, session: session)
    }

    /// Plays `media` (whose reference this takes) on a new libVLC player.
    private func start(_ media: OpaquePointer, pipe: LivestreamPipe?, session: Int) {
        libvlc_media_add_option(media, ":no-video")
        let context = AudioOnlySession(
            sink: sink, pipe: pipe, stamps: stamps, now: now, deliver: Self.relay(to: self, session: session))
        let opaque = Unmanaged.passRetained(context).toOpaque()
        guard let player = libvlc_media_player_new(instance, audioOnlyPlayerCallbacks, opaque) else {
            libvlc_media_release(media)
            Unmanaged<AudioOnlySession>.fromOpaque(opaque).release()
            Self.log.error("libVLC refused an audio player")
            fail(session)
            return
        }
        libvlc_media_player_set_media(player, media)
        libvlc_media_release(media)
        libvlc_audio_set_callbacks(player, audioOnlyPlay, nil, nil, audioOnlyFlush, nil, opaque)
        libvlc_audio_set_format(player, "FL32", UInt32(Self.sampleRate), 1)
        context.player = player
        current = context
        if libvlc_media_player_play(player) != 0 {
            Self.log.error("libVLC would not start the audio player")
            fail(session)
        }
    }

    /// Ends the current libVLC player: its callbacks are silenced at once, the
    /// livestream pipe is closed so a blocked read returns, and the player is
    /// stopped and released on the retirement queue, after which the context
    /// the callbacks point at is let go.
    private func retire() {
        guard let context = current else { return }
        current = nil
        context.retire()
        guard let player = context.player else { return }
        let retiring = RetiringAudioPlayer(player: player, context: context)
        Self.retirement.async { retiring.release() }
    }

    private func fail(_ session: Int) {
        guard session == self.session else { return }
        onEvent?(.error)
    }

    fileprivate func receive(_ event: AudioPlayerEvent, session: Int) {
        guard session == self.session, !released else { return }
        onEvent?(event)
    }

    // MARK: - Built outside the main actor (#58)

    private nonisolated static func relay(to player: AudioOnlyPlayer, session: Int)
        -> @Sendable (AudioPlayerEvent) -> Void
    {
        { [weak player] event in
            DispatchQueue.main.async {
                MainActor.assumeIsolated { player?.receive(event, session: session) }
            }
        }
    }

    /// The socket dying is an error straight away, not when the demuxer next
    /// runs dry, so a failure while libVLC idles still reconnects.
    private nonisolated static func failureHandler(for player: AudioOnlyPlayer, session: Int)
        -> @Sendable () -> Void
    {
        { [weak player] in
            DispatchQueue.main.async {
                MainActor.assumeIsolated { player?.fail(session) }
            }
        }
    }
}

/// What libVLC's callbacks for one player session point at. Created on the
/// main actor, then read from libVLC's threads: every mutable part is atomic or
/// locked. Its one retained reference, taken as the player is made, is given
/// back only after the player has been released, so no callback can outlive
/// it.
final class AudioOnlySession: @unchecked Sendable {
    let sink: SpeakerSink?
    let pipe: LivestreamPipe?
    private let stamps: LevelStamps
    private let now: @Sendable () -> Int64
    private let deliver: @Sendable (AudioPlayerEvent) -> Void
    /// Written once on the main actor before playback starts; read by the
    /// retirement queue.
    var player: OpaquePointer?

    private let retired = Atomic<Bool>(false)
    private struct Pending {
        var levels: [LevelSample] = []
        var flushScheduled = false
    }
    private let pending = Mutex(Pending())
    /// Levels kept while the main thread is busy; older ones are dropped, as
    /// the watchdog and the detector want the present.
    private static let maxPending = 512

    init(
        sink: SpeakerSink?, pipe: LivestreamPipe?, stamps: LevelStamps, now: @escaping @Sendable () -> Int64,
        deliver: @escaping @Sendable (AudioPlayerEvent) -> Void
    ) {
        self.sink = sink
        self.pipe = pipe
        self.stamps = stamps
        self.now = now
        self.deliver = deliver
    }

    var isRetired: Bool { retired.load(ordering: .relaxed) }

    func retire() {
        retired.store(true, ordering: .relaxed)
        pipe?.close()
    }

    /// libVLC's audio thread: one decoded buffer, due to be heard at `pts`
    /// on libVLC's clock (microseconds; 0 when unknown).
    func play(_ samples: UnsafeBufferPointer<Float>, pts: Int64) {
        guard !isRetired else { return }
        sink?.write(samples)
        let leadMs = pts > 0 ? min(max((pts - libvlc_clock()) / 1_000, 0), AudioOnlyPlayer.maxLeadMs) : 0
        let sample = LevelSample(rms: PcmRms.of(samples), atMs: stamps.next(now() + leadMs))
        let schedule = pending.withLock { pending in
            if pending.levels.count >= Self.maxPending { pending.levels.removeFirst() }
            pending.levels.append(sample)
            guard !pending.flushScheduled else { return false }
            pending.flushScheduled = true
            return true
        }
        guard schedule else { return }
        let interval = AudioOnlyPlayer.batchInterval.components
        let delay = Double(interval.seconds) + Double(interval.attoseconds) / 1e18
        DispatchQueue.global(qos: .userInitiated).asyncAfter(deadline: .now() + delay) { [self] in flush() }
    }

    private func flush() {
        let levels = pending.withLock { pending in
            pending.flushScheduled = false
            defer { pending.levels.removeAll(keepingCapacity: true) }
            return pending.levels
        }
        guard !isRetired, !levels.isEmpty else { return }
        deliver(.levels(levels))
    }

    /// libVLC's player thread.
    func stateChanged(_ state: libvlc_state_t) {
        guard !isRetired else { return }
        switch state {
        case libvlc_Playing: deliver(.playing)
        case libvlc_Error: deliver(.error)
        case libvlc_Stopped: deliver(.stopped)
        default: break
        }
    }
}

/// Keeps a player's sample times from going back: a reconnect's first
/// buffers can be due sooner than the last ones of the connection before.
final class LevelStamps: Sendable {
    private let last = Mutex<Int64>(.min)

    func next(_ atMs: Int64) -> Int64 {
        last.withLock { last in
            last = max(last, atMs)
            return last
        }
    }
}

/// Carries a retired player to the retirement queue. Unchecked: nothing
/// touches the player after the hand-off but its stop and release.
private final class RetiringAudioPlayer: @unchecked Sendable {
    private let player: OpaquePointer
    private let context: AudioOnlySession

    init(player: OpaquePointer, context: AudioOnlySession) {
        self.player = player
        self.context = context
    }

    /// On the retirement queue only. Release stops the player and joins its
    /// threads, so once it returns no callback can reach the context.
    func release() {
        libvlc_media_player_stop_async(player)
        libvlc_media_player_release(player)
        Unmanaged.passUnretained(context).release()
    }
}

// libVLC calls these on its own threads: file-scope functions, so they carry
// no actor isolation (#58).

private func audioOnlyPlay(
    _ opaque: UnsafeMutableRawPointer?, _ samples: UnsafeRawPointer?, _ count: UInt32, _ pts: Int64
) {
    guard let opaque, let samples else { return }
    let context = Unmanaged<AudioOnlySession>.fromOpaque(opaque).takeUnretainedValue()
    // FL32 mono: `count` samples of one float each.
    context.play(UnsafeBufferPointer(start: samples.assumingMemoryBound(to: Float.self), count: Int(count)), pts: pts)
}

private func audioOnlyFlush(_ opaque: UnsafeMutableRawPointer?, _ pts: Int64) {}

private func audioOnlyStateChanged(_ opaque: UnsafeMutableRawPointer?, _ state: libvlc_state_t) {
    guard let opaque else { return }
    Unmanaged<AudioOnlySession>.fromOpaque(opaque).takeUnretainedValue().stateChanged(state)
}

/// libVLC keeps the pointer for as long as any player made with it lives, so
/// it is allocated once and never freed.
nonisolated(unsafe) private let audioOnlyPlayerCallbacks: UnsafeMutablePointer<libvlc_media_player_cbs> = {
    let callbacks = UnsafeMutablePointer<libvlc_media_player_cbs>.allocate(capacity: 1)
    var value = libvlc_media_player_cbs()
    value.version = 0
    value.on_state_changed = audioOnlyStateChanged
    callbacks.initialize(to: value)
    return callbacks
}()

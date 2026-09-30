import Foundation
import Observation
import os

/// One camera's share of the monitor: an audio-only session over the camera's
/// transports, with its own `PlaybackWatchdog` so a camera that drops off the
/// network reconnects on its own without disturbing the others. The port of
/// Android's `CameraAudioMonitor`.
///
/// **Transports.** `transports` is the camera's plain RTSP stream first and
/// the Protect livestream after it (Android's `MonitorTransports`), because
/// "cannot be decoded" is not a thing a stream announces: it looks exactly
/// like a quiet room. So a transport that has never yielded a single audio
/// buffer after several restarts is abandoned for the next one
/// (`TransportFallback`), rather than reconnected to all night
/// (shared/spec/connection-state.md, "The monitor's transports").
///
/// **Liveness.** The watchdog's rules with decoded audio buffers as the
/// frames: a room whose audio stops arriving for 2.5 s is reconnected, with
/// the same backoff as the viewer's cameras (Android's monitor uses the
/// watchdog's defaults too). libVLC reporting "playing" is not a frame: it
/// says so before any sample is decoded, and on streams it can never decode
/// (shared/spec/connection-state.md, "The monitor"). This follows
/// `VlcPlayerCore`, which also waits for the first real picture, rather than
/// Android, whose ExoPlayer is trusted to be playing only once it renders.
///
/// **Level.** Unknown (nil) until a buffer decodes on the current connection,
/// never 0, and forgotten as soon as the connection stops being live
/// (shared/spec/alerts-and-sound-modes.md, "The detector"). Audible is live
/// with a level: Android's `CameraMonitorState.isAudible`.
///
/// Whether the room is heard is not decided here: its player writes every
/// sample into its `SpeakerSink`, and `Speaker.setAloud` chooses.
///
/// Main actor; its player hops libVLC's callbacks there.
@MainActor
@Observable
final class CameraAudioMonitor {
    let cameraId: String
    /// Ways to listen to this camera, best first.
    let transports: [StreamSource]

    /// The latest level measured on the current, live connection: the peak of
    /// the latest batch of buffers. Nil while unknown.
    var level: Float? { connection == .live ? lastLevel : nil }
    /// Live, with a buffer decoded on this connection: what listen mode may
    /// call a room it is playing (shared/spec/alerts-and-sound-modes.md,
    /// "Listen mode").
    var isAudible: Bool { level != nil }
    var connection: ConnectionState { watchdog.state }
    /// Wall-clock time of the last buffer, for "last heard … ago".
    var lastAudioAt: Date? { watchdog.lastFrameAt }
    /// Which of `transports` is being listened on.
    private(set) var transportIndex = 0

    /// Every batch of decoded buffers' levels, oldest first, each with the
    /// time it was decoded: the sound detector's input. Buffers from a
    /// transport or connection already left are never delivered.
    @ObservationIgnored var onLevels: (([LevelSample]) -> Void)?

    var transport: StreamSource? { transports.indices.contains(transportIndex) ? transports[transportIndex] : nil }

    /// Monotonic time of the last buffer on the current connection, like
    /// Android's `lastAudioAtMs`.
    private(set) var lastAudioAtMs: Int64?

    private var lastLevel: Float?
    @ObservationIgnored private var fallback: TransportFallback
    @ObservationIgnored private let player: any AudioPlayer
    @ObservationIgnored private var watchdog: PlaybackWatchdog!
    @ObservationIgnored private var running = false
    @ObservationIgnored private var stopped = false

    private static let log = Logger(subsystem: "app.dozecam", category: "monitor")

    /// `makePlayer` is called once; the monitor owns what it returns.
    init(
        cameraId: String,
        transports: [StreamSource],
        scheduler: any MonotonicScheduler = ContinuousScheduler.shared,
        watchdogConfig: PlaybackWatchdog.Config = PlaybackWatchdog.Config(),
        makePlayer: () -> any AudioPlayer
    ) {
        self.cameraId = cameraId
        self.transports = transports
        fallback = TransportFallback(transportCount: transports.count)
        player = makePlayer()
        watchdog = PlaybackWatchdog(config: watchdogConfig, scheduler: scheduler) { [weak self] in self?.restart() }
        player.onEvent = { [weak self] in self?.receive($0) }
    }

    /// Starts listening on the best transport. Once only; a camera with no
    /// transport is never started (the caller reports it as unmonitorable).
    func start() {
        guard !running, !stopped, let transport else { return }
        running = true
        watchdog.start()
        player.play(transport)
    }

    /// Ends the monitor for good and releases its player.
    func stop() {
        guard !stopped else { return }
        stopped = true
        running = false
        watchdog.stop()
        player.release()
        forgetConnection()
    }

    func onNetworkAvailable() { watchdog.onNetworkAvailable() }

    func onNetworkLost() { watchdog.onNetworkLost() }

    // MARK: - The player

    private func receive(_ event: AudioPlayerEvent) {
        guard running else { return }
        switch event {
        case .levels(let levels):
            guard let last = levels.last else { return }
            // Proof this transport works, which is what makes abandoning a
            // transport that never gets here safe.
            fallback.onAudioDecoded()
            lastLevel = levels.lazy.map(\.rms).max()
            lastAudioAtMs = last.atMs
            watchdog.onPlayerEvent(.timeChanged(milliseconds: last.atMs))
            onLevels?(levels)
        case .error:
            watchdog.onPlayerEvent(.error)
        case .stopped:
            watchdog.onPlayerEvent(.stopped)
        case .playing:
            // Not a buffer; see the type's comment.
            break
        }
    }

    /// The watchdog's reconnect: a new connection, on the next transport if
    /// this one has never decoded anything after several tries. Decided here,
    /// as the restart is made, rather than when the watchdog schedules one: a
    /// stream that recovers during its backoff stays where it is.
    private func restart() {
        guard running else { return }
        considerFallback()
        forgetConnection()
        guard let transport else { return }
        player.play(transport)
    }

    /// Announced, because a monitor quietly changing how it listens to a
    /// nursery is something the logs should be able to explain afterwards.
    private func considerFallback() {
        let abandoned = transport
        guard fallback.onRestart() else { return }
        transportIndex = fallback.index
        Self.log.notice(
            "\(self.cameraId, privacy: .private): no audio over \(Self.label(abandoned), privacy: .public); falling back to \(Self.label(self.transport), privacy: .public)"
        )
    }

    /// A new connection knows nothing yet.
    private func forgetConnection() {
        lastLevel = nil
        lastAudioAtMs = nil
    }

    private static func label(_ source: StreamSource?) -> String {
        switch source {
        case .rtsp: "RTSP"
        case .livestream: "the Protect livestream"
        case nil: "nothing"
        }
    }
}

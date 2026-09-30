import Foundation
import Observation

/// Frame-level connection watchdog, the port of Android's `PlaybackWatchdog`.
/// Feed it player and network events; it detects stalls (no frame within
/// `Config.stallTimeoutMs`), drives reconnect attempts with capped exponential
/// backoff, and reports honest state: a frozen frame must never pretend to be
/// live (shared/spec/connection-state.md).
///
/// Android runs this as a coroutine reading a channel; here it is a state
/// machine on the main actor whose only waits are timers on a
/// `MonotonicScheduler`. Inputs are handled in arrival order, one at a time:
/// one that arrives while another is being handled (a player echoing `stopped`
/// from inside the restart this asked for) waits its turn, as it would in the
/// channel.
@MainActor
@Observable
final class PlaybackWatchdog {
    /// The defaults are the shared rule in
    /// `shared/fixtures/playback-watchdog/timings.json`.
    struct Config: Equatable, Sendable {
        var stallTimeoutMs: Int64 = 2_500
        var connectTimeoutMs: Int64 = 5_000
        var initialBackoffMs: Int64 = 500
        var maxBackoffMs: Int64 = 4_000

        /// The wait before reconnect attempt `attempt` (1-based): doubling from
        /// the initial backoff up to the cap.
        func backoffMs(forAttempt attempt: Int) -> Int64 {
            let shift = Int64(min(max(attempt - 1, 0), 20))
            return min(initialBackoffMs << shift, maxBackoffMs)
        }
    }

    private(set) var state: ConnectionState = .connecting
    /// Wall-clock time of the last frame, only for the user-facing "last frame
    /// … ago"; every deadline is on the scheduler's monotonic clock.
    private(set) var lastFrameAt: Date?

    @ObservationIgnored let config: Config
    @ObservationIgnored private let onReconnect: @MainActor () -> Void
    @ObservationIgnored private let wallClock: () -> Date
    @ObservationIgnored private let scheduler: any MonotonicScheduler

    private enum Input {
        case player(PlayerEvent)
        case networkUp
        case networkDown
        case video(enabled: Bool)
        case timer
    }

    @ObservationIgnored private var running = false
    @ObservationIgnored private var inbox: [Input] = []
    @ObservationIgnored private var draining = false
    @ObservationIgnored private var timer: ScheduledAction?
    @ObservationIgnored private var timerAt: Int64?

    // The run's state: Android's locals in `run()`, reset by every `start()`.
    @ObservationIgnored private var attempts = 0
    @ObservationIgnored private var networkUp = true
    /// True between issuing a reconnect and seeing frames; teardown echoes
    /// (`stopped` from the old session) are ignored in this window.
    @ObservationIgnored private var awaitingRecovery = false
    /// Whether a picture is still expected. Nothing is timed while it is not:
    /// the frames every deadline waits for are exactly what a dropped video
    /// track stops producing. Nothing is restarted either: a stream with no
    /// video track cannot say whether a restart worked, so a failure is
    /// remembered (`brokeWhileWarm`) and settled when the picture is wanted
    /// again.
    @ObservationIgnored private var videoOn = true
    @ObservationIgnored private var brokeWhileWarm = false
    /// The stream's video cannot be decoded here: no frame is ever due, and
    /// nothing that looks like one is believed.
    @ObservationIgnored private var undecodable = false
    /// When the current phase (initial connect, live stall watch, reconnect
    /// attempt) is declared failed. Only frames and phase transitions move it:
    /// `buffering` must never push it out.
    @ObservationIgnored private var deadline: Int64?
    /// Set while a reconnect waits out its backoff: when it goes ahead.
    @ObservationIgnored private var backoffUntil: Int64?

    init(
        config: Config = Config(),
        scheduler: any MonotonicScheduler,
        wallClock: @escaping () -> Date = Date.init,
        onReconnect: @escaping @MainActor () -> Void
    ) {
        self.config = config
        self.scheduler = scheduler
        self.wallClock = wallClock
        self.onReconnect = onReconnect
    }

    func onPlayerEvent(_ event: PlayerEvent) { receive(.player(event)) }
    func onNetworkAvailable() { receive(.networkUp) }
    func onNetworkLost() { receive(.networkDown) }

    /// Says the picture is wanted again; see `videoOn`.
    func onVideoEnabled() { receive(.video(enabled: true)) }
    /// Says this stream is kept connected but not shown, so no frames are due.
    func onVideoDisabled() { receive(.video(enabled: false)) }

    /// Begins a session in connecting. Events that arrived while stopped
    /// describe a session that no longer exists, and were dropped.
    func start() {
        guard !running else { return }
        running = true
        inbox.removeAll()
        attempts = 0
        networkUp = true
        awaitingRecovery = false
        videoOn = true
        brokeWhileWarm = false
        undecodable = false
        backoffUntil = nil
        deadline = scheduler.nowMs + config.connectTimeoutMs
        setState(.connecting)
        armTimer()
    }

    func stop() {
        running = false
        inbox.removeAll()
        cancelTimer()
    }

    // MARK: - Inputs

    private func receive(_ input: Input) {
        guard running else { return }
        inbox.append(input)
        guard !draining else { return }
        draining = true
        defer { draining = false }
        while running, !inbox.isEmpty {
            let next = inbox.removeFirst()
            if backoffUntil != nil {
                handleDuringBackoff(next)
            } else {
                handle(next)
            }
        }
        if running { armTimer() }
    }

    private func handle(_ input: Input) {
        switch input {
        case .timer:
            // A stall while live, or a connect attempt that hung.
            if let deadline, scheduler.nowMs >= deadline { attemptReconnect() }

        case .player(let event):
            switch event {
            case .playing, .timeChanged:
                if !videoOn || undecodable {
                    // Nothing is painting, so nothing here is a frame: the
                    // audio clock ticks on for a camera nobody is watching,
                    // or over a picture this device cannot decode.
                } else if networkUp {
                    markLive()
                } else {
                    // Buffered frames trickle in after network loss; they date
                    // the picture but never repaint an offline tile as live.
                    lastFrameAt = wallClock()
                }
            case .error:
                if !videoOn {
                    brokeWhileWarm = true
                } else if networkUp {
                    attemptReconnect()
                } else {
                    setState(.offline)
                }
            case .stopped:
                if !videoOn {
                    brokeWhileWarm = true
                } else if awaitingRecovery {
                    // Our own restart's teardown echoing back.
                } else if networkUp {
                    attemptReconnect()
                } else {
                    setState(.offline)
                }
            case .unsupportedCodec:
                // No frame will ever come, and retrying cannot make the codec
                // decodable: stop waiting for one. Errors, stops and the
                // network coming back still reconnect, and each new session
                // says the same again.
                undecodable = true
                deadline = nil
            case .buffering, .videoAspect:
                // Not frames, and say nothing about whether one is coming.
                break
            }

        case .networkUp:
            networkUp = true
            if !videoOn {
                // A camera nobody is watching does not get the network back to
                // itself ahead of the one being looked at.
                brokeWhileWarm = true
            } else if state == .live || state == .connecting {
                // Nothing to repair.
            } else {
                attempts = 0
                attemptReconnect(immediate: true)
            }

        case .networkDown:
            networkUp = false
            setState(.offline)
            deadline = nil

        case .video(let enabled):
            videoOn = enabled
            // The decoder went away with the track: whatever the stream was
            // doing before, it is not painting now.
            if videoOn, state == .live { setState(.connecting) }
            if !videoOn {
                // A fresh warm spell: nothing has gone wrong in it yet.
                brokeWhileWarm = false
                deadline = nil
            } else if !networkUp {
                // Nothing is coming until the network is back; offline says so.
                deadline = nil
            } else if brokeWhileWarm {
                brokeWhileWarm = false
                attempts = 0
                attemptReconnect(immediate: true)
            } else if undecodable {
                // Still no picture to wait for.
                deadline = nil
            } else {
                // The first-frame allowance, not the stall one: the decoder
                // cannot paint until the stream's next keyframe.
                deadline = scheduler.nowMs + config.connectTimeoutMs
            }
        }
    }

    /// Waiting out a backoff, but responsive: frames resuming cancel the
    /// restart, the network dropping or the camera going out of view abandon
    /// it, and stale errors from the failing session are ignored.
    private func handleDuringBackoff(_ input: Input) {
        switch input {
        case .timer:
            guard let backoffUntil, scheduler.nowMs >= backoffUntil else { return }
            self.backoffUntil = nil
            reconnectNow()
        case .player(let event):
            switch event {
            case .playing, .timeChanged:
                guard !undecodable else { break }
                backoffUntil = nil
                markLive()  // recovered on its own; skip the restart
            default:
                break
            }
        case .networkDown:
            backoffUntil = nil
            networkUp = false
            setState(.offline)
            deadline = nil
        case .networkUp:
            break  // already about to reconnect
        case .video(let enabled):
            videoOn = enabled
            if !enabled {
                // Carrying on would leave a reconnect in flight for a picture
                // nobody is waiting for; settled when it is wanted again.
                backoffUntil = nil
                brokeWhileWarm = true
                deadline = nil
            }
        }
    }

    // MARK: - Transitions

    private func markLive() {
        lastFrameAt = wallClock()
        setState(.live)
        attempts = 0
        awaitingRecovery = false
        deadline = videoOn ? scheduler.nowMs + config.stallTimeoutMs : nil
    }

    private func attemptReconnect(immediate: Bool = false) {
        attempts += 1
        setState(.reconnecting(attempt: attempts))
        if immediate {
            reconnectNow()
        } else {
            backoffUntil = scheduler.nowMs + config.backoffMs(forAttempt: attempts)
        }
    }

    /// Only ever reached with the picture wanted, so there are frames coming
    /// to judge the attempt by.
    private func reconnectNow() {
        onReconnect()
        awaitingRecovery = true
        deadline = scheduler.nowMs + config.connectTimeoutMs
    }

    /// Assigned only on change: `live` is re-asserted on every frame, and each
    /// assignment would otherwise redraw whatever reads it.
    private func setState(_ next: ConnectionState) {
        if state != next { state = next }
    }

    /// Keeps one timer, no later than the next deadline. A deadline that moved
    /// later (every frame pushes the stall deadline out) leaves the timer
    /// where it is: when it fires early it finds nothing due and is set again,
    /// so a live stream costs one timer per stall period, not one per frame.
    private func armTimer() {
        guard let fireAt = backoffUntil ?? deadline else {
            cancelTimer()
            return
        }
        if timer != nil, let timerAt, timerAt <= fireAt { return }
        cancelTimer()
        timerAt = fireAt
        timer = scheduler.schedule(at: fireAt) { [weak self] in
            guard let self else { return }
            timer = nil
            timerAt = nil
            receive(.timer)
        }
    }

    private func cancelTimer() {
        timer?.cancel()
        timer = nil
        timerAt = nil
    }
}

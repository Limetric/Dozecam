/// The alarm's life over time, decided without playing a note: the timing and
/// identity rules of Android's `AlertSignaler`, as a pure state machine
/// (shared/spec/alerts-and-sound-modes.md#the-sound-alert). The app's own tone
/// (the fallback) carries out its actions; the AlarmKit path reads the same
/// identity and give-up time. Driven by a caller-supplied monotonic clock, in
/// milliseconds, so the rules are tested without real time.
///
/// Built for one job, waking an adult who is asleep:
///
/// - A ramp, because a gentle first note that becomes insistent wakes a
///   parent without launching them out of bed.
/// - Repeats, because one tone is easy to sleep through.
/// - Latched: a trigger starts an alarm that owns its own lifetime. The
///   detector re-arming when the room goes quiet deliberately does *not* stop
///   it (a baby who cries for forty seconds and settles is exactly the alert
///   nobody heard), so only a person, or the give-up 5 minutes after the
///   latest trigger, ends it.
/// - One alarm at a time. Another room's trigger re-points it without
///   restarting the ramp; a room outranks the monitor's own failure alarm; a
///   real room ends a bedtime test.
struct AlertSignalerState: Sendable {
    /// What `alarmingCameraId` holds while the alarm is for the monitor itself
    /// rather than a room. Never a real camera id, so nothing that reads it as
    /// one can be sent to a room that is not there. Android:
    /// `AlertSignaler.MONITORING_FAILURE`.
    static let monitoringFailure = "monitoring-failure"
    /// The bedtime test's camera id: the real alert, down the same path, for no
    /// room. Android: `MonitoringService.TEST_CAMERA_ID`.
    static let testCameraId = "dozecam-test-alert"
    /// How often the fallback player should `tick`: fine enough for a ramp to
    /// sound continuous, coarse enough to cost nothing. Android: `TICK_MS`.
    static let tickMs: Int64 = 250

    /// Which sound a burst plays.
    enum Tone: Equatable, Sendable {
        /// The user's alert sound.
        case alert
        /// The monitor's own failure tone, so a failure is never mistaken for
        /// a room getting loud (shared/spec/failure-alerts.md#how-it-is-said).
        case failure
    }

    /// What the player and the vibrator do now, in order.
    enum Action: Equatable, Sendable {
        /// Start one burst of `tone` at `volume` (0...1 of the ceiling's
        /// scale), replacing any burst still playing. Only with the chime on.
        case burst(Tone, volume: Float)
        /// Adjust the burst in flight; the ramp climbs through a burst, not
        /// only between them. Only with the chime on.
        case setVolume(Float)
        /// One vibration pulse. Only with vibration on.
        case vibrate
        /// Silence the player and cancel vibration: the alarm is over.
        case stop
    }

    private struct Run: Sendable {
        var cameraId: String
        let tone: Tone
        let schedule: AlarmSchedule
        let chime: Bool
        let vibrate: Bool
        let startedAtMs: Int64
        var previousElapsedMs: Int64 = 0
    }

    private var run: Run?
    private var lastTriggerAtMs: Int64 = 0

    /// The camera the alarm is sounding for, `monitoringFailure`, or
    /// `testCameraId`; nil when nothing is sounding.
    var alarmingCameraId: String? { run?.cameraId }
    var isAlarming: Bool { run != nil }

    /// When the alarm gives up unless triggered again or acknowledged: for the
    /// AlarmKit path, which cannot be ticked.
    var givesUpAtMs: Int64? { run.map { lastTriggerAtMs + $0.schedule.maxDurationMs } }

    /// Whether a bedtime test is refused: any alarm but a test's (a room's,
    /// or the failure's, as on Android), or a detector still triggered. Asked
    /// of the detectors too, because a room playing aloud raises its card with
    /// no alarm, and a test would overwrite that card. Android:
    /// `AppContainer.roomIsCrying`.
    func roomIsCrying(anyDetectorTriggered: Bool) -> Bool {
        anyDetectorTriggered || alarmingCameraId.map { $0 != Self.testCameraId } == true
    }

    /// A real room, rather than the monitor's failure or a bedtime test.
    private static func isRoom(_ cameraId: String) -> Bool {
        cameraId != monitoringFailure && cameraId != testCameraId
    }

    /// Sounds the alarm for `cameraId` at monotonic `nowMs`, with the chime,
    /// vibration, ramp, repeat and ceiling of `settings` (kept for the whole
    /// run). If one is already sounding, it is pointed at the newer room and
    /// given a fresh five minutes, never a second alarm: two overlapping is
    /// noise, not urgency, and a ramp in progress must not drop back to a
    /// whisper.
    ///
    /// The monitor's own alarm ranks below a room's. A failure signalled while
    /// a room's alarm sounds leaves it exactly as it is, identity included, so
    /// clearing the failure never stops a room's alarm. A room's trigger while
    /// the failure tone sounds replaces it outright with the room's own tone.
    /// A real room's trigger over a bedtime test does the same: the test is
    /// over.
    ///
    /// A test is refused while a room is crying (`roomIsCrying`); one that
    /// arrives anyway leaves the room's alarm untouched rather than relabel it
    /// as a test.
    mutating func signal(cameraId: String, settings: AppSettings, nowMs: Int64) -> [Action] {
        if let current = run?.cameraId, cameraId == Self.testCameraId, Self.isRoom(current) { return [] }
        // Before the other checks, as on Android: every trigger, including a
        // failure announced during a cry, extends the give-up.
        lastTriggerAtMs = nowMs
        var actions: [Action] = []
        if let current = run?.cameraId {
            if cameraId == Self.monitoringFailure { return [] }
            let replaces =
                current == Self.monitoringFailure || (current == Self.testCameraId && cameraId != Self.testCameraId)
            if !replaces {
                run?.cameraId = cameraId
                return []
            }
            actions = stop()
        }
        let started = Run(
            cameraId: cameraId,
            tone: cameraId == Self.monitoringFailure ? .failure : .alert,
            schedule: settings.alarmSchedule,
            chime: settings.alertChime,
            vibrate: settings.alertVibrate,
            startedAtMs: nowMs
        )
        run = started
        return actions + burst(started, elapsedMs: 0)
    }

    /// Carries the alarm to monotonic `nowMs`: every `tickMs` while
    /// `isAlarming`. Returns `.stop` once it gives up.
    mutating func tick(nowMs: Int64) -> [Action] {
        guard var current = run else { return [] }
        // Against the latest trigger, so a room that keeps going off keeps the
        // alarm alive rather than timing out mid-cry.
        if current.schedule.expired(sinceLastTriggerMs: nowMs - lastTriggerAtMs) { return stop() }
        let elapsedMs = nowMs - current.startedAtMs
        let actions: [Action]
        if current.schedule.burstDue(fromMs: current.previousElapsedMs, toMs: elapsedMs) {
            actions = burst(current, elapsedMs: elapsedMs)
        } else if current.chime {
            actions = [.setVolume(current.schedule.volumeAt(elapsedMs))]
        } else {
            actions = []
        }
        current.previousElapsedMs = elapsedMs
        run = current
        return actions
    }

    /// A burst now, at the ramp's current volume, for an alarm whose delivery
    /// just changed (AlarmKit refused it, so the fallback tone takes over): the
    /// burst it started with went to AlarmKit, and waiting for the next repeat
    /// would leave the room silent for up to 30 s.
    mutating func burstNow(nowMs: Int64) -> [Action] {
        guard var current = run else { return [] }
        let elapsedMs = nowMs - current.startedAtMs
        current.previousElapsedMs = elapsedMs
        run = current
        return burst(current, elapsedMs: elapsedMs)
    }

    /// A person is here: a touch or key press on the viewer, or the alert
    /// dismissed.
    mutating func acknowledge() -> [Action] {
        isAlarming ? stop() : []
    }

    /// Ends the alarm outright (monitoring stopping takes its alert with it,
    /// alerts switched off). Always `.stop`, so a player left sounding by
    /// anything else is silenced too.
    mutating func stop() -> [Action] {
        run = nil
        return [.stop]
    }

    /// Ends the alarm only if it is `cameraId`'s: a room leaving the monitored
    /// set takes its alert with it, and every other room's is left as it is.
    mutating func stop(ifAlarming cameraId: String) -> [Action] {
        alarmingCameraId == cameraId ? stop() : []
    }

    /// Nothing is wrong any more: the failure's own alarm stops, and a room's
    /// is never silenced by a camera coming back or a charger going in.
    mutating func stopFailure() -> [Action] {
        stop(ifAlarming: Self.monitoringFailure)
    }

    private func burst(_ run: Run, elapsedMs: Int64) -> [Action] {
        var actions: [Action] = []
        if run.chime { actions.append(.burst(run.tone, volume: run.schedule.volumeAt(elapsedMs))) }
        if run.vibrate { actions.append(.vibrate) }
        return actions
    }
}

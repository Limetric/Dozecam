/// The shape of an alert: how loud it is at a given moment, when the next burst
/// is due, and when it gives up. The port of Android's `AlarmSchedule`
/// (shared/spec/alerts-and-sound-modes.md#the-sound-alert).
///
/// Pure on purpose. This is the part of the alarm most likely to be wrong at
/// 3 am and the hardest to check on a device, so it is decided here and the
/// player only carries it out. `AlertSignalerState` runs it over time.
struct AlarmSchedule: Equatable, Sendable {
    /// Quiet enough to surface a sleeper without launching them out of bed.
    static let rampStart: Float = 0.15

    static let defaultRampMs: Int64 = 5_000
    static let defaultRepeatIntervalMs: Int64 = 8_000
    static let defaultMaxDurationMs: Int64 = 5 * 60_000

    /// The user's range for the repeat; `AppSettings.alertRepeatIntervalMsRange`
    /// enforces it.
    static let minRepeatIntervalMs: Int64 = 3_000
    static let maxRepeatIntervalMs: Int64 = 30_000

    /// Climb from a gentle first note rather than starting at full volume.
    var ramp = true
    var rampMs = defaultRampMs
    var repeatIntervalMs = defaultRepeatIntervalMs
    /// How long an unacknowledged alarm keeps trying before it gives up.
    var maxDurationMs = defaultMaxDurationMs
    /// Ceiling as a fraction of the loudest the alarm may be. The ramp climbs
    /// to this and never past it: Dozecam never rewrites the volume the user
    /// set on the phone.
    var ceiling: Float = 1

    /// Volume `elapsedMs` into the alarm, as a fraction of the most it may be.
    func volumeAt(_ elapsedMs: Int64) -> Float {
        let climbed: Float =
            if !ramp || rampMs <= 0 {
                1
            } else if elapsedMs <= 0 {
                Self.rampStart
            } else if elapsedMs >= rampMs {
                1
            } else {
                Self.rampStart + (1 - Self.rampStart) * (Float(elapsedMs) / Float(rampMs))
            }
        return (ceiling.clamped(to: 0...1) * climbed).clamped(to: 0...1)
    }

    /// Whether a tick carrying the alarm from `fromMs` to `toMs` (elapsed)
    /// crossed the start of a repeat. Derived from elapsed time rather than
    /// counted, so a tick the system delayed cannot lose a burst or fire two at
    /// once.
    func burstDue(fromMs: Int64, toMs: Int64) -> Bool {
        repeatIntervalMs > 0 && toMs > fromMs && toMs / repeatIntervalMs > fromMs / repeatIntervalMs
    }

    /// Measured from the most recent trigger, not from the first: a room that
    /// is still going off half an hour later has earned another five minutes.
    func expired(sinceLastTriggerMs: Int64) -> Bool {
        sinceLastTriggerMs >= maxDurationMs
    }
}

extension AppSettings {
    /// The alarm the user has chosen: ramp, repeat and ceiling. Android:
    /// `AppSettings.alarmSchedule()`.
    var alarmSchedule: AlarmSchedule {
        AlarmSchedule(ramp: alertRamp, repeatIntervalMs: Int64(alertRepeatIntervalMs), ceiling: alertVolume)
    }
}

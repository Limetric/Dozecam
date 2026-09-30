/// Decides when the ongoing status line is worth redrawing: the port of
/// Android's `StatusHeartbeat` (shared/spec/monitoring-lifecycle.md#staying-alive).
///
/// The problem it solves: a healthy quiet night would leave "Monitoring 2
/// cameras" frozen for hours, indistinguishable, to the person glancing at it,
/// from a wedged process whose last text happened to say the same thing. So
/// while the line is the healthy listening one, each display carries two live
/// facts, the loudest camera's level in coarse steps and the minute it was
/// last actually posted, and this meters them: a text change goes out at once,
/// level motion at most every `minIntervalMs`, and a silent room still reposts
/// once a minute as the stamp rolls over. The stamp can only advance because
/// the app really evaluated just now; a wedged app posts nothing and visibly
/// goes stale. Unhealthy lines carry no stamp, so "offline" never looks freshly
/// confirmed.
///
/// The caller offers the current line on every change and on a timer of its
/// own, every `minIntervalMs`: time is an input, and without the timer a room
/// in steady digital silence would never roll the minute over.
///
/// Two clocks on purpose, both given in: the throttle runs on monotonic time,
/// so a wall clock set backwards cannot freeze the heartbeat for hours, while
/// the stamp shown stays on wall time, the clock the user reads.
struct StatusHeartbeat: Sendable {
    /// Mirrors the in-app meter: the useful RMS range is 0...0.5, so the
    /// status line's level and the meter agree.
    static let levelScale: Float = 0.5
    /// Coarse on purpose: enough steps to visibly move, few enough to repost
    /// rarely.
    static let levelBuckets = 10
    /// The fastest the line is allowed to breathe.
    static let minIntervalMs: Int64 = 2_500

    private static let minuteMs: Int64 = 60_000

    /// One posting's worth of status; equal displays never repost.
    struct Display: Equatable, Sendable {
        let text: String
        /// The level in `0...levelBuckets` steps, on the healthy line only.
        let levelBucket: Int?
        /// The wall-clock minute (milliseconds since 1970 / 60 000) of this
        /// posting, on the healthy line only.
        let minute: Int64?

        /// The stamp to show, as wall-clock milliseconds.
        var checkedAtMs: Int64? { minute.map { $0 * StatusHeartbeat.minuteMs } }

        /// "Checked 3:04 AM", or nil on a line that has not earned a stamp.
        /// Android: `monitoring_status_checked`.
        func checkedText(_ wording: FailureWording) -> String? {
            checkedAtMs.map { "Checked \(wording.time($0))" }
        }
    }

    var minIntervalMs = Self.minIntervalMs

    private var posted: Display?
    private var postedAtMonotonicMs = Int64.min / 2

    /// Returns the display to post now, or nil when the line already says
    /// everything this would. `level` is the loudest monitored camera's RMS
    /// while the healthy listening text is showing (`MonitoringStatus.Status`
    /// gives it there only), and nil for every other state: those repost on a
    /// text change only, because "offline" wearing a fresh timestamp would read
    /// as reassurance it has not earned.
    mutating func offer(_ text: String, level: Float?, wallMs: Int64, monotonicMs: Int64) -> Display? {
        let display = Display(
            text: text,
            levelBucket: level.map(Self.bucket),
            minute: level == nil ? nil : wallMs / Self.minuteMs
        )
        let textChanged = display.text != posted?.text
        if !textChanged && (display == posted || monotonicMs - postedAtMonotonicMs < minIntervalMs) {
            return nil
        }
        posted = display
        postedAtMonotonicMs = monotonicMs
        return display
    }

    /// Clamped as a Float, before it becomes an Int: a level past the meter's
    /// range fills the bar, and nothing a decoder hands over can trap here.
    private static func bucket(_ level: Float) -> Int {
        let scaled = (level / levelScale * Float(levelBuckets)).rounded()
        guard !scaled.isNaN else { return 0 }
        return Int(scaled.clamped(to: 0...Float(levelBuckets)))
    }
}

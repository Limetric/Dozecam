import Testing

@testable import Dozecam

/// The port of Android's `StatusHeartbeatTest`: proof of life on the status
/// line, metered.
struct StatusHeartbeatTests {
    /// The heartbeat and its two clocks.
    private struct Harness {
        var heartbeat = StatusHeartbeat()
        var wallMs: Int64 = 0
        var monotonicMs: Int64 = 0

        /// Both clocks tick together, as they do on a device whose time is
        /// never corrected.
        mutating func advance(to ms: Int64) {
            wallMs = ms
            monotonicMs = ms
        }

        mutating func offer(_ text: String, _ level: Float?) -> StatusHeartbeat.Display? {
            heartbeat.offer(text, level: level, wallMs: wallMs, monotonicMs: monotonicMs)
        }
    }

    private let listening = "Monitoring 2 cameras"

    @Test func theFirstStatusAlwaysPosts() {
        var clock = Harness()
        let display = clock.offer(listening, 0)
        #expect(display?.levelBucket == 0)
        #expect(display?.checkedAtMs == 0)
    }

    @Test func aTextChangePostsImmediatelyEvenMidInterval() {
        var clock = Harness()
        _ = clock.offer(listening, 0)
        clock.advance(to: 100)
        #expect(clock.offer("Sound detected — Nursery", nil) != nil)
    }

    @Test func levelMotionPostsNoFasterThanTheInterval() {
        var clock = Harness()
        _ = clock.offer(listening, 0)
        // The room gets loud straight away, but the last post is too fresh.
        clock.advance(to: 1_000)
        #expect(clock.offer(listening, 0.3) == nil)
        // Once the interval has passed, the still-loud room shows.
        clock.advance(to: StatusHeartbeat.minIntervalMs)
        #expect(clock.offer(listening, 0.3)?.levelBucket == 6)
    }

    /// RMS wobbles on every decoded buffer; the coarse buckets exist so that
    /// wobble is not a reason to repost.
    @Test func aSubBucketWiggleNeverReposts() {
        var clock = Harness()
        _ = clock.offer(listening, 0.30)
        clock.advance(to: 10_000)
        #expect(clock.offer(listening, 0.31) == nil)
    }

    /// The point of the whole thing: a silent, healthy night must still
    /// visibly advance.
    @Test func aSilentRoomStillPostsOnceAMinute() {
        var clock = Harness()
        _ = clock.offer(listening, 0)
        clock.advance(to: 59_999)
        #expect(clock.offer(listening, 0) == nil)
        clock.advance(to: 60_000)
        let display = clock.offer(listening, 0)
        #expect(display?.checkedAtMs == 60_000)
        #expect(display?.checkedText(FailureWording { "t\($0)" }) == "Checked t60000")
    }

    /// "Offline" wearing a fresh timestamp would read as reassurance it has
    /// not earned.
    @Test func statesWithoutALevelNeverRepostOnTimeAlone() {
        var clock = Harness()
        let first = clock.offer("Offline — waiting for network", nil)
        #expect(first?.checkedAtMs == nil)
        #expect(first?.checkedText(.system) == nil)
        clock.advance(to: 10 * 60_000)
        #expect(clock.offer("Offline — waiting for network", nil) == nil)
    }

    @Test func aLevelPastTheMetersRangeFillsTheBarRatherThanOverflowingIt() {
        var clock = Harness()
        #expect(clock.offer(listening, 0.9)?.levelBucket == StatusHeartbeat.levelBuckets)
    }

    @Test func noLevelADecoderHandsOverCanTrap() {
        var heartbeat = StatusHeartbeat()
        #expect(heartbeat.offer("a", level: .infinity, wallMs: 0, monotonicMs: 0)?.levelBucket == 10)
        #expect(heartbeat.offer("b", level: .nan, wallMs: 0, monotonicMs: 0)?.levelBucket == 0)
        #expect(heartbeat.offer("c", level: -1, wallMs: 0, monotonicMs: 0)?.levelBucket == 0)
    }

    /// The throttle runs on monotonic time precisely so this cannot happen: a
    /// wall clock corrected backwards after a post must not freeze the
    /// heartbeat until wall time catches up.
    @Test func aWallClockSetBackwardsCannotFreezeTheHeartbeat() {
        var clock = Harness()
        clock.wallMs = 3_600_000
        clock.monotonicMs = 0
        _ = clock.offer(listening, 0)
        // An hour's correction backwards; only a minute really passes.
        clock.wallMs = 60_000
        clock.monotonicMs = 60_000
        #expect(clock.offer(listening, 0) != nil)
    }
}

import Testing

@testable import Dozecam

/// The port of Android's `AlarmScheduleTest`: the ramp and the repeat, checked
/// off-device. These are the numbers that decide whether anyone wakes up, and
/// a device is the worst place to find out they are wrong.
struct AlarmScheduleTests {
    private let tolerance: Float = 0.0001

    @Test func startsGentlyRatherThanAtFullVolume() {
        let schedule = AlarmSchedule(ramp: true, rampMs: 5_000)
        #expect(abs(schedule.volumeAt(0) - AlarmSchedule.rampStart) < tolerance)
    }

    @Test func climbsToFullOverTheRampAndStaysThere() {
        let schedule = AlarmSchedule(ramp: true, rampMs: 5_000)
        let quiet = schedule.volumeAt(0)
        let middle = schedule.volumeAt(2_500)
        let full = schedule.volumeAt(5_000)
        #expect(quiet < middle)
        #expect(middle < full)
        #expect(abs(full - 1) < tolerance)
        #expect(abs(schedule.volumeAt(60_000) - 1) < tolerance)
    }

    @Test func startsAtFullVolumeWhenTheRampIsSwitchedOff() {
        #expect(abs(AlarmSchedule(ramp: false, rampMs: 5_000).volumeAt(0) - 1) < tolerance)
    }

    @Test func theCeilingScalesTheWholeRampAndIsNeverExceeded() {
        let schedule = AlarmSchedule(ramp: true, rampMs: 5_000, ceiling: 0.5)
        #expect(abs(schedule.volumeAt(0) - 0.5 * AlarmSchedule.rampStart) < tolerance)
        #expect(abs(schedule.volumeAt(5_000) - 0.5) < tolerance)
        #expect(abs(schedule.volumeAt(60_000) - 0.5) < tolerance)
    }

    @Test func aCeilingOutsideTheUsableRangeIsClampedRatherThanTrusted() {
        #expect(abs(AlarmSchedule(ramp: false, ceiling: 4).volumeAt(0) - 1) < tolerance)
        #expect(abs(AlarmSchedule(ramp: false, ceiling: -1).volumeAt(0)) < tolerance)
    }

    @Test func aBurstIsDueEachTimeATickCrossesTheInterval() {
        let schedule = AlarmSchedule(repeatIntervalMs: 8_000)
        #expect(!schedule.burstDue(fromMs: 7_750, toMs: 7_999))
        #expect(schedule.burstDue(fromMs: 7_750, toMs: 8_000))
        #expect(!schedule.burstDue(fromMs: 8_000, toMs: 8_250))
        #expect(schedule.burstDue(fromMs: 15_750, toMs: 16_000))
    }

    /// A tick the system delayed must not lose the burst it slept through: the
    /// repeat is what a sleeping parent is relying on.
    @Test func aTickThatOvershootsSeveralIntervalsStillReportsABurst() {
        #expect(AlarmSchedule(repeatIntervalMs: 8_000).burstDue(fromMs: 1_000, toMs: 30_000))
    }

    @Test func timeStandingStillIsNotABurst() {
        let schedule = AlarmSchedule(repeatIntervalMs: 8_000)
        #expect(!schedule.burstDue(fromMs: 8_000, toMs: 8_000))
        #expect(!schedule.burstDue(fromMs: 9_000, toMs: 8_000))
    }

    @Test func givesUpOnlyOnceTheCapIsReached() {
        let schedule = AlarmSchedule(maxDurationMs: 300_000)
        #expect(!schedule.expired(sinceLastTriggerMs: 0))
        #expect(!schedule.expired(sinceLastTriggerMs: 299_999))
        #expect(schedule.expired(sinceLastTriggerMs: 300_000))
    }

    @Test func settingsChooseTheRampTheRepeatAndTheCeiling() {
        var settings = AppSettings()
        settings.alertRamp = false
        settings.alertRepeatIntervalMs = 12_000
        settings.alertVolume = 0.4
        let schedule = settings.alarmSchedule
        #expect(!schedule.ramp)
        #expect(schedule.repeatIntervalMs == 12_000)
        #expect(schedule.ceiling == 0.4)
        #expect(schedule.rampMs == AlarmSchedule.defaultRampMs)
        #expect(schedule.maxDurationMs == AlarmSchedule.defaultMaxDurationMs)
    }
}

import Testing

@testable import Dozecam

/// The alarm's behaviour over time: the timing and identity cases of Android's
/// `AlertSignalerTest`, on a manual clock. What matters here (does it repeat,
/// does it stop, does it stack) happens over minutes, which is no way to run a
/// test suite.
struct AlertSignalerStateTests {
    /// Drives the state as the fallback player would: a tick every `tickMs`,
    /// with every action recorded.
    private struct Harness {
        var state = AlertSignalerState()
        var nowMs: Int64 = 1_000_000
        var actions: [AlertSignalerState.Action] = []

        var bursts: [AlertSignalerState.Tone] {
            actions.compactMap { if case .burst(let tone, _) = $0 { tone } else { nil } }
        }
        var pulses: Int { actions.filter { $0 == .vibrate }.count }
        var stops: Int { actions.filter { $0 == .stop }.count }
        /// The volume the player is at: the latest burst's or adjustment.
        var volume: Float? {
            actions.reversed().lazy.compactMap { action -> Float? in
                switch action {
                case .burst(_, let volume): volume
                case .setVolume(let volume): volume
                default: nil
                }
            }.first
        }

        mutating func signal(_ cameraId: String, _ settings: AppSettings = AppSettings()) {
            actions += state.signal(cameraId: cameraId, settings: settings, nowMs: nowMs)
        }

        mutating func advance(_ ms: Int64) {
            let end = nowMs + ms
            while nowMs + AlertSignalerState.tickMs <= end {
                nowMs += AlertSignalerState.tickMs
                actions += state.tick(nowMs: nowMs)
            }
            nowMs = end
        }
    }

    @Test func soundsAndVibratesTheMomentItIsTriggered() {
        var alarm = Harness()
        alarm.signal("cam-1")
        #expect(alarm.bursts == [.alert])
        #expect(alarm.pulses == 1)
        #expect(alarm.state.alarmingCameraId == "cam-1")
    }

    @Test func startsBelowFullVolumeAndClimbsToIt() {
        var alarm = Harness()
        alarm.signal("cam-1")
        #expect(alarm.volume == AlarmSchedule.rampStart)
        alarm.advance(AlarmSchedule.defaultRampMs)
        #expect(alarm.volume == 1)
        #expect(alarm.state.isAlarming)
    }

    @Test func aFlatAlarmStartsAtTheCeiling() {
        var settings = AppSettings()
        settings.alertRamp = false
        settings.alertVolume = 0.5
        var alarm = Harness()
        alarm.signal("cam-1", settings)
        #expect(alarm.volume == 0.5)
    }

    @Test func repeatsOnTheChosenInterval() {
        var settings = AppSettings()
        settings.alertRepeatIntervalMs = 8_000
        var alarm = Harness()
        alarm.signal("cam-1", settings)
        alarm.advance(8_000)
        #expect(alarm.bursts.count == 2)
        #expect(alarm.pulses == 2)
    }

    /// The latch, which is the whole point: nothing but a person or the cap
    /// may end this.
    @Test func keepsSoundingLongAfterTheRoomHasGoneQuiet() {
        var alarm = Harness()
        alarm.signal("cam-1")
        alarm.advance(60_000)
        #expect(alarm.state.isAlarming)
        #expect(alarm.bursts.count >= 7)
    }

    @Test func aSecondTriggerRetargetsTheAlarmInsteadOfStackingASecondOne() {
        var alarm = Harness()
        alarm.signal("cam-1")
        alarm.advance(1_000)
        let volume = alarm.volume
        alarm.signal("cam-2")
        #expect(alarm.state.alarmingCameraId == "cam-2")
        #expect(alarm.bursts.count == 1, "the second trigger must not open its own burst")
        alarm.advance(250)
        #expect(alarm.volume! > volume!, "the ramp carries on rather than dropping back to a whisper")
        alarm.advance(6_750)
        #expect(alarm.bursts.count == 2, "one burst per interval, not two")
    }

    /// Whoever clears the failure stops "the failure's alarm", and that must
    /// never be an unacknowledged room.
    @Test func aFailureAnnouncedDuringACryLeavesTheCrysAlarmAsItIs() {
        var alarm = Harness()
        alarm.signal("cam-1")
        alarm.advance(1_000)
        alarm.signal(AlertSignalerState.monitoringFailure)
        #expect(alarm.state.alarmingCameraId == "cam-1")
        #expect(alarm.bursts == [.alert])
        #expect(alarm.state.stopFailure().isEmpty)
        #expect(alarm.state.isAlarming)
    }

    /// The room is the more urgent of the two, and it gets its own tone, from
    /// the start of its own ramp.
    @Test func aCryDuringTheFailureAlarmReplacesItWithTheRoomsOwnAlarm() {
        var alarm = Harness()
        alarm.signal(AlertSignalerState.monitoringFailure)
        alarm.advance(6_000)
        alarm.signal("cam-1")
        #expect(alarm.state.alarmingCameraId == "cam-1")
        #expect(alarm.bursts == [.failure, .alert])
        #expect(alarm.stops == 1)
        #expect(alarm.volume == AlarmSchedule.rampStart)
    }

    @Test func theFailureAlarmStopsWhenTheFailureClears() {
        var alarm = Harness()
        alarm.signal(AlertSignalerState.monitoringFailure)
        #expect(alarm.state.stopFailure() == [.stop])
        #expect(!alarm.state.isAlarming)
    }

    @Test func acknowledgingStopsEverything() {
        var alarm = Harness()
        alarm.signal("cam-1")
        alarm.actions += alarm.state.acknowledge()
        #expect(!alarm.state.isAlarming)
        #expect(alarm.state.alarmingCameraId == nil)
        #expect(alarm.stops == 1)
        alarm.advance(60_000)
        #expect(alarm.bursts.count == 1, "nothing may sound after an acknowledgement")
        #expect(alarm.state.acknowledge().isEmpty)
    }

    @Test func givesUpOnceTheCapIsReached() {
        var alarm = Harness()
        alarm.signal("cam-1")
        #expect(alarm.state.givesUpAtMs == alarm.nowMs + AlarmSchedule.defaultMaxDurationMs)
        alarm.advance(AlarmSchedule.defaultMaxDurationMs)
        #expect(!alarm.state.isAlarming)
        #expect(alarm.stops == 1)
    }

    /// A room still going off half an hour later has earned another five
    /// minutes.
    @Test func aFreshTriggerExtendsTheCap() {
        var alarm = Harness()
        alarm.signal("cam-1")
        alarm.advance(240_000)
        alarm.signal("cam-1")
        alarm.advance(AlarmSchedule.defaultMaxDurationMs - 240_000)
        #expect(alarm.state.isAlarming, "the cap runs from the newest trigger")
        alarm.advance(AlarmSchedule.defaultMaxDurationMs)
        #expect(!alarm.state.isAlarming)
    }

    @Test func vibrationCarriesOnAloneWhenTheChimeIsSwitchedOff() {
        var settings = AppSettings()
        settings.alertChime = false
        var alarm = Harness()
        alarm.signal("cam-1", settings)
        alarm.advance(8_000)
        #expect(alarm.bursts.isEmpty)
        #expect(alarm.volume == nil)
        #expect(alarm.pulses == 2)
    }

    @Test func theChimeCarriesOnAloneWhenVibrationIsSwitchedOff() {
        var settings = AppSettings()
        settings.alertVibrate = false
        var alarm = Harness()
        alarm.signal("cam-1", settings)
        #expect(alarm.bursts.count == 1)
        #expect(alarm.pulses == 0)
    }

    @Test func theAlertVolumeCapsTheRamp() {
        var settings = AppSettings()
        settings.alertVolume = 0.5
        var alarm = Harness()
        alarm.signal("cam-1", settings)
        alarm.advance(AlarmSchedule.defaultRampMs)
        #expect(alarm.volume == 0.5)
    }

    /// A late tick loses no burst and fires no two at once.
    @Test func aDelayedTickStillBurstsOnce() {
        var alarm = Harness()
        alarm.signal("cam-1")
        alarm.actions += alarm.state.tick(nowMs: alarm.nowMs + 30_000)
        #expect(alarm.bursts.count == 2)
    }

    // MARK: - Bedtime test

    /// The real alert, down the same path, with only the wording changed; a
    /// real room ends it, with its own alarm from the start of its ramp.
    @Test func aRealAlertEndsATestsAlarm() {
        var alarm = Harness()
        alarm.signal(AlertSignalerState.testCameraId)
        #expect(alarm.bursts == [.alert])
        alarm.advance(6_000)
        alarm.signal("cam-1")
        #expect(alarm.state.alarmingCameraId == "cam-1")
        #expect(alarm.stops == 1)
        #expect(alarm.bursts.count == 2)
        #expect(alarm.volume == AlarmSchedule.rampStart)
    }

    @Test func aTestIsRefusedWhileARoomIsCrying() {
        var alarm = Harness()
        #expect(!alarm.state.roomIsCrying(anyDetectorTriggered: false))
        #expect(alarm.state.roomIsCrying(anyDetectorTriggered: true))
        alarm.signal("cam-1")
        #expect(alarm.state.roomIsCrying(anyDetectorTriggered: false))
        _ = alarm.state.stop()
        alarm.signal(AlertSignalerState.testCameraId)
        #expect(!alarm.state.roomIsCrying(anyDetectorTriggered: false))
    }

    /// Should one slip past `roomIsCrying`, a room's alarm is never relabelled
    /// as a test, nor has its cap extended by one.
    @Test func aTestNeverTakesOverARoomsAlarm() {
        var alarm = Harness()
        alarm.signal("cam-1")
        let givesUp = alarm.state.givesUpAtMs
        alarm.advance(1_000)
        alarm.signal(AlertSignalerState.testCameraId)
        #expect(alarm.state.alarmingCameraId == "cam-1")
        #expect(alarm.state.givesUpAtMs == givesUp)
    }

    @Test func stoppingARoomLeavesAnotherRoomsAlarmAlone() {
        var alarm = Harness()
        alarm.signal("cam-1")
        #expect(alarm.state.stop(ifAlarming: "cam-2").isEmpty)
        #expect(alarm.state.stop(ifAlarming: "cam-1") == [.stop])
    }
}

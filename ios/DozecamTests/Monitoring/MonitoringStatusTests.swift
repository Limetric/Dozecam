import Testing

@testable import Dozecam

/// The port of Android's `MonitoringStatusTest`: the one line for the whole
/// nursery, word for word.
struct MonitoringStatusTests {
    private let wording = FailureWording { "t\($0)" }

    private func live(_ id: String, _ name: String? = nil, level: Float? = 0) -> CameraMonitorState {
        CameraMonitorState(cameraId: id, name: name ?? id, level: level, connection: .live)
    }

    private func of(
        _ states: [CameraMonitorState], anyMonitors: Bool = true, enabledCount: Int? = nil, pausedCount: Int = 0,
        aloud: Set<String> = [], alertsEnabled: Bool = true, failures: [MonitoringFailure] = [],
        recovered: RecoveredFailure? = nil
    ) -> MonitoringStatus.Status {
        MonitoringStatus.of(
            anyMonitors: anyMonitors, states: states, enabledCount: enabledCount ?? states.count,
            pausedCount: pausedCount, aloudCameraIds: aloud, alertsEnabled: alertsEnabled, failures: failures,
            recovered: recovered, wording: wording
        )
    }

    private let nurseryDown = MonitoringFailure(
        reason: .cameraUnreachable(cameraId: "b", name: "Nursery", networkDown: false), sinceMs: 3
    )

    @Test func alertsOffIsSaidFirst() {
        let status = of([live("a", level: 0.2)], alertsEnabled: false)
        #expect(status.text == "Alerts off · Monitoring 1 camera")
        // Still a healthy line: the monitor is running, it just will not wake
        // anyone, and the meter is proof of the former.
        #expect(status.level == 0.2)
    }

    @Test func aFailureIsSaidFirstAndCarriesNoLevel() {
        var offline = live("b", "Nursery")
        offline.connection = .offline
        let status = of(
            [live("a", level: 0.3), offline],
            failures: [nurseryDown, MonitoringFailure(reason: .lowBattery(percent: 22), sinceMs: 4)]
        )
        #expect(status.text == "Can't reach Nursery since t3 · 1 more")
        #expect(status.level == nil)
    }

    /// Nobody was looking at 3 am, so the morning's glance has to be able to
    /// learn it happened.
    @Test func aFailureThatClearedLeavesANoteOnTheHealthyLine() {
        let status = of(
            [live("a", level: 0.2)],
            recovered: RecoveredFailure(
                reason: .cameraUnreachable(cameraId: "a", name: "Nursery", networkDown: true), sinceMs: 0,
                clearedAtMs: 9
            )
        )
        #expect(status.text == "Monitoring 1 camera · Earlier: No network — can't reach Nursery, cleared t9")
        #expect(status.level == 0.2)
    }

    @Test func theListeningLineCarriesTheLoudestCamerasLevel() {
        let status = of([live("a", level: 0.1), live("b", level: 0.4)])
        #expect(status.text == "Monitoring 2 cameras")
        #expect(status.level == 0.4)
    }

    /// The level is proof of health, so it must never ride a line that is not
    /// the healthy one.
    @Test func noUnhealthyLineCarriesALevel() {
        let loud = live("a", level: 0.4)
        func with(_ connection: ConnectionState) -> CameraMonitorState {
            var camera = live("b")
            camera.connection = connection
            return camera
        }
        var triggered = live("b", "Nursery")
        triggered.phase = .triggered
        let statuses = [
            of([loud, with(.offline)]),
            of([loud, with(.reconnecting(attempt: 1))]),
            of([loud, with(.connecting)]),
            of([loud, triggered]),
            of([]),
            of([], anyMonitors: false, enabledCount: 0),
        ]
        for status in statuses {
            #expect(status.level == nil, "\(status.text)")
        }
    }

    @Test func theUnhealthyLinesSayWhatIsWrong() {
        var offline = live("b")
        offline.connection = .offline
        var reconnecting = live("b")
        reconnecting.connection = .reconnecting(attempt: 2)
        var connecting = live("b")
        connecting.connection = .connecting
        #expect(of([live("a"), offline]).text == "Offline — waiting for network")
        #expect(of([live("a"), reconnecting]).text == "Reconnecting to 1 camera…")
        #expect(
            of([reconnecting, CameraMonitorState(cameraId: "c", name: "c", connection: .reconnecting(attempt: 1))]).text
                == "Reconnecting to 2 cameras…")
        #expect(of([live("a"), connecting]).text == "Connecting to the cameras…")
        #expect(of([]).text == "Connecting to the cameras…")
    }

    /// A live camera that has not decoded a buffer yet has no level, and it
    /// must neither break the line nor drag the loudest reading down.
    @Test func anUnmeasuredCameraContributesNoLevel() {
        #expect(of([live("a", level: nil), live("b", level: 0.3)]).level == 0.3)
        let unmeasured = of([live("a", level: nil)])
        #expect(unmeasured.text == "Monitoring 1 camera")
        #expect(unmeasured.level == nil)
    }

    @Test func aTriggeredCameraOutranksEverything() {
        var offline = live("a")
        offline.connection = .offline
        var triggered = live("b", "Nursery")
        triggered.phase = .triggered
        #expect(of([offline, triggered]).text == "Sound detected — Nursery")
    }

    /// An enabled-but-unmonitorable camera must not be silently claimed as
    /// covered.
    @Test func partialCoverageSaysSoAndStillProvesTheRestIsLive() {
        let status = of([live("a", level: 0.2)], enabledCount: 2)
        #expect(status.text == "Monitoring 1 camera · 1 not monitorable")
        #expect(status.level == 0.2)
    }

    @Test func aRoomComingOutOfTheSpeakerIsDisclosedInFrontOfEverythingElse() {
        let status = of([live("a", "Nursery", level: 0.2), live("b", "Hall")], aloud: ["a"])
        #expect(status.text == "Nursery aloud · Monitoring 2 cameras")
        #expect(status.level == 0.2)
    }

    @Test func severalRoomsComingOutOfTheSpeakerAreCountedRatherThanListed() {
        let status = of([live("a", "Nursery"), live("b", "Hall"), live("c", "Play room")], aloud: ["a", "b", "c"])
        #expect(status.text == "3 rooms aloud · Monitoring 3 cameras")
    }

    @Test func theDisclosureDoesNotPushAsideWhatIsWrong() {
        let status = of(
            [live("a", "Nursery"), CameraMonitorState(cameraId: "b", name: "Hall", connection: .offline)], aloud: ["a"]
        )
        #expect(status.text == "Nursery aloud · Offline — waiting for network")
    }

    @Test func nothingIsClaimedForACameraThatIsNotActuallyBeingPlayed() {
        #expect(of([live("a", "Nursery", level: 0.2)], aloud: ["gone"]).text == "Monitoring 1 camera")
    }

    @Test func withNoMonitorsThereIsNothingToOverstate() {
        #expect(of([], anyMonitors: false, enabledCount: 0).text == "No camera is switched on")
    }

    /// Every part at once, in Android's order: alerts off, then aloud, then
    /// the line, the paused count and the note.
    @Test func everyDisclosureStacksInOrder() {
        let status = of(
            [live("a", "Nursery")], pausedCount: 1, aloud: ["a"], alertsEnabled: false,
            recovered: RecoveredFailure(reason: .lowBattery(percent: 24), sinceMs: 0, clearedAtMs: 9)
        )
        #expect(
            status.text
                == "Alerts off · Nursery aloud · Monitoring 1 camera · 1 paused · Earlier: Battery low — 24%, cleared t9"
        )
    }

    // MARK: - Paused cameras

    /// One room heard must not read as the whole house heard.
    @Test func aPausedCameraIsOwnedUpToOnTheListeningLine() {
        let status = of([live("a", level: 0.2)], pausedCount: 1)
        #expect(status.text == "Monitoring 1 camera · 1 paused")
        #expect(status.level == 0.2)
    }

    /// Behind whatever else is said: a failure is still the more urgent half.
    @Test func aFailureKeepsThePausedCountBehindIt() {
        var offline = live("b", "Nursery")
        offline.connection = .offline
        let status = of([offline], pausedCount: 2, failures: [nurseryDown])
        #expect(status.text == "Can't reach Nursery since t3 · 2 paused")
    }

    @Test func everyCameraPausedSaysSoRatherThanThatNoneIsSwitchedOn() {
        let status = of([], anyMonitors: false, enabledCount: 0, pausedCount: 2)
        #expect(status.text == "Every camera is paused")
        #expect(status.level == nil)
    }

    /// A paused room is not "not monitorable": the partial count is of rooms
    /// that should be heard and are not, and a pause is neither.
    @Test func aPausedCameraIsNotCountedAsUnmonitorable() {
        #expect(!of([live("a")], pausedCount: 1).text.contains("not monitorable"))
    }

    /// Every room paused with the battery running down: the failure is said
    /// first, and the paused rooms still behind it.
    @Test func aFailureOverEveryRoomPausedStillOwnsUpToThePause() {
        let status = of(
            [], anyMonitors: false, enabledCount: 0, pausedCount: 2,
            failures: [MonitoringFailure(reason: .lowBattery(percent: 20), sinceMs: 0)]
        )
        #expect(status.text == "Battery low — 20% since t0 · 2 paused")
    }
}

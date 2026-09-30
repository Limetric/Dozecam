import Foundation
import Testing

@testable import Dozecam

/// The ports of the arming tests in Android's `MonitoringStateTest` and
/// `ArmingTest`. Which cameras count is `MonitorTransportsTests`' business;
/// here the counts come from `MonitorTransports.monitorable`, as the callers'
/// will.
struct ArmingTests {
    func arm(
        _ cameras: [Camera], pausedIds: Set<String> = [], consoleHost: String? = nil, running: Bool = false,
        exitRequested: Bool = false, localNetworkGranted: Bool = true
    ) -> Bool {
        Arming.shouldArmMonitoring(
            monitorableCount: MonitorTransports.monitorable(cameras, pausedIds: pausedIds, consoleHost: consoleHost)
                .count,
            running: running, exitRequested: exitRequested, localNetworkGranted: localNetworkGranted)
    }

    // MARK: - Android's MonitoringStateTest

    @Test func anEnabledCameraArms() {
        #expect(Arming.shouldAutoArm(monitorableCount: 1, running: false, exitRequested: false))
    }

    @Test func nothingSwitchedOnMeansNothingToArm() {
        #expect(!Arming.shouldAutoArm(monitorableCount: 0, running: false, exitRequested: false))
    }

    @Test func anAlreadyRunningMonitorIsNotStartedAgain() {
        #expect(!Arming.shouldAutoArm(monitorableCount: 2, running: true, exitRequested: false))
    }

    /// Settings re-arms the instant it sees the monitor go; the request has to
    /// hold the door until the exit has finished.
    @Test func anExitInFlightIsNotReArmedOver() {
        #expect(!Arming.shouldAutoArm(monitorableCount: 1, running: false, exitRequested: true))
    }

    // MARK: - Android's ArmingTest

    @Test func aMonitorableCameraArmsMonitoring() {
        #expect(arm([Camera(id: "a", name: "Nursery", url: "rtsp://cam:7447/a")]))
    }

    @Test func nothingArmsWithoutLocalNetworkAccess() {
        // Every RTSP connection would fail, so this would be a monitor
        // reconnecting all night.
        #expect(!arm([Camera(id: "a", name: "Nursery", url: "rtsp://cam:7447/a")], localNetworkGranted: false))
    }

    @Test func aWatchOnlyCameraDoesNotArmMonitoring() {
        // rtsps cannot be monitored over RTSP, and nobody is signed in.
        #expect(!arm([Camera(id: "a", name: "Stale", url: "rtsps://cam:7441/a")]))
    }

    @Test func aWatchOnlyCameraProtectCanCarryDoesArmMonitoring() {
        let camera = Camera(
            id: "a", name: "Nursery", url: "rtsps://cam:7441/a",
            protect: ProtectStream(cameraId: "cam1", channel: 1, consoleHost: "console.lan"))

        #expect(arm([camera], consoleHost: "console.lan"))
    }

    @Test func aCameraFromAConsoleWeAreNoLongerSignedInToDoesNotArm() {
        let camera = Camera(
            id: "a", name: "Nursery", url: "rtsps://cam:7441/a",
            protect: ProtectStream(cameraId: "cam1", channel: 1, consoleHost: "old-console.lan"))

        #expect(!arm([camera], consoleHost: "new-console.lan"))
    }

    @Test func aSwitchedOffCameraDoesNotArmMonitoring() {
        #expect(!arm([Camera(id: "a", name: "Nursery", url: "rtsp://cam:7447/a", enabled: false)]))
    }

    @Test func anAlreadyRunningMonitorIsNotStartedAgainByTheGate() {
        #expect(!arm([Camera(id: "a", name: "Nursery", url: "rtsp://cam:7447/a")], running: true))
    }

    // MARK: - The spec's paused rooms

    /// shared/spec/monitoring-lifecycle.md: nothing to listen to is "no
    /// enabled, unpaused camera with any way to hear it".
    @Test func everyRoomPausedIsNothingToArm() {
        #expect(!arm([Camera(id: "a", name: "Nursery", url: "rtsp://cam:7447/a")], pausedIds: ["a"]))
    }

    /// But a running monitor with every room paused stays up, idle, so a room
    /// resumed is picked up rather than landing on a monitor on its way out.
    @Test func aMonitorWithEveryRoomPausedIsNotStopped() {
        #expect(!Arming.shouldStopMonitoring(running: true, enabledMonitorableCount: 1))
    }

    @Test func aMonitorLeftWithNothingToListenToIsStopped() {
        #expect(Arming.shouldStopMonitoring(running: true, enabledMonitorableCount: 0))
    }

    @Test func aMonitorNotRunningHasNothingToStop() {
        #expect(!Arming.shouldStopMonitoring(running: false, enabledMonitorableCount: 0))
    }
}

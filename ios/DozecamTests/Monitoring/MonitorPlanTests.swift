import Foundation
import Testing

@testable import Dozecam

/// The port of Android's `MonitorPlanTest`, test for test, plus the transport
/// changes Android's `MonitoringService.reconcile` handles before planning.
struct MonitorPlanTests {
    func camera(_ id: String, url: String? = nil, name: String? = nil) -> Camera {
        Camera(id: id, name: name ?? id, url: url ?? "rtsp://cam:7447/\(id)")
    }

    func running(_ cameras: Camera...) -> [String: Camera] {
        Dictionary(uniqueKeysWithValues: cameras.map { ($0.id, $0) })
    }

    // MARK: - Android's MonitorPlanTest

    @Test("starts every camera when nothing is running yet")
    func startsEveryCamera() {
        let plan = MonitorPlan.of(running: running(), wanted: [camera("a"), camera("b")])

        #expect(plan.start.map(\.id) == ["a", "b"])
        #expect(plan.stop.isEmpty)
    }

    @Test("stops a camera that was switched off")
    func stopsSwitchedOff() {
        let plan = MonitorPlan.of(running: running(camera("a"), camera("b")), wanted: [camera("a")])

        #expect(plan.stop == ["b"])
        #expect(plan.start.isEmpty)
    }

    @Test("leaves an unchanged camera alone")
    func leavesUnchangedAlone() {
        let plan = MonitorPlan.of(running: running(camera("a")), wanted: [camera("a")])

        #expect(plan.isEmpty)
    }

    @Test("a rename does not disturb the running monitor")
    func renameDoesNotDisturb() {
        let plan = MonitorPlan.of(
            running: running(camera("a", name: "Nursery")), wanted: [camera("a", name: "Baby room")])

        // Restarting here would re-arm a detector that may be mid-refractory.
        #expect(plan.isEmpty)
    }

    @Test("a url change under the same id restarts just that camera")
    func urlChangeRestartsThatCamera() {
        let plan = MonitorPlan.of(
            running: running(camera("a"), camera("b")),
            wanted: [camera("a", url: "rtsp://cam:7447/a-new"), camera("b")])

        #expect(plan.stop == ["a"])
        #expect(plan.start.map(\.id) == ["a"])
        #expect(plan.start.first?.url == "rtsp://cam:7447/a-new")
    }

    @Test("everything switched off stops everything and starts nothing")
    func everythingSwitchedOff() {
        let plan = MonitorPlan.of(running: running(camera("a"), camera("b")), wanted: [])

        #expect(plan.stop == ["a", "b"])
        #expect(plan.start.isEmpty)
    }

    @Test("a protect camera is matched by id and url like any other")
    func protectCameraMatchedLikeAnyOther() {
        var protect = camera("a")
        protect.protect = ProtectStream(cameraId: "cam-a", channel: 1, consoleHost: "console")

        let plan = MonitorPlan.of(running: running(protect), wanted: [protect])

        #expect(plan.isEmpty)
    }

    // MARK: - Transports

    @Test func unchangedTransportsLeaveTheMonitorAlone() {
        let transports: [String: [StreamSource]] = ["a": [.rtsp(url: "rtsp://cam:7447/a")]]

        let plan = MonitorPlan.of(
            running: running(camera("a")), runningTransports: transports, wanted: [camera("a")],
            transports: transports)

        #expect(plan.isEmpty)
    }

    @Test func aCameraWhoseTransportsChangedIsRebuilt() {
        // Signed in to another console: the livestream went, the camera did not.
        let rtsp = StreamSource.rtsp(url: "rtsp://cam:7447/a")
        let plan = MonitorPlan.of(
            running: running(camera("a"), camera("b")),
            runningTransports: [
                "a": [rtsp, .livestream(cameraId: "cam-a", channel: 1)], "b": [.rtsp(url: "rtsp://cam:7447/b")],
            ],
            wanted: [camera("a"), camera("b")],
            transports: ["a": [rtsp], "b": [.rtsp(url: "rtsp://cam:7447/b")]])

        #expect(plan.stop == ["a"])
        #expect(plan.start.map(\.id) == ["a"])
    }

    @Test func aCameraThatLostEveryTransportIsStoppedAndNotStarted() {
        let plan = MonitorPlan.of(
            running: running(camera("a")),
            runningTransports: ["a": [.livestream(cameraId: "cam-a", channel: 1)]],
            wanted: [],
            transports: [:])

        #expect(plan.stop == ["a"])
        #expect(plan.start.isEmpty)
    }
}

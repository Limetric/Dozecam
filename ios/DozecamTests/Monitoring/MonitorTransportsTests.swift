import Foundation
import Testing

@testable import Dozecam

/// The port of Android's `MonitorTransportsTest`, test for test, and of the
/// camera-set parts of `ArmingTest` that decide which cameras count.
struct MonitorTransportsTests {
    let rtspUrl = "rtsp://console:7447/abc"
    let host = "console.lan"
    let livestream = StreamSource.livestream(cameraId: "cam-1", channel: 1)

    func camera(_ url: String? = nil) -> Camera {
        Camera(id: "a", name: "Nursery", url: url ?? rtspUrl)
    }

    // MARK: - Android's MonitorTransportsTest

    @Test("a plain RTSP camera is listened to over RTSP and nothing else")
    func plainRtsp() {
        let transports = MonitorTransports.of(camera(), source: .rtsp(url: rtspUrl), consoleHost: host)

        #expect(transports == [.rtsp(url: rtspUrl)])
    }

    @Test("a Protect camera keeps RTSP first and the livestream in reserve")
    func protectCamera() {
        let transports = MonitorTransports.of(camera(), source: livestream, consoleHost: host)

        // RTSP asks for the audio track alone. The livestream carries the
        // camera's video whether or not anything looks at it, so it is what to
        // fall back to, not what to start with.
        #expect(transports == [.rtsp(url: rtspUrl), livestream])
    }

    @Test("an rtsps camera is monitorable after all when Protect can carry it")
    func rtspsCarriedByProtect() {
        let transports = MonitorTransports.of(
            camera("rtsps://console:7441/abc"), source: livestream, consoleHost: host)

        #expect(transports == [livestream])
    }

    @Test("an rtsps camera with no console behind it cannot be listened to at all")
    func rtspsWithNoConsole() {
        let camera = camera("rtsps://console:7441/abc")

        let transports = MonitorTransports.of(camera, source: .rtsp(url: camera.url), consoleHost: host)

        // Empty is the honest answer; the caller says so rather than leaving a
        // room quietly uncovered.
        #expect(transports.isEmpty)
    }

    @Test("a livestream is not offered while nobody is signed in")
    func noLivestreamSignedOut() {
        // A camera stored before the console host was recorded still resolves
        // to a livestream identity, but negotiating one without a sign-in can
        // only ever fail, and would count the camera as monitored meanwhile.
        let transports = MonitorTransports.of(
            camera("rtsps://console:7441/abc"), source: livestream, consoleHost: nil)

        #expect(transports.isEmpty)
    }

    // MARK: - The camera set

    @Test func aProtectCameraFromTheSignedInConsoleGetsBothTransports() {
        let camera = Camera(
            id: "protect-cam1-1", name: "Nursery", url: rtspUrl,
            protect: ProtectStream(cameraId: "cam1", channel: 1, consoleHost: host))

        let transports = MonitorTransports.transportsFor([camera], consoleHost: host)

        #expect(transports == [camera.id: [.rtsp(url: rtspUrl), .livestream(cameraId: "cam1", channel: 1)]])
    }

    @Test func aCameraFromAConsoleWeAreNoLongerSignedInToIsRtspOnly() {
        let camera = Camera(
            id: "protect-cam1-1", name: "Nursery", url: rtspUrl,
            protect: ProtectStream(cameraId: "cam1", channel: 1, consoleHost: "old-console.lan"))

        let transports = MonitorTransports.transportsFor([camera], consoleHost: "new-console.lan")

        // The new console has never heard of cam1.
        #expect(transports == [camera.id: [.rtsp(url: rtspUrl)]])
    }

    @Test func anRtspsCameraFromAnotherConsoleIsNotMonitorable() {
        let camera = Camera(
            id: "a", name: "Nursery", url: "rtsps://cam:7441/a",
            protect: ProtectStream(cameraId: "cam1", channel: 1, consoleHost: "old-console.lan"))

        #expect(MonitorTransports.monitorable([camera], consoleHost: "new-console.lan").isEmpty)
    }

    @Test func aLegacyProtectIdFallsBackToTheLivestreamWhileSignedIn() {
        let camera = Camera(id: "protect-cam1-1", name: "Nursery", url: rtspUrl)

        let transports = MonitorTransports.transportsFor([camera], consoleHost: host)

        #expect(transports == [camera.id: [.rtsp(url: rtspUrl), .livestream(cameraId: "cam1", channel: 1)]])
    }

    @Test func switchedOffAndPausedCamerasAreNotMonitored() {
        let cameras = [
            Camera(id: "a", name: "Nursery", url: "rtsp://cam:7447/a"),
            Camera(id: "b", name: "Play room", url: "rtsp://cam:7447/b", enabled: false),
            Camera(id: "c", name: "Hallway", url: "rtsp://cam:7447/c"),
        ]

        let transports = MonitorTransports.transportsFor(cameras, pausedIds: ["c"], consoleHost: nil)

        #expect(transports == ["a": [.rtsp(url: "rtsp://cam:7447/a")]])
    }

    @Test func monitorableKeepsListOrderAndDropsCamerasWithNoWayIn() {
        let cameras = [
            Camera(id: "c", name: "Hallway", url: "rtsp://cam:7447/c"),
            Camera(id: "stale", name: "Stale", url: "rtsps://cam:7441/s"),
            Camera(id: "a", name: "Nursery", url: "rtsp://cam:7447/a"),
        ]

        let monitorable = MonitorTransports.monitorable(cameras, consoleHost: host)

        #expect(monitorable.map(\.id) == ["c", "a"])
    }
}

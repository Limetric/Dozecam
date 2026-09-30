import Testing

@testable import Dozecam

/// Mirrors Android's `StreamSource.of`: which transport a camera plays over.
struct StreamSourceTests {
    private let console = "192.168.1.1"

    @Test func aProtectCameraFromTheSignedInConsolePlaysTheLivestream() {
        let camera = Camera(
            id: "protect-cam1-1", name: "Nursery", url: "rtsp://192.168.1.1:7447/a",
            protect: ProtectStream(cameraId: "cam1", channel: 1, consoleHost: console))
        #expect(StreamSource.of(camera, consoleHost: console) == .livestream(cameraId: "cam1", channel: 1))
    }

    @Test func aCameraFromAnotherConsolePlaysItsOwnRTSPURL() {
        let camera = Camera(
            id: "protect-cam1-1", name: "Nursery", url: "rtsp://192.168.1.2:7447/a",
            protect: ProtectStream(cameraId: "cam1", channel: 1, consoleHost: "192.168.1.2"))
        #expect(StreamSource.of(camera, consoleHost: console) == .rtsp(url: "rtsp://192.168.1.2:7447/a"))
    }

    @Test func aProtectCameraWithoutARecordedConsoleCountsAsOurs() {
        let camera = Camera(
            id: "x", name: "Nursery", url: "rtsp://h/a", protect: ProtectStream(cameraId: "cam1", channel: 1))
        #expect(StreamSource.of(camera, consoleHost: nil) == .livestream(cameraId: "cam1", channel: 1))
    }

    @Test func aCameraAddedByURLPlaysRTSP() {
        let camera = Camera(id: "3f2a", name: "Testbed", url: "rtsp://127.0.0.1:18554/nursery")
        #expect(StreamSource.of(camera, consoleHost: console) == .rtsp(url: "rtsp://127.0.0.1:18554/nursery"))
    }

    @Test(arguments: [
        ("protect-cam9-0", StreamSource.livestream(cameraId: "cam9", channel: 0)),
        ("protect-cam9-12", .livestream(cameraId: "cam9", channel: 12)),
        ("protect-cam9-+1", .rtsp(url: "rtsp://h/a")),
        ("protect-cam9-", .rtsp(url: "rtsp://h/a")),
        ("protect--1", .rtsp(url: "rtsp://h/a")),
        ("protect-a-b-1", .rtsp(url: "rtsp://h/a")),
        ("camera-cam9-1", .rtsp(url: "rtsp://h/a")),
    ])
    func anIdentityIsRecoveredFromALegacyProtectID(id: String, expected: StreamSource) {
        #expect(StreamSource.of(Camera(id: id, name: "n", url: "rtsp://h/a"), consoleHost: console) == expected)
    }
}

import Foundation
import Testing

@testable import Dozecam

/// The import rules of Android's `OnboardingViewModel` (and its tests),
/// driven by the shared camera fixtures: both APIs must store the same
/// `Camera` ids as each other and as Android.
struct ProtectCameraImportTests {
    let console = StubConsole()
    /// As the user typed it, port included; the stream URLs use only the host.
    let consoleHost = "127.0.0.1:7443"

    var baseURL: URL { ProtectApiClient.baseURL(for: consoleHost)! }
    var publicApi: ProtectPublicApiClient { ProtectPublicApiClient(baseURL: baseURL, urlSession: console.urlSession) }
    var legacyApi: ProtectApiClient { ProtectApiClient(baseURL: baseURL, urlSession: console.urlSession) }

    func publicCameras() throws -> [PublicCamera] {
        let expected = try CamerasExpected.load()
        // A verbatim console response: not a strict fixture decode.
        return try JSONDecoder().decode(
            [PublicCamera].self, from: Fixtures.data("protect-api/\(expected.responses.publicApi)"))
    }

    func legacyCameras() throws -> [ProtectCamera] {
        let expected = try CamerasExpected.load()
        // A verbatim console response: the client ignores fields it does not use,
        // so this is not a strict fixture decode.
        return try JSONDecoder().decode(
            ProtectBootstrap.self, from: Fixtures.data("protect-api/\(expected.responses.legacyApi)")
        ).cameras
    }

    @Test func thePublicApiStoresEveryCameraOnTheMediumChannel() async throws {
        let expected = try CamerasExpected.load()
        #expect(expected.name == "the public and legacy APIs yield the same camera ids for the same cameras")
        var imported: [Camera] = []
        for camera in try publicCameras() {
            try console.enqueueFixture("protect-api/public/rtsps-stream.json")
            imported.append(
                try await ProtectCameraImport.importCamera(
                    camera,
                    api: publicApi,
                    apiKey: "key-1",
                    consoleHost: consoleHost,
                    existing: []
                )
            )
        }

        // What Android's OnboardingViewModel stores for the same cameras.
        #expect(imported.map(\.id) == ["protect-cam1-1", "protect-cam2-1", "protect-cam3-1"], "\(expected.name)")
        #expect(
            imported.map(\.id) == expected.cameras.map { Camera.protectID(cameraId: $0.id, channel: 1) },
            "\(expected.name)"
        )
        // An unnamed camera (`name: null`) still onboards.
        #expect(imported.map(\.name) == ["Nursery", "Camera", "Landing"])
        // The console advertised 192.168.1.1 over RTSPS; only the alias survives.
        #expect(imported.allSatisfy { $0.url == "rtsp://127.0.0.1:7447/aliasM" })
        #expect(imported.allSatisfy { $0.enabled })
        #expect(
            imported.first?.protect == ProtectStream(cameraId: "cam1", channel: 1, consoleHost: "127.0.0.1:7443")
        )
        // Streams the console already serves are reused, never re-created.
        #expect(console.requests.allSatisfy { $0.method == "GET" })
    }

    @Test func theLegacyApiStoresThePreferredChannelAndSkipsACameraWithNone() async throws {
        let expected = try CamerasExpected.load()
        #expect(expected.name == "the public and legacy APIs yield the same camera ids for the same cameras")
        var imported: [Camera?] = []
        for camera in try legacyCameras() {
            imported.append(
                try await ProtectCameraImport.importCamera(
                    camera,
                    api: legacyApi,
                    consoleHost: consoleHost,
                    existing: []
                ) { _, _ in
                    Issue.record("a served alias must not be re-enabled")
                    return camera
                }
            )
        }

        // What Android's OnboardingViewModel stores for the same cameras:
        // cam2 has no Medium channel, so its first (High, id 0) is used.
        #expect(imported.map { $0?.id } == ["protect-cam1-1", "protect-cam2-0", nil], "\(expected.name)")
        #expect(imported.map { $0?.name } == ["Nursery", "Camera", nil])
        #expect(
            imported.map { $0?.url }
                == expected.cameras.map { camera in
                    camera.legacyApi.preferredChannel?.rtspAlias.map { "rtsp://127.0.0.1:7447/\($0)" }
                }
        )
        #expect(imported.first??.protect == ProtectStream(cameraId: "cam1", channel: 1, consoleHost: "127.0.0.1:7443"))
        #expect(console.requests.isEmpty)
    }

    /// A console that moves between the APIs updates its entries in place.
    @Test func bothApisStoreTheSameCameraForAMediumChannel() async throws {
        let publicCamera = try #require(try publicCameras().first { $0.id == "cam1" })
        let legacyCamera = try #require(try legacyCameras().first { $0.id == "cam1" })
        try console.enqueueFixture("protect-api/public/rtsps-stream.json")

        let viaPublic = try await ProtectCameraImport.importCamera(
            publicCamera,
            api: publicApi,
            apiKey: "key-1",
            consoleHost: consoleHost,
            existing: []
        )
        let viaLegacy = try await ProtectCameraImport.importCamera(
            legacyCamera,
            api: legacyApi,
            consoleHost: consoleHost,
            existing: []
        ) { _, _ in legacyCamera }

        #expect(viaPublic == viaLegacy)
    }

    @Test func aCameraWithoutAMediumStreamHasOneEnabled() async throws {
        let expected = try ProtectPublicApiClientTests.Expected.load().rtspsStreamCreated
        try #require(expected.name == "the created stream comes back by quality")
        console.enqueue(json: #"{"high": "rtsps://192.168.1.1:7441/aliasH?enableSrtp", "medium": null}"#)
        try console.enqueueFixture("protect-api/public/\(expected.response)")

        let imported = try await ProtectCameraImport.importCamera(
            PublicCamera(id: "cam1", name: "Nursery"),
            api: publicApi,
            apiKey: "key-1",
            consoleHost: consoleHost,
            existing: []
        )

        #expect(console.requests.map(\.method) == ["GET", "POST"])
        let body = try #require(try JSONSerialization.jsonObject(with: console.requests[1].body) as? [String: Any])
        #expect(body["qualities"] as? [String] == ["medium"])
        #expect(imported.url == "rtsp://127.0.0.1:7447/aliasM")
        #expect(imported.id == "protect-cam1-1")
    }

    @Test func aConsoleThatWillNotServeAMediumStreamFailsTheImport() async throws {
        console.enqueue(json: "{}")
        console.enqueue(json: "{}")

        await #expect {
            try await ProtectCameraImport.importCamera(
                PublicCamera(id: "cam1", name: "Nursery"),
                api: publicApi,
                apiKey: "key-1",
                consoleHost: consoleHost,
                existing: []
            )
        } throws: { error in
            guard case .invalidResponse = error as? ProtectAPIError else { return false }
            return true
        }
    }

    @Test func aChannelWithoutRtspIsEnabledAndItsNewAliasStored() async throws {
        let expected = try ProtectApiClientTests.Expected.load().rtspEnabled
        try #require(expected.name == "the patched camera comes back with its channel's new alias")
        console.enqueueLogin()
        try console.enqueueFixture("protect-api/legacy/\(expected.response)")
        let session = try await legacyApi.login(username: "babycam", password: "secret")
        let camera = ProtectCamera(
            id: "cam1",
            name: "Nursery",
            channels: [ProtectChannel(id: 0, name: "High"), ProtectChannel(id: 1, name: "Medium")]
        )

        let imported = try await ProtectCameraImport.importCamera(
            camera,
            api: legacyApi,
            consoleHost: consoleHost,
            existing: []
        ) { cameraId, channelId in
            try await legacyApi.enableRtsp(session, cameraId: cameraId, channelId: channelId)
        }

        #expect(console.requests.last?.method == "PATCH")
        #expect(imported?.url == "rtsp://127.0.0.1:7447/\(expected.rtspAlias)", "\(expected.name)")
        #expect(imported?.id == "protect-cam1-1")
    }

    /// Whether a camera is on is the user's call: silently re-enabling it
    /// would put it back in the viewer and restart monitoring it.
    @Test func reImportingASwitchedOffCameraLeavesItSwitchedOff() async throws {
        try console.enqueueFixture("protect-api/public/rtsps-stream.json")
        let stored = Camera(id: "protect-cam1-1", name: "Nursery", url: "rtsp://old:7447/stale", enabled: false)

        let reimported = try await ProtectCameraImport.importCamera(
            PublicCamera(id: "cam1", name: "Nursery"),
            api: publicApi,
            apiKey: "key-1",
            consoleHost: consoleHost,
            existing: [stored]
        )

        #expect(reimported.url == "rtsp://127.0.0.1:7447/aliasM")
        #expect(!reimported.enabled)
    }

    @Test func aCameraNewToTheListArrivesSwitchedOn() {
        let other = Camera(id: "protect-cam2-1", name: "Hall", url: "rtsp://h:7447/x", enabled: false)

        let imported = ProtectCameraImport.camera(
            PublicCamera(id: "cam1", name: "Nursery"),
            streamURL: "rtsp://127.0.0.1:7447/aliasM",
            consoleHost: consoleHost,
            existing: [other]
        )

        #expect(imported.enabled)
    }

    @Test func pickerRowsNameTheQualityThatWouldBeImported() throws {
        #expect(
            try publicCameras().map(ProtectCameraImport.discovered) == [
                DiscoveredCamera(id: "cam1", name: "Nursery", detail: "Medium"),
                DiscoveredCamera(id: "cam2", name: "Camera", detail: "Medium"),
                DiscoveredCamera(id: "cam3", name: "Landing", detail: "Medium"),
            ]
        )
        #expect(
            try legacyCameras().map(ProtectCameraImport.discovered) == [
                DiscoveredCamera(id: "cam1", name: "Nursery", detail: "Medium"),
                DiscoveredCamera(id: "cam2", name: "Camera", detail: "High"),
                DiscoveredCamera(id: "cam3", name: "Landing", detail: ""),
            ]
        )
    }

    @Test func theConsoleHostIsKeptAsTypedButTrimmed() {
        #expect(ProtectCameraImport.consoleHost(forInput: " 192.168.1.1:7443 \n") == "192.168.1.1:7443")
    }
}

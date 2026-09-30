import Foundation
import Testing

@testable import Dozecam

/// Mirrors Android's `ProtectPublicApiClientTest`, less what belongs to the
/// trust layer.
struct ProtectPublicApiClientTests {
    /// `shared/fixtures/protect-api/public/expected.json`.
    struct Expected: Codable {
        struct Streams: Codable {
            let name: String
            let response: String
            let streams: [String: String]
        }

        struct Talkback: Codable {
            let name: String
            let response: String
            let url: String
            let codec: String
            let samplingRate: Int
            let bitsPerSample: Int
        }

        let rtspsStream: Streams
        let rtspsStreamCreated: Streams
        let talkbackSession: Talkback

        static func load() throws -> Expected {
            try Fixtures.decode(Expected.self, from: "protect-api/public/expected.json")
        }
    }

    let console = StubConsole()

    func client(host: String = "127.0.0.1") -> ProtectPublicApiClient {
        ProtectPublicApiClient(baseURL: ProtectApiClient.baseURL(for: host)!, urlSession: console.urlSession)
    }

    func response(_ file: String) -> String { "protect-api/public/\(file)" }

    @Test func camerasAreReadFromTheIntegrationEndpointWithTheApiKey() async throws {
        let expected = try CamerasExpected.load()
        #expect(expected.name == "the public and legacy APIs yield the same camera ids for the same cameras")
        try console.enqueueFixture("protect-api/\(expected.responses.publicApi)")

        let cameras = try await client().cameras(apiKey: "key-1")

        let request = try #require(console.requests.first)
        #expect(request.method == "GET")
        #expect(request.path == "/proxy/protect/integration/v1/cameras")
        #expect(request.header("X-API-KEY") == "key-1")
        // The same ids the legacy client reads from its bootstrap.
        #expect(cameras.map(\.id) == expected.cameras.map(\.id), "\(expected.name)")
        #expect(cameras.map(\.name) == expected.cameras.map(\.publicApi.name), "\(expected.name)")
    }

    @Test func camerasReportWhetherTheyCarryASpeaker() async throws {
        let expected = try CamerasExpected.load()
        try console.enqueueFixture("protect-api/\(expected.responses.publicApi)")

        let cameras = try await client().cameras(apiKey: "key-1")

        // A camera whose flags never arrived (cam3) is treated as having no
        // speaker: offering talk-back and failing is worse than not offering it.
        #expect(cameras.map(\.hasSpeaker) == expected.cameras.map(\.publicApi.hasSpeaker), "\(expected.name)")
    }

    @Test func activeStreamsAreReturnedByQualityAndInactiveOnesDropped() async throws {
        let expected = try Expected.load().rtspsStream
        try #require(expected.name == "active streams are kept by quality and a null one is dropped")
        try console.enqueueFixture(response(expected.response))

        let streams = try await client().rtspsStreams(apiKey: "key-1", cameraId: "cam1")

        let request = try #require(console.requests.first)
        #expect(request.method == "GET")
        #expect(request.path == "/proxy/protect/integration/v1/cameras/cam1/rtsps-stream")
        #expect(request.header("X-API-KEY") == "key-1")
        #expect(streams == expected.streams, "\(expected.name)")
    }

    @Test func creatingAStreamPostsTheRequestedQualities() async throws {
        let expected = try Expected.load().rtspsStreamCreated
        try #require(expected.name == "the created stream comes back by quality")
        try console.enqueueFixture(response(expected.response))

        let streams = try await client().createRtspsStreams(apiKey: "key-1", cameraId: "cam1", qualities: ["medium"])

        let request = try #require(console.requests.first)
        #expect(request.method == "POST")
        #expect(request.path == "/proxy/protect/integration/v1/cameras/cam1/rtsps-stream")
        let body = try #require(try JSONSerialization.jsonObject(with: request.body) as? [String: Any])
        #expect(body["qualities"] as? [String] == ["medium"])
        #expect(streams == expected.streams, "\(expected.name)")
    }

    @Test func aTalkbackSessionIsPostedWithoutABodyAndParsed() async throws {
        let expected = try Expected.load().talkbackSession
        try #require(expected.name == "the talk-back session is read as sent")
        try console.enqueueFixture(response(expected.response))

        let session = try await client().talkbackSession(apiKey: "key-1", cameraId: "cam1")

        let request = try #require(console.requests.first)
        #expect(request.method == "POST")
        #expect(request.path == "/proxy/protect/integration/v1/cameras/cam1/talkback-session")
        #expect(request.header("X-API-KEY") == "key-1")
        #expect(request.body.isEmpty)
        #expect(session.url == expected.url, "\(expected.name)")
        #expect(session.codec == expected.codec, "\(expected.name)")
        #expect(session.samplingRate == expected.samplingRate, "\(expected.name)")
        #expect(session.bitsPerSample == expected.bitsPerSample, "\(expected.name)")
    }

    /// The audio goes to the camera, not the console that described it, so
    /// the address in the URL is the only thing that says where.
    @Test func aTalkbackSessionExposesTheCamerasOwnAddress() {
        let session = TalkbackSession(
            url: "rtp://192.168.1.12:7004", codec: "opus", samplingRate: 24000, bitsPerSample: 16)

        #expect(session.host == "192.168.1.12")
        #expect(session.port == 7004)
    }

    @Test func aTalkbackURLWithoutAPortFallsBackTo7004AndRubbishHasNoHost() {
        let portless = TalkbackSession(url: "rtp://192.168.1.12", codec: "opus", samplingRate: 24000, bitsPerSample: 16)
        #expect(portless.host == "192.168.1.12")
        #expect(portless.port == 7004)

        let rubbish = TalkbackSession(url: "not a url", codec: "opus", samplingRate: 24000, bitsPerSample: 16)
        #expect(rubbish.host == nil)
    }

    @Test func aCameraWithoutASpeakerSurfacesTheConsolesRefusal() async throws {
        console.enqueue(status: 404)

        await #expect {
            try await client().talkbackSession(apiKey: "key-1", cameraId: "cam-without-speaker")
        } throws: { error in
            guard case .notFound = error as? ProtectAPIError else { return false }
            return (error as? ProtectAPIError)?.statusCode == 404
        }
    }

    /// Onboarding reads a 401 as a revoked key and mints a fresh one.
    @Test func aRejectedApiKeySurfacesTheStatusCode() async throws {
        console.enqueue(status: 401)

        await #expect {
            try await client().cameras(apiKey: "stale-key")
        } throws: { error in
            guard case .unauthorized = error as? ProtectAPIError else { return false }
            return (error as? ProtectAPIError)?.statusCode == 401
        }
    }

    @Test func anUnreadableCameraListIsAProtocolError() async throws {
        console.enqueue(json: #"{"not": "a list"}"#)

        await #expect {
            try await client().cameras(apiKey: "key-1")
        } throws: { error in
            guard case .invalidResponse = error as? ProtectAPIError else { return false }
            return true
        }
    }

    /// The console advertises its own host and the SRTP-flavoured RTSPS
    /// port; neither survives the trip to the player, only the alias does.
    @Test func streamURLsKeepTheAliasButRepointAtTheReachableConsole() {
        #expect(
            client(host: "192.168.1.50").streamURL(forRtsps: "rtsps://10.0.0.1:7441/aliasM?enableSrtp")
                == "rtsp://192.168.1.50:7447/aliasM"
        )
    }

    @Test func streamURLsBracketIPv6ConsoleHostsAndRejectUnparseableInput() {
        let api = client(host: "[2001:db8::1]")

        #expect(api.streamURL(forRtsps: "rtsps://10.0.0.1:7441/aliasM") == "rtsp://[2001:db8::1]:7447/aliasM")
        #expect(api.streamURL(forRtsps: "rtsps://10.0.0.1:7441/") == nil)
        #expect(api.streamURL(forRtsps: "not a url") == nil)
    }
}

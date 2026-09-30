import Foundation
import Testing

@testable import Dozecam

/// `shared/fixtures/protect-api/cameras.expected.json`, which the public
/// client's tests read too: both clients must yield the same camera ids.
struct CamerasExpected: Decodable {
    struct Responses: Decodable {
        let publicApi: String
        let legacyApi: String
    }

    struct Camera: Decodable {
        let id: String
        let publicApi: PublicApi
        let legacyApi: LegacyApi
    }

    struct PublicApi: Decodable {
        let name: String?
        let hasSpeaker: Bool
    }

    struct LegacyApi: Decodable {
        let name: String
        let preferredChannel: Channel?
    }

    struct Channel: Decodable {
        let name: String
        let rtspAlias: String?
    }

    let name: String
    let responses: Responses
    let cameras: [Camera]

    static let caseName = "the public and legacy APIs yield the same camera ids for the same cameras"

    /// The case, checked to be the one these tests are about.
    static func load() throws -> CamerasExpected {
        let loaded = try Fixtures.decode(CamerasExpected.self, from: "protect-api/cameras.expected.json")
        try #require(loaded.name == caseName)
        return loaded
    }
}

/// Mirrors Android's `ProtectApiClientTest`, less what belongs to the trust
/// layer (TLS and the pin prompt).
struct ProtectApiClientTests {
    /// `shared/fixtures/protect-api/legacy/expected.json`.
    struct Expected: Decodable {
        struct Livestream: Decodable {
            let name: String
            let response: String
            let url: String
        }

        struct RtspEnabled: Decodable {
            let name: String
            let response: String
            let rtspAlias: String
        }

        struct ApiKey: Decodable {
            let name: String
            let response: String
            let apiKey: String
        }

        let livestream: Livestream
        let rtspEnabled: RtspEnabled
        let apiKey: ApiKey

        static func load() throws -> Expected {
            try Fixtures.decode(Expected.self, from: "protect-api/legacy/expected.json")
        }
    }

    let console = StubConsole()

    /// 127.0.0.1, as the legacy livestream expectation assumes.
    func client() -> ProtectApiClient {
        ProtectApiClient(baseURL: ProtectApiClient.baseURL(for: "127.0.0.1")!, urlSession: console.urlSession)
    }

    func response(_ file: String) -> String { "protect-api/legacy/\(file)" }

    @Test func livestreamURLIsNegotiatedAndRepointedAtTheConsoleAddress() async throws {
        let expected = try Expected.load().livestream
        try #require(expected.name == "the advertised host is swapped for the address the console answered on")
        console.enqueueLogin()
        try console.enqueueFixture(response(expected.response))
        let api = client()
        let session = try await api.login(username: "user", password: "pass")

        let url = try await api.livestreamURL(session, cameraId: "cam-1", channel: 1)

        #expect(url.absoluteString == expected.url, "\(expected.name)")
        let request = console.requests[1]
        #expect(request.path == "/proxy/protect/api/ws/livestream")
        let query = try #require(request.query)
        #expect(query.contains("camera=cam-1"))
        #expect(query.contains("channel=1"))
        #expect(query.contains("type=fmp4"))
        #expect(query.contains("allowPartialGOP="))
        #expect(request.header("Cookie") == "TOKEN=abc123")
        #expect(request.header("X-CSRF-Token") == "csrf-token-1")
    }

    @Test func livestreamNegotiationFailureNamesTheStatus() async throws {
        console.enqueueLogin()
        console.enqueue(status: 404, json: "nope")
        let api = client()
        let session = try await api.login(username: "user", password: "pass")

        await #expect {
            try await api.livestreamURL(session, cameraId: "cam-1", channel: 1)
        } throws: { error in
            guard case .notFound = error as? ProtectAPIError else { return false }
            return (error as? ProtectAPIError)?.statusCode == 404
        }
    }

    @Test func livestreamResponseWithoutAURLIsRejected() async throws {
        console.enqueueLogin()
        console.enqueue(status: 200, json: "{}")
        let api = client()
        let session = try await api.login(username: "user", password: "pass")

        await #expect {
            try await api.livestreamURL(session, cameraId: "cam-1", channel: 1)
        } throws: { error in
            guard case .invalidResponse = error as? ProtectAPIError else { return false }
            return true
        }
    }

    @Test func loginExtractsTheSessionCookieAndCSRFToken() async throws {
        console.enqueueLogin()

        let session = try await client().login(username: "babycam", password: "secret")

        #expect(session.cookie == "TOKEN=abc123")
        #expect(session.csrfToken == "csrf-token-1")
        let request = try #require(console.requests.first)
        #expect(request.method == "POST")
        #expect(request.path == "/api/auth/login")
        #expect(request.header("Content-Type")?.hasPrefix("application/json") == true)
        let body = try #require(try JSONSerialization.jsonObject(with: request.body) as? [String: Any])
        #expect(body["username"] as? String == "babycam")
        #expect(body["password"] as? String == "secret")
        #expect(body["rememberMe"] as? Bool == true)
    }

    @Test func loginFallsBackToTheUpdatedCSRFHeader() async throws {
        console.enqueue(
            status: 200,
            headers: ["Set-Cookie": "TOKEN=abc123; Path=/; HttpOnly", "X-Updated-CSRF-Token": "csrf-2"]
        )

        let session = try await client().login(username: "babycam", password: "secret")

        #expect(session.csrfToken == "csrf-2")
    }

    @Test func aLoginWithoutASessionCookieIsAProtocolError() async throws {
        console.enqueue(status: 200, headers: ["Set-Cookie": "OTHER=1; Path=/"])

        await #expect {
            try await client().login(username: "babycam", password: "secret")
        } throws: { error in
            guard case .invalidResponse = error as? ProtectAPIError else { return false }
            return true
        }
    }

    @Test func failedLoginSurfacesAnActionableErrorWithTheStatusCode() async throws {
        console.enqueue(status: 401)

        await #expect {
            try await client().login(username: "babycam", password: "wrong")
        } throws: { error in
            guard case .unauthorized(let message) = error as? ProtectAPIError else { return false }
            return message.contains("401") && (error as? ProtectAPIError)?.statusCode == 401
        }
    }

    @Test func anUnreachableConsoleIsNotAnHTTPFailure() async throws {
        console.enqueue(.failure(.cannotConnectToHost))

        await #expect {
            try await client().login(username: "babycam", password: "secret")
        } throws: { error in
            guard case .unreachable(let urlError) = error as? ProtectAPIError else { return false }
            return urlError.code == .cannotConnectToHost && (error as? ProtectAPIError)?.statusCode == nil
        }
    }

    @Test func bootstrapParsesCamerasAndSendsTheSessionHeaders() async throws {
        let expected = try CamerasExpected.load()
        #expect(expected.name == "the public and legacy APIs yield the same camera ids for the same cameras")
        console.enqueueLogin()
        try console.enqueueFixture("protect-api/\(expected.responses.legacyApi)")
        let api = client()
        let session = try await api.login(username: "babycam", password: "secret")

        let bootstrap = try await api.bootstrap(session)

        let request = console.requests[1]
        #expect(request.path == "/proxy/protect/api/bootstrap")
        #expect(request.header("Cookie") == "TOKEN=abc123")
        #expect(request.header("X-CSRF-Token") == "csrf-token-1")
        // The same ids the public client reads from its camera list.
        #expect(bootstrap.cameras.map(\.id) == expected.cameras.map(\.id), "\(expected.name)")
        #expect(bootstrap.cameras.map(\.name) == expected.cameras.map(\.legacyApi.name), "\(expected.name)")
        #expect(
            bootstrap.cameras.map(\.preferredChannel?.name) == expected.cameras.map(\.legacyApi.preferredChannel?.name),
            "\(expected.name)"
        )
        #expect(
            bootstrap.cameras.map(\.preferredChannel?.rtspAlias)
                == expected.cameras.map { $0.legacyApi.preferredChannel?.rtspAlias },
            "\(expected.name)"
        )
    }

    /// iOS reads a `null` name as empty rather than failing the bootstrap:
    /// the public API sends `null` for an unnamed camera, and the spec's
    /// intent is that it still onboards, as "Camera".
    @Test func aNullCameraNameIsReadAsEmpty() async throws {
        console.enqueueLogin()
        console.enqueue(json: #"{"cameras": [{"id": "cam9", "name": null, "channels": null}]}"#)
        let api = client()
        let session = try await api.login(username: "babycam", password: "secret")

        let bootstrap = try await api.bootstrap(session)

        #expect(bootstrap.cameras == [ProtectCamera(id: "cam9", name: "", channels: [])])
    }

    @Test func enableRtspPatchesTheChannelAndReturnsTheUpdatedCamera() async throws {
        let expected = try Expected.load().rtspEnabled
        try #require(expected.name == "the patched camera comes back with its channel's new alias")
        console.enqueueLogin()
        try console.enqueueFixture(response(expected.response))
        let api = client()
        let session = try await api.login(username: "babycam", password: "secret")

        let updated = try await api.enableRtsp(session, cameraId: "cam1", channelId: 1)

        let request = console.requests[1]
        #expect(request.method == "PATCH")
        #expect(request.path == "/proxy/protect/api/cameras/cam1")
        #expect(request.header("Cookie") == "TOKEN=abc123")
        let body = try #require(try JSONSerialization.jsonObject(with: request.body) as? [String: Any])
        let channels = try #require(body["channels"] as? [[String: Any]])
        #expect(channels.first?["id"] as? Int == 1)
        #expect(channels.first?["isRtspEnabled"] as? Bool == true)
        #expect(updated.channels.first?.rtspAlias == expected.rtspAlias, "\(expected.name)")
    }

    @Test func enableRtspRefusedForLackOfRightsIsForbidden() async throws {
        console.enqueueLogin()
        console.enqueue(status: 403)
        let api = client()
        let session = try await api.login(username: "babycam", password: "secret")

        await #expect {
            try await api.enableRtsp(session, cameraId: "cam1", channelId: 1)
        } throws: { error in
            guard case .forbidden = error as? ProtectAPIError else { return false }
            return true
        }
    }

    @Test func createApiKeyPostsTheKeyNameAndUnwrapsTheMintedKey() async throws {
        let expected = try Expected.load().apiKey
        try #require(expected.name == "the minted key is unwrapped from its envelope")
        console.enqueueLogin()
        try console.enqueueFixture(response(expected.response))
        let api = client()
        let session = try await api.login(username: "babycam", password: "secret")

        let key = try await api.createApiKey(session, name: "Dozecam")

        let request = console.requests[1]
        #expect(request.method == "POST")
        #expect(request.path == "/proxy/users/api/v2/user/self/keys")
        #expect(request.header("Cookie") == "TOKEN=abc123")
        let body = try #require(try JSONSerialization.jsonObject(with: request.body) as? [String: Any])
        #expect(body["name"] as? String == "Dozecam")
        #expect(key == expected.apiKey, "\(expected.name)")
    }

    /// Pre-5.3 consoles have no such endpoint; non-owner accounts are
    /// refused. Onboarding falls back to the legacy API on either.
    @Test(arguments: [403, 404])
    func createApiKeySurfacesAConsoleThatWillNotIssueOne(status: Int) async throws {
        console.enqueueLogin()
        console.enqueue(status: status)
        let api = client()
        let session = try await api.login(username: "babycam", password: "secret")

        await #expect {
            try await api.createApiKey(session, name: "Dozecam")
        } throws: { error in
            (error as? ProtectAPIError)?.statusCode == status
        }
    }

    @Test func createApiKeyWithoutAKeyIsAProtocolError() async throws {
        console.enqueueLogin()
        console.enqueue(json: #"{"data": {}}"#)
        let api = client()
        let session = try await api.login(username: "babycam", password: "secret")

        await #expect {
            try await api.createApiKey(session, name: "Dozecam")
        } throws: { error in
            guard case .invalidResponse = error as? ProtectAPIError else { return false }
            return true
        }
    }

    @Test func rtspURLsTargetTheConsoleHostOnPort7447() {
        #expect(client().rtspURL(forAlias: "aliasM") == "rtsp://127.0.0.1:7447/aliasM")
    }

    @Test func rtspURLsBracketIPv6ConsoleHosts() {
        let api = ProtectApiClient(
            baseURL: ProtectApiClient.baseURL(for: "[2001:db8::1]")!,
            urlSession: console.urlSession
        )
        #expect(api.rtspURL(forAlias: "alias") == "rtsp://[2001:db8::1]:7447/alias")
    }

    @Test func rtspURLsLeaveTheConsolePortBehind() {
        let api = ProtectApiClient(
            baseURL: ProtectApiClient.baseURL(for: "console.local:8443")!,
            urlSession: console.urlSession
        )
        #expect(api.rtspURL(forAlias: "alias") == "rtsp://console.local:7447/alias")
    }

    @Test func baseURLNormalizesBareHostsAndRejectsGarbage() {
        #expect(ProtectApiClient.baseURL(for: "192.168.1.1")?.absoluteString == "https://192.168.1.1/")
        #expect(ProtectApiClient.baseURL(for: " console.local:8443/ ")?.absoluteString == "https://console.local:8443/")
        #expect(ProtectApiClient.baseURL(for: "") == nil)
        #expect(ProtectApiClient.baseURL(for: "not a host") == nil)
        // Credentials must never bypass the TOFU TLS flow.
        #expect(ProtectApiClient.baseURL(for: "http://console.local") == nil)
        // Bare IPv6 literals gain brackets.
        #expect(ProtectApiClient.baseURL(for: "2001:db8::1")?.absoluteString == "https://[2001:db8::1]/")
    }

    @Test func aSessionNeverPrintsItsSecrets() {
        let session = ProtectSession(cookie: "TOKEN=abc123", csrfToken: "csrf-token-1")
        #expect(!String(describing: session).contains("abc123"))
        #expect(!String(reflecting: session).contains("abc123"))
    }

    @Test func rehostingKeepsPortPathAndTokenAndRejectsRubbish() {
        #expect(
            ProtectHTTP.rehostWebSocketURL("wss://unifi.internal:7443/ws/livestream?token=t1", host: "2001:db8::1")?
                .absoluteString == "wss://[2001:db8::1]:7443/ws/livestream?token=t1"
        )
        #expect(ProtectHTTP.rehostWebSocketURL("not a url", host: "127.0.0.1") == nil)
    }
}

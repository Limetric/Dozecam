import Foundation
import Testing

@testable import Dozecam

/// Mirrors Android's `ProtectLivestreamProviderTest` for sessions and
/// negotiation; learning the media endpoint's pin is the trust layer's,
/// behind the `mediaSession` closure.
struct ProtectLivestreamProviderTests {
    let console = StubConsole()
    let signIn = Locked<ProtectLivestreamProvider.SignIn?>(
        ProtectLivestreamProvider.SignIn(host: "127.0.0.1", username: "user", password: "pass")
    )

    func provider() -> ProtectLivestreamProvider {
        let urlSession = console.urlSession
        let signIn = self.signIn
        return ProtectLivestreamProvider(
            signIn: { signIn.value },
            consoleSession: { _ in urlSession },
            mediaSession: { _ in urlSession }
        )
    }

    func enqueueLivestream() {
        console.enqueue(json: #"{"url": "wss://unifi.internal:7443/ws/livestream?token=t1"}"#)
    }

    var logins: [StubConsole.Request] {
        console.requests.filter { $0.path == "/api/auth/login" }
    }

    @Test func negotiatesALivestreamURLPointedAtTheConsoleThatAnswered() async throws {
        console.enqueueLogin()
        enqueueLivestream()

        let connection = try await provider().connect(cameraId: "cam-1", channel: 1)

        #expect(connection.url.absoluteString == "wss://127.0.0.1:7443/ws/livestream?token=t1")
    }

    @Test func reusesTheSessionAcrossConnectionsInsteadOfLoggingInAgain() async throws {
        console.enqueueLogin()
        enqueueLivestream()
        enqueueLivestream()
        let provider = provider()

        _ = try await provider.connect(cameraId: "cam-1", channel: 1)
        _ = try await provider.connect(cameraId: "cam-1", channel: 1)

        // login, negotiate, negotiate: no second login.
        #expect(console.requests.count == 3)
        #expect(logins.count == 1)
    }

    @Test func concurrentConnectsShareOneLogin() async throws {
        console.enqueueLogin()
        enqueueLivestream()
        enqueueLivestream()
        let provider = provider()

        async let first = provider.connect(cameraId: "cam-1", channel: 1)
        async let second = provider.connect(cameraId: "cam-2", channel: 1)
        _ = try await (first, second)

        #expect(logins.count == 1)
    }

    @Test func reAuthenticatesOnceWhenTheConsoleExpiresTheSession() async throws {
        console.enqueueLogin()
        console.enqueue(status: 401, json: "expired")
        console.enqueueLogin()
        enqueueLivestream()

        let connection = try await provider().connect(cameraId: "cam-1", channel: 1)

        #expect(connection.url.absoluteString == "wss://127.0.0.1:7443/ws/livestream?token=t1")
        #expect(console.requests.count == 4)
    }

    @Test func aSecondConsecutive401IsSurfacedRatherThanRetriedForever() async throws {
        console.enqueueLogin()
        console.enqueue(status: 401, json: "expired")
        console.enqueueLogin()
        console.enqueue(status: 401, json: "expired")

        await #expect {
            try await provider().connect(cameraId: "cam-1", channel: 1)
        } throws: { error in
            (error as? ProtectAPIError)?.statusCode == 401
        }
        #expect(console.requests.count == 4)
    }

    @Test func aCameraWithNoStoredConsoleSignInIsRefused() async throws {
        signIn.value = nil

        await #expect {
            try await provider().connect(cameraId: "cam-1", channel: 1)
        } throws: { error in
            guard case .notSignedIn = error as? ProtectAPIError else { return false }
            return true
        }
        #expect(console.requests.isEmpty)
    }

    @Test func doesNotReplayASessionAtAConsoleTheUserReOnboardedTo() async throws {
        console.enqueueLogin()
        enqueueLivestream()
        let provider = provider()
        _ = try await provider.connect(cameraId: "cam-1", channel: 1)

        // The user signs into a different account; the cookie minted for the
        // previous one must not be sent on its behalf.
        signIn.value = ProtectLivestreamProvider.SignIn(
            host: "127.0.0.1",
            username: "other-user",
            password: "other-pass"
        )
        console.enqueueLogin(token: "other")
        enqueueLivestream()
        _ = try await provider.connect(cameraId: "cam-1", channel: 1)

        #expect(logins.count == 2)
        #expect(logins.last?.bodyText.contains("other-user") == true)
        #expect(console.requests.last?.header("Cookie") == "TOKEN=other")
    }

    @Test func invalidatingDropsTheSession() async throws {
        console.enqueueLogin()
        enqueueLivestream()
        console.enqueueLogin()
        enqueueLivestream()
        let provider = provider()

        _ = try await provider.connect(cameraId: "cam-1", channel: 1)
        await provider.invalidate()
        _ = try await provider.connect(cameraId: "cam-1", channel: 1)

        #expect(logins.count == 2)
    }

    /// The socket lands on a media port with its own certificate, so the
    /// trust layer is asked for a session for that URL, not the console's.
    @Test func theSocketIsOpenedWithTheSessionForTheMediaEndpoint() async throws {
        console.enqueueLogin()
        enqueueLivestream()
        let asked = Locked<[URL]>([])
        let urlSession = console.urlSession
        let media = URLSession(configuration: .ephemeral)
        let provider = ProtectLivestreamProvider(
            signIn: { ProtectLivestreamProvider.SignIn(host: "127.0.0.1", username: "user", password: "pass") },
            consoleSession: { _ in urlSession },
            mediaSession: { url in
                asked.value.append(url)
                return media
            }
        )

        let connection = try await provider.connect(cameraId: "cam-1", channel: 1)

        #expect(asked.value.map(\.absoluteString) == ["wss://127.0.0.1:7443/ws/livestream?token=t1"])
        #expect(connection.urlSession === media)
    }

    @Test func aSignInNeverPrintsItsSecrets() {
        let signIn = ProtectLivestreamProvider.SignIn(host: "127.0.0.1", username: "babycam", password: "secret")
        #expect(!String(describing: signIn).contains("secret"))
        #expect(!String(describing: signIn).contains("babycam"))
    }
}

struct ProtectPublicApiAccessTests {
    let console = StubConsole()

    func access(_ console: ProtectPublicApiAccess.Console?) -> ProtectPublicApiAccess {
        let urlSession = self.console.urlSession
        return ProtectPublicApiAccess(console: { console }, urlSession: { _ in urlSession })
    }

    @Test func runsAgainstTheStoredConsoleWithItsKey() async throws {
        console.enqueue(json: "[]")
        let access = access(.init(host: "192.168.1.1", apiKey: "key-1"))

        let cameras = try await access.withClient { api, apiKey in
            try await api.cameras(apiKey: apiKey)
        }

        #expect(cameras == [])
        #expect(console.requests.first?.url.host() == "192.168.1.1")
        #expect(console.requests.first?.header("X-API-KEY") == "key-1")
        #expect(try await access.consoleHost() == "192.168.1.1")
        #expect(try await access.hasApiKey())
    }

    @Test(arguments: [
        nil,
        ProtectPublicApiAccess.Console(host: "192.168.1.1", apiKey: nil),
        ProtectPublicApiAccess.Console(host: "http://192.168.1.1", apiKey: "key-1"),
    ])
    func nothingRunsWithoutASignedInConsoleAKeyAndAUsableAddress(stored: ProtectPublicApiAccess.Console?) async throws {
        let result = try await access(stored).withClient { _, _ in true }

        #expect(result == nil)
        #expect(console.requests.isEmpty)
    }
}

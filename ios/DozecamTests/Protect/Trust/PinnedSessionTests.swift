import Foundation
import Security
import Testing

@testable import Dozecam

struct CertificateFingerprintTests {
    /// Uppercase hex pairs joined with `:`, as Android's
    /// `sha256Fingerprint()` and `openssl x509 -fingerprint -sha256` print.
    @Test func matchesTheFormatAndDigestOpenSSLAndAndroidProduce() throws {
        #expect(
            CertificateFingerprint.sha256(der: try TestCertificates.der("console-a"))
                == TestCertificates.consoleAFingerprint)
        #expect(
            CertificateFingerprint.sha256(try TestCertificates.certificate("console-b"))
                == TestCertificates.consoleBFingerprint)
    }

    @Test func formatIsThirtyTwoZeroPaddedUppercasePairs() {
        let fingerprint = CertificateFingerprint.sha256(der: Data())
        // SHA-256 of nothing: e3b0c442…b855.
        #expect(fingerprint.hasPrefix("E3:B0:C4:42:"))
        #expect(fingerprint.hasSuffix(":B8:55"))
        let pairs = fingerprint.split(separator: ":")
        #expect(pairs.count == 32)
        #expect(pairs.allSatisfy { $0.count == 2 && $0.allSatisfy { $0.isNumber || ("A"..."F").contains($0) } })
    }

    @Test func theLeafOfAServerTrustIsFingerprinted() throws {
        #expect(
            CertificateFingerprint.leaf(of: try TestCertificates.trust("console-a"))
                == TestCertificates.consoleAFingerprint)
    }
}

/// The delegate maps a real `SecTrust` onto accept, refuse and ask, through
/// the same challenge callbacks URLSession calls.
struct PinnedSessionDelegateTests {
    let console = TofuEndpoint(host: "192.168.1.1", port: 443)
    let media = TofuEndpoint(host: "192.168.1.1", port: 7443)

    @Test func firstContactNeedsConfirmation() throws {
        let store = TofuTrustStore(fileURL: nil)
        let delegate = PinnedSessionDelegate(store: store, role: .console())
        let (disposition, credential) = delegate.respond(to: try challenge(presenting: "console-a", at: console))
        #expect(disposition == .cancelAuthenticationChallenge)
        #expect(credential == nil)
        #expect(
            delegate.failure(at: console)
                == .unpinned(endpoint: console, presented: TestCertificates.consoleAFingerprint))
        #expect(store.pin(for: console) == nil)
    }

    @Test func thePinnedCertificateIsAcceptedWithoutHostnameVerification() throws {
        let store = TofuTrustStore(fileURL: nil)
        store.confirmConsole(console, fingerprint: TestCertificates.consoleAFingerprint)
        let delegate = PinnedSessionDelegate(store: store, role: .console())
        // The certificate's CN is not the address it is reached on.
        let (disposition, credential) = delegate.respond(to: try challenge(presenting: "console-a", at: console))
        #expect(disposition == .useCredential)
        #expect(credential != nil)
        #expect(delegate.failure(at: console) == nil)
    }

    @Test func theConfirmedCertificateIsAcceptedButNotPinned() throws {
        let store = TofuTrustStore(fileURL: nil)
        let delegate = PinnedSessionDelegate(
            store: store, role: .console(confirming: TestCertificates.consoleAFingerprint))
        #expect(delegate.respond(to: try challenge(presenting: "console-a", at: console)).0 == .useCredential)
        #expect(store.pin(for: console) == nil)
    }

    @Test func aChangedCertificateNeedsConfirmationWithBothFingerprints() throws {
        let store = TofuTrustStore(fileURL: nil)
        store.confirmConsole(console, fingerprint: TestCertificates.consoleAFingerprint)
        let delegate = PinnedSessionDelegate(store: store, role: .console())
        #expect(
            delegate.respond(to: try challenge(presenting: "console-b", at: console)).0
                == .cancelAuthenticationChallenge)
        let failure = try #require(delegate.failure(at: console))
        #expect(failure.needsConfirmation)
        #expect(failure.pinnedFingerprint == TestCertificates.consoleAFingerprint)
        #expect(failure.presentedFingerprint == TestCertificates.consoleBFingerprint)
    }

    @Test func aMediaSessionLearnsThenRefusesAndForgetsAChange() throws {
        let store = TofuTrustStore(fileURL: nil)
        store.confirmConsole(console, fingerprint: TestCertificates.consoleAFingerprint)
        let delegate = PinnedSessionDelegate(store: store, role: .media(vouchedBy: console))

        #expect(delegate.respond(to: try challenge(presenting: "console-b", at: media)).0 == .useCredential)
        #expect(store.fingerprint(for: media) == TestCertificates.consoleBFingerprint)

        #expect(
            delegate.respond(to: try challenge(presenting: "console-a", at: media)).0
                == .cancelAuthenticationChallenge)
        #expect(store.pin(for: media) == nil)
        #expect(
            delegate.failure(at: media)
                == .mediaChanged(
                    endpoint: media, pinned: TestCertificates.consoleBFingerprint,
                    presented: TestCertificates.consoleAFingerprint))
    }

    @Test func otherChallengesAreLeftToURLSession() {
        let delegate = PinnedSessionDelegate(store: TofuTrustStore(fileURL: nil), role: .console())
        let space = URLProtectionSpace(
            host: "192.168.1.1", port: 443, protocol: NSURLProtectionSpaceHTTPS, realm: nil,
            authenticationMethod: NSURLAuthenticationMethodHTTPBasic)
        #expect(delegate.respond(to: challenge(space)).0 == .performDefaultHandling)
    }

    /// Both challenge callbacks, as URLSession calls them for REST and
    /// WebSocket tasks, answer the same.
    @Test func sessionAndTaskLevelCallbacksAgree() async throws {
        let store = TofuTrustStore(fileURL: nil)
        store.confirmConsole(console, fingerprint: TestCertificates.consoleAFingerprint)
        let session = PinnedSessionFactory(store: store).consoleSession()
        defer { session.invalidate() }
        let task = session.urlSession.webSocketTask(with: try #require(URL(string: "wss://192.168.1.1/ws")))

        for name in ["console-a", "console-b"] {
            let challenge = try challenge(presenting: name, at: console)
            let expected: URLSession.AuthChallengeDisposition =
                name == "console-a" ? .useCredential : .cancelAuthenticationChallenge
            let sessionLevel = await withCheckedContinuation { continuation in
                session.delegate.urlSession(session.urlSession, didReceive: challenge) { disposition, _ in
                    continuation.resume(returning: disposition)
                }
            }
            let taskLevel = await withCheckedContinuation { continuation in
                session.delegate.urlSession(session.urlSession, task: task, didReceive: challenge) { disposition, _ in
                    continuation.resume(returning: disposition)
                }
            }
            #expect(sessionLevel == expected)
            #expect(taskLevel == expected)
        }
    }

    @Test func aCancelledRequestIsTracedBackToTheRefusal() throws {
        let session = PinnedSessionFactory(store: TofuTrustStore(fileURL: nil)).consoleSession()
        defer { session.invalidate() }
        _ = session.delegate.respond(to: try challenge(presenting: "console-a", at: console))

        let cancelled = URLError(
            .cancelled,
            userInfo: [NSURLErrorFailingURLErrorKey: try #require(URL(string: "https://192.168.1.1/api/auth/login"))])
        #expect(
            session.trustFailure(in: cancelled)
                == .unpinned(endpoint: console, presented: TestCertificates.consoleAFingerprint))
        // Another endpoint, or another kind of failure, is not a refusal.
        let elsewhere = URLError(
            .cancelled, userInfo: [NSURLErrorFailingURLErrorKey: try #require(URL(string: "https://192.168.1.2/"))])
        #expect(session.trustFailure(in: elsewhere) == nil)
        #expect(session.trustFailure(in: CocoaError(.fileNoSuchFile)) == nil)
    }

    /// The seam between the clients and the trust layer: a refused handshake
    /// reaches the caller of a real client call as the refusal, not as the
    /// client's `ProtectAPIError.unreachable`.
    @Test func aRefusalSurfacesThroughAProtectClientCall() async throws {
        let stub = StubConsole()
        let configuration = stub.configuration
        let session = PinnedSessionFactory(store: TofuTrustStore(fileURL: nil), configuration: { configuration })
            .consoleSession()
        defer { session.invalidate() }
        _ = session.delegate.respond(to: try challenge(presenting: "console-a", at: console))
        stub.enqueue(.failure(.cancelled))
        let client = ProtectApiClient(
            baseURL: try #require(URL(string: "https://192.168.1.1")), urlSession: session.urlSession)

        await #expect(
            throws: TofuTrustError.unpinned(endpoint: console, presented: TestCertificates.consoleAFingerprint)
        ) {
            try await session.surfacingTrustFailures {
                _ = try await client.login(username: "user", password: "pass")
            }
        }
    }

    @Test func surfacingTrustFailuresRethrowsTheRefusal() async throws {
        let session = PinnedSessionFactory(store: TofuTrustStore(fileURL: nil)).consoleSession()
        defer { session.invalidate() }
        _ = session.delegate.respond(to: try challenge(presenting: "console-a", at: console))
        let url = try #require(URL(string: "https://192.168.1.1/"))

        await #expect(
            throws: TofuTrustError.unpinned(endpoint: console, presented: TestCertificates.consoleAFingerprint)
        ) {
            try await session.surfacingTrustFailures { () async throws -> Void in
                throw URLError(.cancelled, userInfo: [NSURLErrorFailingURLErrorKey: url])
            }
        }
        await #expect(throws: URLError(.timedOut)) {
            try await session.surfacingTrustFailures { () async throws -> Void in throw URLError(.timedOut) }
        }
    }

    /// A `@MainActor` model can wrap its client calls, touching its own state
    /// inside: the body runs on the caller's actor.
    @MainActor @Test func surfacingTrustFailuresRunsOnTheCallersActor() async throws {
        let session = PinnedSessionFactory(store: TofuTrustStore(fileURL: nil)).consoleSession()
        defer { session.invalidate() }
        let model = MainActorCounter()
        let result = try await session.surfacingTrustFailures {
            MainActor.assertIsolated()
            model.count += 1
            return model.count
        }
        #expect(result == 1)
    }

    // MARK: Challenges

    func challenge(presenting name: String, at endpoint: TofuEndpoint) throws -> URLAuthenticationChallenge {
        challenge(
            ServerTrustProtectionSpace(
                host: endpoint.host, port: endpoint.port, protocol: NSURLProtectionSpaceHTTPS, realm: nil,
                authenticationMethod: NSURLAuthenticationMethodServerTrust),
            trust: try TestCertificates.trust(name))
    }

    func challenge(_ space: URLProtectionSpace, trust: SecTrust? = nil) -> URLAuthenticationChallenge {
        (space as? ServerTrustProtectionSpace)?.trust = trust
        return URLAuthenticationChallenge(
            protectionSpace: space, proposedCredential: nil, previousFailureCount: 0, failureResponse: nil, error: nil,
            sender: ChallengeSender())
    }
}

@MainActor
private final class MainActorCounter {
    var count = 0
}

/// A protection space carrying a server trust, which only URLSession can
/// otherwise make.
private final class ServerTrustProtectionSpace: URLProtectionSpace, @unchecked Sendable {
    var trust: SecTrust?
    override var serverTrust: SecTrust? { trust }
}

private final class ChallengeSender: NSObject, URLAuthenticationChallengeSender {
    func use(_ credential: URLCredential, for challenge: URLAuthenticationChallenge) {}
    func continueWithoutCredential(for challenge: URLAuthenticationChallenge) {}
    func cancel(_ challenge: URLAuthenticationChallenge) {}
}

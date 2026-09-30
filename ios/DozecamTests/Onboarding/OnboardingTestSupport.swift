import Foundation
import Security
import Synchronization

@testable import Dozecam

/// A local-network probe that plays one script per connection, then leaves
/// the connection open or ends it.
final class OnboardingProbe: LocalNetworkProbe {
    struct Script: Sendable {
        let evidence: [LocalNetworkEvidence]
        var staysOpen = false
    }

    private let scripts: Mutex<[Script]>
    private let connects = Mutex<[String]>([])

    init(_ scripts: [Script]) {
        self.scripts = Mutex(scripts)
    }

    /// Every `host:port` probed, oldest first.
    var connections: [String] { connects.withLock { $0 } }

    func connect(host: String, port: UInt16) -> AsyncStream<LocalNetworkEvidence> {
        connects.withLock { $0.append("\(host):\(port)") }
        let script = scripts.withLock { $0.isEmpty ? Script(evidence: []) : $0.removeFirst() }
        let (stream, continuation) = AsyncStream.makeStream(of: LocalNetworkEvidence.self)
        for evidence in script.evidence { continuation.yield(evidence) }
        if !script.staysOpen { continuation.finish() }
        return stream
    }
}

/// An onboarding model wired to a `StubConsole` at 192.168.1.1, with its own
/// isolated stores. Each pinned session it builds can have a certificate
/// staged for its first handshake: the stub does no TLS, so the handshake
/// is played to the session's delegate as URLSession would, and the refusal
/// then surfaces from the cancelled request queued after it.
@MainActor
final class OnboardingHarness {
    let stub = StubConsole()
    let credentials: InMemoryCredentialsStore
    let probe: OnboardingProbe
    let dependencies: AppDependencies
    private(set) var model: OnboardingModel!
    /// Test certificate names ("console-a", "console-b"), one per session
    /// built, oldest first; nil for a session whose handshake is not staged.
    var handshakes: [String?] = []
    /// Runs as each session is built, before any of its requests.
    var onSession: (() -> Void)?
    /// The fingerprint each session was built to accept, oldest first.
    private(set) var sessionsConfirming: [String?] = []

    static let console = TofuEndpoint(host: "192.168.1.1", port: 443)

    init(
        stored: ProtectCredentials? = nil,
        localNetwork: LocalNetworkAccessStatus? = .granted,
        probe: [OnboardingProbe.Script] = []
    ) {
        credentials = InMemoryCredentialsStore(stored)
        self.probe = OnboardingProbe(probe)
        dependencies = AppDependencies.isolated(credentials: credentials, localNetworkProbe: self.probe)
        switch localNetwork {
        case .granted: dependencies.localNetwork.record(.reachable)
        case .denied: dependencies.localNetwork.record(.denied)
        case .unknown, nil: break
        }
        model = makeModel()
    }

    /// A model over the same stores, as the app's next visit would build.
    func makeModel() -> OnboardingModel {
        OnboardingModel(dependencies: dependencies) { [unowned self] confirming in
            sessionsConfirming.append(confirming)
            let stub = stub
            let session = PinnedSessionFactory(store: dependencies.trust, configuration: { stub.configuration })
                .consoleSession(confirming: confirming)
            if !handshakes.isEmpty, let name = handshakes.removeFirst() {
                _ = session.delegate.respond(to: Self.challenge(presenting: name, at: Self.console))
            }
            onSession?()
            return session
        }
    }

    func replaceModel() {
        model = makeModel()
    }

    /// Fills the sign-in form.
    func fill(host: String = "192.168.1.1", username: String = "user", password: String = "pass") {
        model.host = host
        model.username = username
        model.password = password
    }

    /// Requests, as `METHOD path`.
    var requestLines: [String] { stub.requests.map { "\($0.method) \($0.path)" } }

    // MARK: Challenges

    static func challenge(presenting name: String, at endpoint: TofuEndpoint) -> URLAuthenticationChallenge {
        let space = ServerTrustProtectionSpace(
            host: endpoint.host, port: endpoint.port, protocol: NSURLProtectionSpaceHTTPS, realm: nil,
            authenticationMethod: NSURLAuthenticationMethodServerTrust)
        // The certificates ship in the test bundle; a missing one fails the
        // test at the expectation on the refusal.
        space.trust = try? TestCertificates.trust(name)
        return URLAuthenticationChallenge(
            protectionSpace: space, proposedCredential: nil, previousFailureCount: 0, failureResponse: nil, error: nil,
            sender: ChallengeSender())
    }
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

/// Waits, without blocking the main actor, until `condition` holds or a
/// second has passed; returns whether it held.
@MainActor
func eventually(_ condition: () -> Bool) async -> Bool {
    for _ in 0..<200 {
        if condition() { return true }
        try? await Task.sleep(for: .milliseconds(5))
    }
    return condition()
}

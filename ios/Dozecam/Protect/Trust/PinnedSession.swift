import Foundation
import Security
import Synchronization

/// Builds the TOFU-pinned `URLSession`s the Protect clients take, REST and
/// `URLSessionWebSocketTask` alike.
///
/// Android reference: `protectHttpClient`, `ProtectLivestreamProvider`.
struct PinnedSessionFactory: Sendable {
    let store: TofuTrustStore
    /// Each session gets a fresh configuration; ephemeral, so nothing (TLS
    /// sessions, cookies, cache) carries over from one pinned session to
    /// another.
    let configuration: @Sendable () -> URLSessionConfiguration

    init(
        store: TofuTrustStore = .shared,
        configuration: @escaping @Sendable () -> URLSessionConfiguration = { .ephemeral }
    ) {
        self.store = store
        self.configuration = configuration
    }

    /// A session for the console's REST API. `confirming` is the fingerprint
    /// the user has just accepted at the prompt, trusted for this session
    /// only; once a sign-in through it succeeds, pin it with
    /// `store.confirmConsole(_:fingerprint:)`.
    func consoleSession(confirming fingerprint: String? = nil) -> PinnedSession {
        PinnedSession(
            delegate: PinnedSessionDelegate(store: store, role: .console(confirming: fingerprint)),
            configuration: configuration())
    }

    /// A session for the media endpoints behind URLs the pinned `console`
    /// minted (the livestream WebSocket): their certificates are learned on
    /// first use and a changed one is forgotten, to be relearned on the next
    /// negotiation. Only for URLs the console handed back over a pinned
    /// session.
    func mediaSession(vouchedBy console: TofuEndpoint) -> PinnedSession {
        PinnedSession(
            delegate: PinnedSessionDelegate(store: store, role: .media(vouchedBy: console)),
            configuration: configuration())
    }
}

/// A pinned `URLSession`, and the way back from the `URLError` it fails with
/// to the `TofuTrustError` behind it.
///
/// URLSession keeps its delegate until it is invalidated: call `invalidate()`
/// when done with the session.
final class PinnedSession: Sendable {
    /// Hand this to the Protect clients.
    let urlSession: URLSession
    let delegate: PinnedSessionDelegate

    init(delegate: PinnedSessionDelegate, configuration: URLSessionConfiguration) {
        self.delegate = delegate
        // A nil queue: URLSession makes a serial one for the callbacks, which
        // are nonisolated (#58).
        urlSession = URLSession(configuration: configuration, delegate: delegate, delegateQueue: nil)
    }

    /// The certificate refusal behind `error`, if a refusal is what it is.
    /// URLSession reports a cancelled challenge as `URLError.cancelled`
    /// carrying the failing URL; the refusal is looked up by its endpoint.
    /// The Protect clients wrap transport errors in
    /// `ProtectAPIError.unreachable`, so that is unwrapped first.
    func trustFailure(in error: any Error) -> TofuTrustError? {
        if let error = error as? TofuTrustError { return error }
        if case .unreachable(let wrapped) = error as? ProtectAPIError { return trustFailure(in: wrapped) }
        guard let urlError = error as? URLError,
            let url = urlError.failingURL,
            let endpoint = TofuEndpoint(url: url)
        else { return nil }
        return delegate.failure(at: endpoint)
    }

    /// Runs `body`, rethrowing a certificate refusal as the `TofuTrustError`
    /// behind it and anything else as it was.
    /// `body` runs on the caller's actor, so a `@MainActor` model can call
    /// its clients from it directly.
    func surfacingTrustFailures<T>(
        isolation: isolated (any Actor)? = #isolation,
        _ body: () async throws -> T
    ) async throws -> T {
        do {
            return try await body()
        } catch {
            throw trustFailure(in: error) ?? error
        }
    }

    /// Lets running tasks finish, then releases the delegate.
    func invalidate() {
        urlSession.finishTasksAndInvalidate()
    }
}

/// Applies TOFU pinning to every server-trust challenge of a session, at the
/// session and the task level, so REST requests and WebSocket upgrades are
/// judged alike. Hostname verification is off: only the leaf certificate's
/// fingerprint decides.
///
/// Callbacks arrive on URLSession's delegate queue. Nothing here is
/// actor-isolated, and all shared state is behind a `Mutex`.
///
/// A task given its own delegate (`URLSessionTask.delegate`) must not answer
/// authentication challenges, or it would bypass this one.
final class PinnedSessionDelegate: NSObject, URLSessionTaskDelegate, Sendable {
    let store: TofuTrustStore
    let role: TofuRole
    /// The latest refusal per endpoint, cleared when the endpoint is accepted.
    private let failures = Mutex<[TofuEndpoint: TofuTrustError]>([:])

    init(store: TofuTrustStore, role: TofuRole) {
        self.store = store
        self.role = role
    }

    /// The latest certificate refusal at `endpoint`, if its latest handshake
    /// was refused.
    func failure(at endpoint: TofuEndpoint) -> TofuTrustError? {
        failures.withLock { $0[endpoint] }
    }

    /// The decision for one server trust, recorded for `failure(at:)`.
    func evaluate(_ trust: SecTrust, at endpoint: TofuEndpoint) -> Result<Void, TofuTrustError> {
        let result = store.evaluate(trust, at: endpoint, role: role)
        failures.withLock { failures in
            switch result {
            case .success: failures[endpoint] = nil
            case .failure(let error): failures[endpoint] = error
            }
        }
        return result
    }

    /// The answer to a challenge: server trust is judged by the pin, every
    /// other kind is left to URLSession.
    func respond(to challenge: URLAuthenticationChallenge) -> (URLSession.AuthChallengeDisposition, URLCredential?) {
        let space = challenge.protectionSpace
        guard space.authenticationMethod == NSURLAuthenticationMethodServerTrust else {
            return (.performDefaultHandling, nil)
        }
        guard let trust = space.serverTrust else { return (.cancelAuthenticationChallenge, nil) }
        let endpoint = TofuEndpoint(host: space.host, port: space.port)
        switch evaluate(trust, at: endpoint) {
        case .success: return (.useCredential, URLCredential(trust: trust))
        case .failure: return (.cancelAuthenticationChallenge, nil)
        }
    }

    /// Whether a redirect may be followed: only within the endpoint the
    /// request was made to. Requests to a console carry its credentials
    /// (cookie, CSRF token, API key) as headers, which URLSession would replay
    /// to wherever a redirect points; pinning proves the destination's
    /// certificate, not that it is the console those credentials are for
    /// (shared/spec/protect.md: they are sent only to that console).
    static func allowsRedirect(from original: URL?, to destination: URL?) -> Bool {
        guard let original, let destination,
            let from = TofuEndpoint(url: original), let to = TofuEndpoint(url: destination)
        else { return false }
        return from == to && original.scheme?.lowercased() == destination.scheme?.lowercased()
    }

    func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        willPerformHTTPRedirection response: HTTPURLResponse,
        newRequest request: URLRequest,
        completionHandler: @escaping @Sendable (URLRequest?) -> Void
    ) {
        // nil hands the redirect response itself back to the caller.
        completionHandler(Self.allowsRedirect(from: task.originalRequest?.url, to: request.url) ? request : nil)
    }

    func urlSession(
        _ session: URLSession,
        didReceive challenge: URLAuthenticationChallenge,
        completionHandler: @escaping @Sendable (URLSession.AuthChallengeDisposition, URLCredential?) -> Void
    ) {
        let (disposition, credential) = respond(to: challenge)
        completionHandler(disposition, credential)
    }

    func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        didReceive challenge: URLAuthenticationChallenge,
        completionHandler: @escaping @Sendable (URLSession.AuthChallengeDisposition, URLCredential?) -> Void
    ) {
        let (disposition, credential) = respond(to: challenge)
        completionHandler(disposition, credential)
    }
}

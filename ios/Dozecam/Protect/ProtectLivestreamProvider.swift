import Foundation

/// Turns a stored console login into a ready-to-open livestream WebSocket.
///
/// The console mints a single-use token per negotiation, so every connection,
/// including each reconnect, comes back through here. The login session
/// behind it is reused until the console rejects it, then renewed once,
/// rather than logging in per attempt (shared/spec/protect.md, "The
/// livestream").
///
/// Connects are serialised, like Android's mutex: two cameras reconnecting
/// at once share one login instead of racing two.
actor ProtectLivestreamProvider {
    /// The stored console sign-in. Its description is redacted.
    struct SignIn: Equatable, Sendable, CustomStringConvertible {
        /// The console address as the user typed it.
        let host: String
        let username: String
        let password: String

        var description: String { "SignIn(host: \(host), username: <redacted>)" }
    }

    /// A negotiated socket URL and the session that must open it.
    struct Connection: Sendable {
        let url: URL
        let urlSession: URLSession
    }

    private let signIn: @Sendable () async throws -> SignIn?
    private let consoleSession: @Sendable (_ baseURL: URL) async throws -> URLSession
    private let mediaSession: @Sendable (_ livestreamURL: URL) async throws -> URLSession

    private var session: ProtectSession?
    /// Whose `session` it is. Re-onboarding to another console or account
    /// rewrites the stored sign-in underneath, and a cookie minted for the
    /// previous one must never be replayed at the new one.
    private var sessionOwner: (host: String, username: String)?

    private var busy = false
    private var waiting: [CheckedContinuation<Void, Never>] = []

    /// - Parameters:
    ///   - signIn: reads the stored sign-in, or nil when none is stored.
    ///   - consoleSession: the session pinned to the console at `baseURL`.
    ///   - mediaSession: the session to open the negotiated socket with. The
    ///     socket lands on a media port with its own certificate, which the
    ///     trust layer learns on first use: the pinned console minted the URL,
    ///     so it vouches for it (shared/spec/protect.md, "Certificate
    ///     pinning").
    init(
        signIn: @escaping @Sendable () async throws -> SignIn?,
        consoleSession: @escaping @Sendable (_ baseURL: URL) async throws -> URLSession,
        mediaSession: @escaping @Sendable (_ livestreamURL: URL) async throws -> URLSession
    ) {
        self.signIn = signIn
        self.consoleSession = consoleSession
        self.mediaSession = mediaSession
    }

    func connect(cameraId: String, channel: Int) async throws -> Connection {
        await lock()
        defer { unlock() }

        guard let saved = try await signIn() else {
            throw ProtectAPIError.notSignedIn("This camera needs a Protect console sign-in")
        }
        guard let baseURL = ProtectApiClient.baseURL(for: saved.host) else {
            throw ProtectAPIError.notSignedIn("Stored console address \(saved.host) is unusable")
        }
        if sessionOwner?.host != saved.host || sessionOwner?.username != saved.username {
            session = nil
            sessionOwner = (saved.host, saved.username)
        }
        let api = ProtectApiClient(baseURL: baseURL, urlSession: try await consoleSession(baseURL))

        let url: URL
        do {
            url = try await api.livestreamURL(currentSession(api, saved), cameraId: cameraId, channel: channel)
        } catch ProtectAPIError.unauthorized {
            // The session aged out while the monitor sat open; one fresh
            // login beats stranding the user on an error that retrying cannot
            // clear. A second 401 is surfaced.
            session = nil
            url = try await api.livestreamURL(currentSession(api, saved), cameraId: cameraId, channel: channel)
        }
        return Connection(url: url, urlSession: try await mediaSession(url))
    }

    /// Drops the cached session; the next connect logs in again.
    func invalidate() {
        session = nil
        sessionOwner = nil
    }

    private func currentSession(_ api: ProtectApiClient, _ saved: SignIn) async throws -> ProtectSession {
        if let session { return session }
        let fresh = try await api.login(username: saved.username, password: saved.password)
        session = fresh
        return fresh
    }

    /// An actor alone would interleave two connects at their first `await`.
    private func lock() async {
        guard busy else {
            busy = true
            return
        }
        await withCheckedContinuation { waiting.append($0) }
    }

    private func unlock() {
        if waiting.isEmpty {
            busy = false
        } else {
            waiting.removeFirst().resume()
        }
    }
}

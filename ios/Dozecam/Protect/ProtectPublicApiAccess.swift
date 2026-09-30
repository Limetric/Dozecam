import Foundation

/// The signed-in console's public Integration API, assembled on demand.
///
/// Everything needed to talk to it (the address, the pinned certificate and
/// the API key) is stored rather than held, and any of the three can change
/// while the viewer is open: re-onboarding rewrites the credentials
/// underneath, and a key minted for the previous console must never be
/// replayed at the new one. So the client is built per call rather than
/// cached, which costs a Keychain read and buys never being wrong about
/// whose console this is.
struct ProtectPublicApiAccess: Sendable {
    /// What is stored for the signed-in console: the address as the user
    /// typed it, and the API key, if one was minted.
    struct Console: Equatable, Sendable, CustomStringConvertible {
        let host: String
        let apiKey: String?

        var description: String { "Console(host: \(host), apiKey: \(apiKey == nil ? "none" : "<redacted>"))" }
    }

    private let console: @Sendable () async throws -> Console?
    private let urlSession: @Sendable (_ baseURL: URL) async throws -> URLSession

    /// - Parameters:
    ///   - console: reads the stored console, or nil when none is signed in.
    ///   - urlSession: the session pinned to the console at `baseURL`.
    init(
        console: @escaping @Sendable () async throws -> Console?,
        urlSession: @escaping @Sendable (_ baseURL: URL) async throws -> URLSession
    ) {
        self.console = console
        self.urlSession = urlSession
    }

    /// Runs `body` against the console, or returns nil when there is no
    /// console signed in, no API key, or an address that cannot be parsed.
    /// `body` runs in the caller's isolation.
    func withClient<T>(
        isolation: isolated (any Actor)? = #isolation,
        _ body: (_ api: ProtectPublicApiClient, _ apiKey: String) async throws -> T
    ) async throws -> T? {
        guard let saved = try await console(), let apiKey = saved.apiKey,
            let baseURL = ProtectApiClient.baseURL(for: saved.host)
        else { return nil }
        let api = ProtectPublicApiClient(baseURL: baseURL, urlSession: try await urlSession(baseURL))
        return try await body(api, apiKey)
    }

    /// The console this would talk to, or nil when none is signed in.
    func consoleHost() async throws -> String? {
        try await console()?.host
    }

    func hasApiKey() async throws -> Bool {
        try await console()?.apiKey != nil
    }
}

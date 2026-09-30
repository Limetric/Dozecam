import Foundation

/// Why a console exchange failed, in the terms callers act on. Onboarding
/// renews a login on `unauthorized`, falls back to the legacy API when the
/// public one is `forbidden` or `notFound` (older firmware, an account that
/// cannot mint a key), and tells the user about the rest. The livestream
/// renews its session once on `unauthorized`.
///
/// Messages never carry a response body, a cookie or a key: bodies can hold
/// stream tokens, and these strings end up on screen and in logs.
enum ProtectAPIError: Error, Equatable, Sendable {
    /// 401: a wrong password, an expired login session, or a revoked API key.
    case unauthorized(String)
    /// 403: the account lacks the rights, e.g. to mint an API key or manage
    /// a camera's streams.
    case forbidden(String)
    /// 404: this console has no such endpoint (Protect before 5.3 for the
    /// public API) or no such camera.
    case notFound(String)
    /// Any other non-2xx answer.
    case rejected(status: Int, String)
    /// No HTTP answer at all: no route, refused, timed out, local network
    /// access denied, or a TLS failure. The `URLError` is kept whole so the
    /// trust layer can still tell a certificate prompt from a dead host.
    case unreachable(URLError)
    /// The console answered 2xx with something Dozecam cannot use: an
    /// unreadable body, no session cookie, no livestream URL.
    case invalidResponse(String)
    /// Nothing to talk to: no console is signed in, or its stored address
    /// is unusable.
    case notSignedIn(String)

    /// The HTTP status behind the failure, when the console answered.
    var statusCode: Int? {
        switch self {
        case .unauthorized: 401
        case .forbidden: 403
        case .notFound: 404
        case .rejected(let status, _): status
        case .unreachable, .invalidResponse, .notSignedIn: nil
        }
    }

    /// Maps a non-2xx status to its case, with the message the user sees.
    static func status(_ status: Int, _ message: String) -> ProtectAPIError {
        switch status {
        case 401: .unauthorized(message)
        case 403: .forbidden(message)
        case 404: .notFound(message)
        default: .rejected(status: status, message)
        }
    }
}

extension ProtectAPIError: LocalizedError {
    var errorDescription: String? {
        switch self {
        case .unauthorized(let message), .forbidden(let message), .notFound(let message),
            .rejected(_, let message), .invalidResponse(let message), .notSignedIn(let message):
            message
        case .unreachable(let error):
            error.localizedDescription
        }
    }
}

/// The plumbing both console clients share.
enum ProtectHTTP {
    static let jsonContentType = "application/json; charset=utf-8"

    /// Runs one console exchange on the injected session (which carries the
    /// TOFU pin), and returns the answer whatever its status: each call
    /// words its own failure. Transport failures become `.unreachable`; a
    /// cancelled task surfaces as `CancellationError`, not as a network
    /// fault the user would be told about.
    static func exchange(_ request: URLRequest, on urlSession: URLSession) async throws -> (HTTPURLResponse, Data) {
        var request = request
        // Cookies are sent by hand from a `ProtectSession`. Letting the
        // session's cookie store do it would replay one console's login at
        // another after re-onboarding.
        request.httpShouldHandleCookies = false
        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await urlSession.data(for: request)
        } catch let error as URLError {
            if error.code == .cancelled, Task.isCancelled { throw CancellationError() }
            throw ProtectAPIError.unreachable(error)
        }
        guard let http = response as? HTTPURLResponse else {
            throw ProtectAPIError.invalidResponse("The console sent something other than an HTTP answer")
        }
        return (http, data)
    }

    static func isSuccess(_ response: HTTPURLResponse) -> Bool {
        (200..<300).contains(response.statusCode)
    }

    /// Decodes a 2xx body, naming only what was expected on failure: the body
    /// itself may carry tokens.
    static func decode<T: Decodable>(_ type: T.Type, from data: Data, what: String) throws -> T {
        do {
            return try JSONDecoder().decode(type, from: data)
        } catch {
            throw ProtectAPIError.invalidResponse("The console sent an unreadable \(what)")
        }
    }

    static func jsonBody<T: Encodable>(_ value: T) throws -> Data {
        try JSONEncoder().encode(value)
    }

    /// `baseURL` with its path replaced, as `HttpUrl.encodedPath` does on
    /// Android: the stored address carries only scheme, host and port.
    static func endpoint(_ baseURL: URL, path: String, query: [URLQueryItem]? = nil) -> URL {
        var components = URLComponents(url: baseURL, resolvingAgainstBaseURL: false) ?? URLComponents()
        components.percentEncodedPath = path
        components.queryItems = query
        // Only reachable with a hand-built base URL; `baseURL(for:)` never
        // yields one that fails here.
        return components.url ?? baseURL
    }

    /// One path segment, with `/` and the rest of the reserved set escaped,
    /// like OkHttp's `addPathSegment`.
    static func segment(_ value: String) -> String {
        var allowed = CharacterSet.urlPathAllowed
        allowed.remove("/")
        return value.addingPercentEncoding(withAllowedCharacters: allowed) ?? value
    }

    /// The console's host as a bare name or address: an IPv6 literal comes
    /// back without its brackets.
    static func host(of url: URL) -> String {
        url.host(percentEncoded: false) ?? ""
    }

    /// Re-points a console-minted WebSocket URL at `host`, the console address
    /// that actually answered. Only the host moves: the port and path carry
    /// the WebSocket port and the single-use token, and the console routinely
    /// mints an internal hostname that resolves nowhere else on the network,
    /// the same trap `ProtectPublicApiClient.streamURL(forRtsps:)` avoids.
    ///
    /// Nil when the minted URL will not parse.
    static func rehostWebSocketURL(_ minted: String, host: String) -> URL? {
        guard let uri = URLComponents(string: minted.trimmingCharacters(in: .whitespacesAndNewlines)),
            let scheme = uri.scheme, !uri.percentEncodedPath.isEmpty
        else { return nil }
        let port = uri.port.map { ":\($0)" } ?? ""
        let query = uri.percentEncodedQuery.map { "?\($0)" } ?? ""
        return URL(string: "\(scheme)://\(bracketed(host))\(port)\(uri.percentEncodedPath)\(query)")
    }

    /// Plain RTSP on the console's 7447 port. Both APIs converge here: the
    /// legacy one hands back a bare alias, and the public one's `rtsps://`
    /// URL (7441) is not a stream the players here can open
    /// (shared/spec/protect.md, "Stream URLs").
    static func rtspURL(host: String, alias: String) -> String {
        "rtsp://\(bracketed(host)):7447/\(alias)"
    }

    /// IPv6 literals need brackets in a URL authority.
    private static func bracketed(_ host: String) -> String {
        host.contains(":") && !host.hasPrefix("[") ? "[\(host)]" : host
    }
}

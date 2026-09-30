import Foundation

/// One quality channel of a camera on the legacy API. Protect numbers them
/// High, Medium, Low.
struct ProtectChannel: Decodable, Equatable, Sendable {
    let id: Int
    let name: String
    let isRtspEnabled: Bool
    let rtspAlias: String?

    init(id: Int, name: String = "", isRtspEnabled: Bool = false, rtspAlias: String? = nil) {
        self.id = id
        self.name = name
        self.isRtspEnabled = isRtspEnabled
        self.rtspAlias = rtspAlias
    }

    // The private API is unofficial and shifts between releases: anything
    // but the id may be missing or null.
    init(from decoder: any Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(Int.self, forKey: .id)
        name = try container.decodeIfPresent(String.self, forKey: .name) ?? ""
        isRtspEnabled = try container.decodeIfPresent(Bool.self, forKey: .isRtspEnabled) ?? false
        rtspAlias = try container.decodeIfPresent(String.self, forKey: .rtspAlias)
    }

    private enum CodingKeys: String, CodingKey {
        case id, name, isRtspEnabled, rtspAlias
    }
}

/// A camera as the legacy API's bootstrap (or a camera PATCH) describes it.
struct ProtectCamera: Decodable, Equatable, Sendable {
    let id: String
    /// Empty when the console has none. A `null` name is read as empty
    /// rather than failing the whole bootstrap: the public API sends `null`
    /// for an unnamed camera, and either way the camera still onboards,
    /// shown as "Camera".
    let name: String
    let channels: [ProtectChannel]

    init(id: String, name: String = "", channels: [ProtectChannel] = []) {
        self.id = id
        self.name = name
        self.channels = channels
    }

    init(from decoder: any Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        id = try container.decode(String.self, forKey: .id)
        name = try container.decodeIfPresent(String.self, forKey: .name) ?? ""
        channels = try container.decodeIfPresent([ProtectChannel].self, forKey: .channels) ?? []
    }

    /// Nursery view: medium quality is plenty and light on decode and Wi-Fi.
    /// A camera without a channel called Medium gets its first one.
    var preferredChannel: ProtectChannel? {
        channels.first { $0.name.caseInsensitiveCompare("Medium") == .orderedSame } ?? channels.first
    }

    private enum CodingKeys: String, CodingKey {
        case id, name, channels
    }
}

struct ProtectBootstrap: Decodable, Equatable, Sendable {
    let cameras: [ProtectCamera]

    init(cameras: [ProtectCamera]) {
        self.cameras = cameras
    }

    init(from decoder: any Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        cameras = try container.decodeIfPresent([ProtectCamera].self, forKey: .cameras) ?? []
    }

    private enum CodingKeys: String, CodingKey {
        case cameras
    }
}

/// A legacy login: the `TOKEN` cookie and the CSRF token that must ride
/// along with it. Its description is redacted, so logging a session never
/// leaks it.
struct ProtectSession: Equatable, Sendable, CustomStringConvertible {
    /// `TOKEN=<value>`, ready for a `Cookie` header.
    let cookie: String
    let csrfToken: String?

    var description: String { "ProtectSession(<redacted>)" }
}

/// Client for the UniFi Protect console's legacy private API
/// (`/proxy/protect/api`), authenticated by a login session. It is the
/// fallback when the public Integration API cannot be used, and the only
/// way to mint the API key that API needs, and to negotiate a livestream.
///
/// The API is unofficial and shifts between Protect releases, so every parse
/// ignores unknown keys, and failures are `ProtectAPIError`s with actionable
/// text. The session is injected: it carries the console's TOFU pin.
struct ProtectApiClient: Sendable {
    let baseURL: URL
    let urlSession: URLSession

    /// The controller's own defaults; shorter segments only add console load.
    private static let chunkSize = 4096
    private static let segmentLengthMs = 100

    func login(username: String, password: String) async throws -> ProtectSession {
        var request = URLRequest(url: ProtectHTTP.endpoint(baseURL, path: "/api/auth/login"))
        request.httpMethod = "POST"
        request.setValue(ProtectHTTP.jsonContentType, forHTTPHeaderField: "Content-Type")
        request.httpBody = try ProtectHTTP.jsonBody(LoginRequest(username: username, password: password))
        let (response, _) = try await ProtectHTTP.exchange(request, on: urlSession)
        guard ProtectHTTP.isSuccess(response) else {
            throw ProtectAPIError.status(
                response.statusCode,
                "Login failed (\(response.statusCode)); check the console address and credentials"
            )
        }
        guard let token = Self.tokenCookie(in: response) else {
            throw ProtectAPIError.invalidResponse("Login succeeded but no session cookie was returned")
        }
        return ProtectSession(
            cookie: token,
            csrfToken: response.value(forHTTPHeaderField: "X-CSRF-Token")
                ?? response.value(forHTTPHeaderField: "X-Updated-CSRF-Token")
        )
    }

    func bootstrap(_ session: ProtectSession) async throws -> ProtectBootstrap {
        let request = authorized(session, ProtectHTTP.endpoint(baseURL, path: "/proxy/protect/api/bootstrap"))
        let (response, data) = try await ProtectHTTP.exchange(request, on: urlSession)
        guard ProtectHTTP.isSuccess(response) else {
            throw ProtectAPIError.status(
                response.statusCode,
                "Camera discovery failed (\(response.statusCode)); is this a Protect console?"
            )
        }
        return try ProtectHTTP.decode(ProtectBootstrap.self, from: data, what: "camera list")
    }

    /// Enables RTSP on the channel and returns the updated camera, whose
    /// channel then carries the new alias.
    func enableRtsp(_ session: ProtectSession, cameraId: String, channelId: Int) async throws -> ProtectCamera {
        var request = authorized(
            session,
            ProtectHTTP.endpoint(baseURL, path: "/proxy/protect/api/cameras/\(ProtectHTTP.segment(cameraId))")
        )
        request.httpMethod = "PATCH"
        request.setValue(ProtectHTTP.jsonContentType, forHTTPHeaderField: "Content-Type")
        request.httpBody = try ProtectHTTP.jsonBody(
            EnableRtspRequest(channels: [.init(id: channelId, isRtspEnabled: true)])
        )
        let (response, data) = try await ProtectHTTP.exchange(request, on: urlSession)
        guard ProtectHTTP.isSuccess(response) else {
            throw ProtectAPIError.status(
                response.statusCode,
                "Enabling the RTSP stream failed (\(response.statusCode)); "
                    + "the account may lack camera management permission"
            )
        }
        return try ProtectHTTP.decode(ProtectCamera.self, from: data, what: "camera")
    }

    /// Mints a console API key for the public Integration API, the same call
    /// Home Assistant makes. It lives on the *private* API because the public
    /// one cannot bootstrap its own credential. Consoles older than Protect
    /// 5.3 have no such endpoint (`.notFound`) and accounts without owner
    /// rights are refused (`.forbidden`), so the caller can fall back.
    func createApiKey(_ session: ProtectSession, name: String) async throws -> String {
        var request = authorized(session, ProtectHTTP.endpoint(baseURL, path: "/proxy/users/api/v2/user/self/keys"))
        request.httpMethod = "POST"
        request.setValue(ProtectHTTP.jsonContentType, forHTTPHeaderField: "Content-Type")
        request.httpBody = try ProtectHTTP.jsonBody(ApiKeyRequest(name: name))
        let (response, data) = try await ProtectHTTP.exchange(request, on: urlSession)
        guard ProtectHTTP.isSuccess(response) else {
            throw ProtectAPIError.status(
                response.statusCode,
                "Creating an API key failed (\(response.statusCode)); the account may not own this console"
            )
        }
        guard let key = try ProtectHTTP.decode(ApiKeyEnvelope.self, from: data, what: "API key").data?.fullApiKey
        else {
            throw ProtectAPIError.invalidResponse("Console accepted the request but returned no API key")
        }
        return key
    }

    /// Plain RTSP on the console's 7447 port, on the address the user
    /// reached it on.
    func rtspURL(forAlias alias: String) -> String {
        ProtectHTTP.rtspURL(host: ProtectHTTP.host(of: baseURL), alias: alias)
    }

    /// Negotiates a livestream WebSocket for one camera channel and returns
    /// the `wss://` URL to open.
    ///
    /// This is the only transport that carries video off a camera encoding
    /// AV1: the controller wraps whatever the camera produces in fMP4. The
    /// token in the returned URL is single-use, so a reconnect must negotiate
    /// again rather than replay it.
    func livestreamURL(_ session: ProtectSession, cameraId: String, channel: Int) async throws -> URL {
        let url = ProtectHTTP.endpoint(
            baseURL,
            path: "/proxy/protect/api/ws/livestream",
            query: [
                // Empty-valued flags are how the controller reads these as set.
                URLQueryItem(name: "allowPartialGOP", value: ""),
                URLQueryItem(name: "camera", value: cameraId),
                URLQueryItem(name: "channel", value: String(channel)),
                URLQueryItem(name: "chunkSize", value: String(Self.chunkSize)),
                URLQueryItem(name: "fragmentDurationMillis", value: String(Self.segmentLengthMs)),
                URLQueryItem(name: "lens", value: "0"),
                URLQueryItem(name: "progressive", value: ""),
                URLQueryItem(name: "rebaseTimestampsToZero", value: "true"),
                URLQueryItem(name: "requestId", value: "\(cameraId)-\(channel)"),
                URLQueryItem(name: "type", value: "fmp4"),
                URLQueryItem(name: "useWallClock", value: "false"),
            ]
        )
        let (response, data) = try await ProtectHTTP.exchange(authorized(session, url), on: urlSession)
        guard ProtectHTTP.isSuccess(response) else {
            throw ProtectAPIError.status(
                response.statusCode,
                "Opening the livestream failed (\(response.statusCode)); "
                    + "this console may predate the livestream API"
            )
        }
        guard let minted = try ProtectHTTP.decode(LivestreamEnvelope.self, from: data, what: "livestream").url
        else {
            throw ProtectAPIError.invalidResponse("Console returned no livestream URL")
        }
        guard let rehosted = ProtectHTTP.rehostWebSocketURL(minted, host: ProtectHTTP.host(of: baseURL)) else {
            throw ProtectAPIError.invalidResponse("Console returned an unusable livestream URL")
        }
        return rehosted
    }

    /// "192.168.1.1", "console.local:8443" → an https base URL; nil if
    /// unparseable. Non-HTTPS schemes are rejected outright: credentials
    /// must never ride a connection that bypasses the TOFU TLS flow.
    static func baseURL(for hostInput: String) -> URL? {
        var trimmed = hostInput.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.hasSuffix("/") { trimmed.removeLast() }
        guard !trimmed.isEmpty else { return nil }
        let candidate =
            if trimmed.contains("://") {
                trimmed
            } else if trimmed.count(where: { $0 == ":" }) >= 2, !trimmed.hasPrefix("[") {
                // A bare IPv6 literal needs brackets before it can be a URL host.
                "https://[\(trimmed)]"
            } else {
                "https://\(trimmed)"
            }
        guard let components = URLComponents(string: candidate),
            components.scheme?.lowercased() == "https",
            let host = components.percentEncodedHost, !host.isEmpty,
            components.user == nil, components.password == nil,
            components.port.map({ (1...65535).contains($0) }) ?? true
        else { return nil }
        var base = URLComponents()
        base.scheme = "https"
        base.percentEncodedHost = host
        base.port = components.port
        base.path = "/"
        return base.url
    }

    private func authorized(_ session: ProtectSession, _ url: URL) -> URLRequest {
        var request = URLRequest(url: url)
        request.setValue(session.cookie, forHTTPHeaderField: "Cookie")
        if let csrf = session.csrfToken {
            request.setValue(csrf, forHTTPHeaderField: "X-CSRF-Token")
        }
        return request
    }

    /// `TOKEN=<value>` from the login's `Set-Cookie` headers. Parsed with
    /// `HTTPCookie` rather than by splitting the header: Foundation folds
    /// several `Set-Cookie` headers into one comma-joined value.
    private static func tokenCookie(in response: HTTPURLResponse) -> String? {
        guard let url = response.url else { return nil }
        var fields: [String: String] = [:]
        for (key, value) in response.allHeaderFields {
            if let key = key as? String, let value = value as? String { fields[key] = value }
        }
        return HTTPCookie.cookies(withResponseHeaderFields: fields, for: url)
            .first { $0.name == "TOKEN" }
            .map { "TOKEN=\($0.value)" }
    }

    private struct LoginRequest: Encodable {
        let username: String
        let password: String
        var rememberMe = true
    }

    private struct EnableRtspRequest: Encodable {
        struct Channel: Encodable {
            let id: Int
            let isRtspEnabled: Bool
        }
        let channels: [Channel]
    }

    private struct ApiKeyRequest: Encodable {
        let name: String
    }

    private struct LivestreamEnvelope: Decodable {
        let url: String?
    }

    private struct ApiKeyEnvelope: Decodable {
        struct KeyData: Decodable {
            let fullApiKey: String?

            private enum CodingKeys: String, CodingKey {
                case fullApiKey = "full_api_key"
            }
        }
        let data: KeyData?
    }
}

import Foundation

/// A camera as the public Integration API lists it.
struct PublicCamera: Decodable, Equatable, Sendable {
    let id: String
    /// Null on the wire for an unnamed camera (the API allows
    /// `oneOf [string, null]`).
    let name: String?
    let featureFlags: PublicCameraFeatureFlags?

    init(id: String, name: String? = nil, featureFlags: PublicCameraFeatureFlags? = nil) {
        self.id = id
        self.name = name
        self.featureFlags = featureFlags
    }

    /// Whether talk-back is worth offering at all. A camera whose flags never
    /// arrived has no speaker: offering talk-back and failing is worse than
    /// not offering it.
    var hasSpeaker: Bool { featureFlags?.hasSpeaker == true }
}

/// Only the flags Dozecam acts on; the console sends many more.
struct PublicCameraFeatureFlags: Decodable, Equatable, Sendable {
    let hasSpeaker: Bool

    init(hasSpeaker: Bool = false) {
        self.hasSpeaker = hasSpeaker
    }

    init(from decoder: any Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        hasSpeaker = try container.decodeIfPresent(Bool.self, forKey: .hasSpeaker) ?? false
    }

    private enum CodingKeys: String, CodingKey {
        case hasSpeaker
    }
}

/// Where a camera listens for talk-back audio, and in what format.
///
/// Despite the endpoint's name the console allocates nothing: repeated POSTs
/// hand back identical answers, with no token and nothing to release. So
/// this describes a camera rather than opening a session against it, and is
/// worth caching per camera instead of fetching per press.
///
/// The address is the *camera's own*, not the console's. Video reaches the
/// viewer through the console, so a camera on an isolated VLAN can stream
/// perfectly and still be unreachable here, which is why `host` is probed
/// before the control is offered rather than after it fails silently.
struct TalkbackSession: Decodable, Equatable, Sendable {
    let url: String
    let codec: String
    let samplingRate: Int
    let bitsPerSample: Int

    /// Consoles observed always name 7004, but the URL is what decides.
    static let defaultPort = 7004

    private var parsed: URL? {
        URL(string: url.trimmingCharacters(in: .whitespacesAndNewlines), encodingInvalidCharacters: false)
    }

    /// Nil when the console sends something unparseable; talk-back is then
    /// off.
    var host: String? {
        guard let host = parsed?.host(percentEncoded: false),
            !host.trimmingCharacters(in: .whitespaces).isEmpty
        else { return nil }
        return host
    }

    var port: Int {
        guard let port = parsed?.port, port > 0 else { return Self.defaultPort }
        return port
    }
}

/// Client for the UniFi Protect *public* Integration API (Protect 5.3+), the
/// one Ubiquiti documents. It is authenticated by a console API key rather
/// than a login session, so it cannot mint its own credential:
/// `ProtectApiClient.createApiKey` does that.
///
/// Implements what onboarding and talk-back need: list cameras, read or
/// create a camera's RTSPS streams, and describe its talk-back endpoint. The
/// livestream is negotiated over the legacy API; this one has none.
struct ProtectPublicApiClient: Sendable {
    let baseURL: URL
    let urlSession: URLSession

    /// Nursery view: medium quality is plenty and light on decode and Wi-Fi.
    static let qualityMedium = "medium"

    func cameras(apiKey: String) async throws -> [PublicCamera] {
        let data = try await execute(makeRequest(apiKey, "cameras"), action: "Camera discovery")
        return try ProtectHTTP.decode([PublicCamera].self, from: data, what: "camera list")
    }

    /// Streams already active on the camera, keyed by quality.
    func rtspsStreams(apiKey: String, cameraId: String) async throws -> [String: String] {
        let data = try await execute(
            makeRequest(apiKey, "cameras", cameraId, "rtsps-stream"),
            action: "Reading the camera's streams"
        )
        return try Self.parseStreams(data)
    }

    /// Enables the given qualities and returns the camera's streams by quality.
    func createRtspsStreams(apiKey: String, cameraId: String, qualities: [String]) async throws -> [String: String] {
        var request = makeRequest(apiKey, "cameras", cameraId, "rtsps-stream")
        request.httpMethod = "POST"
        request.setValue(ProtectHTTP.jsonContentType, forHTTPHeaderField: "Content-Type")
        request.httpBody = try ProtectHTTP.jsonBody(QualitiesRequest(qualities: qualities))
        let data = try await execute(request, action: "Enabling the RTSP stream")
        return try Self.parseStreams(data)
    }

    /// Where to send talk-back audio for a camera, and in what format.
    ///
    /// Takes no request body. Only cameras whose `PublicCamera.hasSpeaker` is
    /// set are worth asking; the rest answer with an error the caller would
    /// only have to translate back into "this camera cannot do that".
    func talkbackSession(apiKey: String, cameraId: String) async throws -> TalkbackSession {
        var request = makeRequest(apiKey, "cameras", cameraId, "talkback-session")
        request.httpMethod = "POST"
        request.httpBody = Data()
        let data = try await execute(request, action: "Starting talk-back")
        return try ProtectHTTP.decode(TalkbackSession.self, from: data, what: "talk-back session")
    }

    /// The Integration API hands back `rtsps://<console>:7441/<alias>?enableSrtp`.
    /// Only the alias travels: the host is whatever the console believes it
    /// is, which need not be the address the user reached it on (Home
    /// Assistant hit exactly this, core#176487), and the RTSPS port is not a
    /// stream the players here can open. So the alias is re-pointed at the
    /// console address that just answered, on the plain RTSP port.
    ///
    /// Nil when the URL will not parse or has no alias.
    func streamURL(forRtsps rtspsURL: String) -> String? {
        guard
            let url = URL(
                string: rtspsURL.trimmingCharacters(in: .whitespacesAndNewlines),
                encodingInvalidCharacters: false
            ),
            let alias = url.path(percentEncoded: true).split(separator: "/").last(where: { !$0.isEmpty })
        else { return nil }
        return ProtectHTTP.rtspURL(host: ProtectHTTP.host(of: baseURL), alias: String(alias))
    }

    /// A quality the camera does not currently serve can still be present as
    /// a key with a null value, so only the string ones are kept.
    private static func parseStreams(_ data: Data) throws -> [String: String] {
        guard let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw ProtectAPIError.invalidResponse("The console sent an unreadable stream list")
        }
        return object.compactMapValues { $0 as? String }
    }

    private func makeRequest(_ apiKey: String, _ segments: String...) -> URLRequest {
        let path = "/proxy/protect/integration/v1/" + segments.map(ProtectHTTP.segment).joined(separator: "/")
        var request = URLRequest(url: ProtectHTTP.endpoint(baseURL, path: path))
        request.setValue(apiKey, forHTTPHeaderField: "X-API-KEY")
        return request
    }

    private func execute(_ request: URLRequest, action: String) async throws -> Data {
        let (response, data) = try await ProtectHTTP.exchange(request, on: urlSession)
        guard ProtectHTTP.isSuccess(response) else {
            throw ProtectAPIError.status(
                response.statusCode,
                "\(action) failed (\(response.statusCode)) on the Protect integration API"
            )
        }
        return data
    }

    private struct QualitiesRequest: Encodable {
        let qualities: [String]
    }
}

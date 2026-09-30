import Foundation
import Synchronization

/// A Protect console that is not there: a `URLSession` whose requests are
/// answered from a queue of canned replies by a `URLProtocol`, so the
/// clients' tests touch no network. Each console gets its own ephemeral
/// session, tagged with a header the protocol routes by, so tests running
/// in parallel never see each other's replies.
///
/// The counterpart of the `MockWebServer` Android's tests use. It does not
/// do TLS: certificate pinning is the trust layer's, and tested there.
final class StubConsole: Sendable {
    struct Request: Sendable {
        let method: String
        let url: URL
        let headers: [String: String]
        let body: Data

        var path: String { url.path(percentEncoded: true) }
        var query: String? { url.query(percentEncoded: true) }
        var bodyText: String { String(decoding: body, as: UTF8.self) }

        func header(_ name: String) -> String? {
            headers.first { $0.key.caseInsensitiveCompare(name) == .orderedSame }?.value
        }
    }

    enum Reply: Sendable {
        case response(status: Int, headers: [String: String], body: Data)
        case failure(URLError.Code)
    }

    let urlSession: URLSession
    private let id = UUID().uuidString
    private let backend = Backend()

    fileprivate static let routingHeader = "X-Stub-Console"

    init() {
        urlSession = URLSession(configuration: Self.configuration(routingTo: id))
        StubURLProtocol.register(backend, as: id)
    }

    /// A configuration routed to this console, for sessions built elsewhere
    /// (the pinned session factory).
    var configuration: URLSessionConfiguration { Self.configuration(routingTo: id) }

    private static func configuration(routingTo id: String) -> URLSessionConfiguration {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [StubURLProtocol.self]
        configuration.httpAdditionalHeaders = [routingHeader: id]
        return configuration
    }

    deinit {
        StubURLProtocol.unregister(id)
        urlSession.invalidateAndCancel()
    }

    func enqueue(_ reply: Reply) {
        backend.state.withLock { $0.replies.append(reply) }
    }

    func enqueue(status: Int = 200, headers: [String: String] = [:], body: Data = Data("{}".utf8)) {
        enqueue(.response(status: status, headers: headers, body: body))
    }

    func enqueue(status: Int = 200, json: String) {
        enqueue(status: status, body: Data(json.utf8))
    }

    /// A 200 whose body is a file under `shared/fixtures`.
    func enqueueFixture(_ path: String) throws {
        enqueue(status: 200, body: try Fixtures.data(path))
    }

    /// A legacy login that succeeds: the session cookie and the CSRF token.
    func enqueueLogin(token: String = "abc123", csrf: String = "csrf-token-1") {
        enqueue(
            status: 200,
            headers: ["Set-Cookie": "TOKEN=\(token); Path=/; HttpOnly", "X-CSRF-Token": csrf]
        )
    }

    /// Every request answered so far, oldest first.
    var requests: [Request] {
        backend.state.withLock { $0.requests }
    }
}

/// What the protocol reaches a console by: held apart from `StubConsole` so
/// the registry does not keep a finished test's console alive.
private final class Backend: Sendable {
    struct State {
        var replies: [StubConsole.Reply] = []
        var requests: [StubConsole.Request] = []
    }

    let state = Mutex(State())

    func answer(_ request: StubConsole.Request) -> StubConsole.Reply {
        state.withLock { state in
            state.requests.append(request)
            guard !state.replies.isEmpty else {
                return .response(status: 599, headers: [:], body: Data("no reply queued".utf8))
            }
            return state.replies.removeFirst()
        }
    }
}

private let backends = Mutex<[String: Backend]>([:])

final class StubURLProtocol: URLProtocol, @unchecked Sendable {
    fileprivate static func register(_ backend: Backend, as id: String) {
        backends.withLock { $0[id] = backend }
    }

    fileprivate static func unregister(_ id: String) {
        backends.withLock { _ = $0.removeValue(forKey: id) }
    }

    override class func canInit(with request: URLRequest) -> Bool {
        request.value(forHTTPHeaderField: StubConsole.routingHeader) != nil
    }

    override class func canonicalRequest(for request: URLRequest) -> URLRequest {
        request
    }

    override func startLoading() {
        guard let url = request.url,
            let id = request.value(forHTTPHeaderField: StubConsole.routingHeader),
            let backend = backends.withLock({ $0[id] })
        else {
            client?.urlProtocol(self, didFailWithError: URLError(.cannotConnectToHost))
            return
        }
        var headers = request.allHTTPHeaderFields ?? [:]
        headers[StubConsole.routingHeader] = nil
        let recorded = StubConsole.Request(
            method: request.httpMethod ?? "GET",
            url: url,
            headers: headers,
            body: request.httpBody ?? request.httpBodyStream.map(Self.drain) ?? Data()
        )
        switch backend.answer(recorded) {
        case .response(let status, let replyHeaders, let body):
            let response = HTTPURLResponse(
                url: url, statusCode: status, httpVersion: "HTTP/1.1", headerFields: replyHeaders)!
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: body)
            client?.urlProtocolDidFinishLoading(self)
        case .failure(let code):
            // Like URLSession's own errors, carrying the failing URL.
            client?.urlProtocol(self, didFailWithError: URLError(code, userInfo: [NSURLErrorFailingURLErrorKey: url]))
        }
    }

    override func stopLoading() {}

    /// URLSession hands a protocol the body as a stream, not as `httpBody`.
    private static func drain(_ stream: InputStream) -> Data {
        var data = Data()
        stream.open()
        defer { stream.close() }
        var buffer = [UInt8](repeating: 0, count: 4096)
        while stream.hasBytesAvailable {
            let read = stream.read(&buffer, maxLength: buffer.count)
            guard read > 0 else { break }
            data.append(buffer, count: read)
        }
        return data
    }
}

import CryptoKit
import Foundation
import Network
import Security

/// VLCKit's live555 is built without TLS, so rtsps:// cannot be opened
/// directly. This terminates TLS in-process: the player opens
/// rtsp://127.0.0.1:<port>/<path>, and every connection to that port is piped
/// byte for byte over TLS to the real host. Certificate trust is ours, not
/// VLC's: the spike logs the leaf's SHA-256 and accepts it (TOFU in the app).
final class TLSProxy: @unchecked Sendable {
    private static let lock = NSLock()
    nonisolated(unsafe) private static var proxies: [String: TLSProxy] = [:]

    /// Maps an rtsps:// URL to the local plain-RTSP URL that reaches it.
    static func localURL(for url: URL) -> URL {
        guard url.scheme == "rtsps", let host = url.host else { return url }
        let port = url.port ?? 322
        let key = "\(host):\(port)"
        lock.lock()
        let proxy = proxies[key] ?? TLSProxy(host: host, port: UInt16(port))
        proxies[key] = proxy
        lock.unlock()
        var parts = URLComponents(url: url, resolvingAgainstBaseURL: false)!
        parts.scheme = "rtsp"
        parts.host = "127.0.0.1"
        parts.port = Int(proxy.localPort)
        return parts.url!
    }

    let host: String
    let port: UInt16
    private(set) var localPort: UInt16 = 0
    private let queue = DispatchQueue(label: "tls-proxy")
    private let listener: NWListener

    private init(host: String, port: UInt16) {
        self.host = host
        self.port = port
        let params = NWParameters.tcp
        params.requiredLocalEndpoint = NWEndpoint.hostPort(host: "127.0.0.1", port: .any)
        listener = try! NWListener(using: params)
        let ready = DispatchSemaphore(value: 0)
        listener.stateUpdateHandler = { [weak self] state in
            if case .ready = state {
                self?.localPort = self?.listener.port?.rawValue ?? 0
                ready.signal()
            } else if case .failed(let error) = state {
                SpikeLog.write("TLS", "listener failed: \(error)")
                ready.signal()
            }
        }
        listener.newConnectionHandler = { [weak self] inbound in self?.accept(inbound) }
        listener.start(queue: queue)
        _ = ready.wait(timeout: .now() + 2)
        SpikeLog.write("TLS", "proxy 127.0.0.1:\(localPort) → \(host):\(port)")
    }

    private func accept(_ inbound: NWConnection) {
        let tls = NWProtocolTLS.Options()
        let host = self.host
        sec_protocol_options_set_verify_block(
            tls.securityProtocolOptions,
            { _, trust, complete in
                let secTrust = sec_trust_copy_ref(trust).takeRetainedValue()
                let chain = (SecTrustCopyCertificateChain(secTrust) as? [SecCertificate]) ?? []
                let fingerprint = chain.first.map { SHA256.hash(data: SecCertificateCopyData($0) as Data) }
                    .map { $0.map { String(format: "%02X", $0) }.joined(separator: ":") } ?? "none"
                SpikeLog.write("TLS", "\(host) leaf SHA-256 \(fingerprint) (chain \(chain.count)) → accepted (TOFU)")
                complete(true)
            }, queue)
        let outbound = NWConnection(
            host: NWEndpoint.Host(host), port: NWEndpoint.Port(rawValue: port)!, using: NWParameters(tls: tls))
        outbound.stateUpdateHandler = { state in
            switch state {
            case .ready: SpikeLog.write("TLS", "connected to \(host)")
            case .failed(let error), .waiting(let error):
                SpikeLog.write("TLS", "upstream \(host): \(error)")
                inbound.cancel()
            default: break
            }
        }
        inbound.stateUpdateHandler = { state in
            if case .cancelled = state { outbound.cancel() }
            if case .failed = state { outbound.cancel() }
        }
        inbound.start(queue: queue)
        outbound.start(queue: queue)
        let local = "rtsp://127.0.0.1:\(localPort)"
        let remote = "rtsps://\(host):\(port)"
        Self.pipe(from: inbound, to: outbound, rewriter: RTSPRewriter(from: local, to: remote))
        Self.pipe(from: outbound, to: inbound, rewriter: RTSPRewriter(from: remote, to: local))
    }

    private static func pipe(from source: NWConnection, to sink: NWConnection, rewriter: RTSPRewriter) {
        source.receive(minimumIncompleteLength: 1, maximumLength: 64 * 1024) { data, _, isComplete, error in
            if let data, !data.isEmpty {
                let out = rewriter.process(data)
                if !out.isEmpty { sink.send(content: out, completion: .contentProcessed { _ in }) }
            }
            if isComplete || error != nil {
                sink.send(content: nil, isComplete: true, completion: .contentProcessed { _ in })
                return
            }
            pipe(from: source, to: sink, rewriter: rewriter)
        }
    }
}

/// Rewrites base URLs in RTSP text messages (request lines, Content-Base,
/// SDP a=control) between the local plain-RTSP address and the real rtsps
/// one, fixing Content-Length. Once interleaved RTP ($ frames) starts, the
/// stream passes through untouched. Used from one proxy queue only.
final class RTSPRewriter: @unchecked Sendable {
    private let from: String
    private let to: String
    private var buffer = Data()
    private var passthrough = false

    init(from: String, to: String) {
        self.from = from
        self.to = to
    }

    func process(_ data: Data) -> Data {
        if passthrough { return data }
        buffer.append(data)
        var out = Data()
        while !buffer.isEmpty {
            if buffer.first == UInt8(ascii: "$") {
                passthrough = true
                out.append(buffer)
                buffer.removeAll()
                break
            }
            guard let end = buffer.range(of: Data("\r\n\r\n".utf8)) else { break }
            let head = String(decoding: buffer[buffer.startIndex..<end.lowerBound], as: UTF8.self)
            var length = 0
            for line in head.split(separator: "\r\n") where line.lowercased().hasPrefix("content-length:") {
                length = Int(line.dropFirst("content-length:".count).trimmingCharacters(in: .whitespaces)) ?? 0
            }
            let bodyStart = end.upperBound
            guard buffer.count - (bodyStart - buffer.startIndex) >= length else { break }
            let body = String(decoding: buffer[bodyStart..<(bodyStart + length)], as: UTF8.self)
            buffer = Data(buffer[(bodyStart + length)...])
            let newBody = body.replacingOccurrences(of: from, with: to)
            var lines = head.replacingOccurrences(of: from, with: to).components(separatedBy: "\r\n")
            if length > 0 {
                lines = lines.map {
                    $0.lowercased().hasPrefix("content-length:") ? "Content-Length: \(newBody.utf8.count)" : $0
                }
            }
            let message = lines.joined(separator: "\r\n") + "\r\n\r\n" + newBody
            SpikeLog.write("RTSP", "→ \(to.hasPrefix("rtsps") ? "server" : "player"): " + message.replacingOccurrences(of: "\r\n", with: " ⏎ ").prefix(900))
            out.append(Data(message.utf8))
        }
        return out
    }
}

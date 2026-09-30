import Foundation

/// One TLS endpoint, `host:port`: what a certificate pin belongs to.
///
/// An endpoint, not a host, because a UniFi console is not one TLS server:
/// the UniFi OS UI on 443 and the Protect media ports (7441, 7443) present
/// different self-signed certificates. Pinning per host would judge a media
/// port against the UI's certificate and refuse every stream
/// (shared/spec/protect.md, "Certificate pinning (TOFU)").
struct TofuEndpoint: Codable, Hashable, Sendable, CustomStringConvertible {
    /// Lowercased, without IPv6 brackets.
    let host: String
    let port: Int

    init(host: String, port: Int) {
        var host = host.lowercased()
        if host.hasPrefix("["), host.hasSuffix("]") {
            host = String(host.dropFirst().dropLast())
        }
        self.host = host
        self.port = port
    }

    /// The endpoint a URL connects to, or nil when it has no host. A missing
    /// port is the scheme's default: 443 for `https` and `wss`, 322 for
    /// `rtsps` (RFC 7826), 80 for `http` and `ws`, 554 for `rtsp`.
    init?(url: URL) {
        guard let host = url.host(percentEncoded: false), !host.isEmpty else { return nil }
        let port: Int
        if let explicit = url.port {
            port = explicit
        } else {
            switch url.scheme?.lowercased() {
            case "https", "wss": port = 443
            case "rtsps": port = 322
            case "http", "ws": port = 80
            case "rtsp": port = 554
            default: return nil
            }
        }
        self.init(host: host, port: port)
    }

    /// `host:port`, with an IPv6 host in brackets. The same key Android's
    /// `endpointKey` builds for an IPv4 address or a hostname.
    var key: String { host.contains(":") ? "[\(host)]:\(port)" : "\(host):\(port)" }

    var description: String { key }
}

import Darwin

/// What a camera stream URL typed or pasted by hand may be, and how it is
/// stored (shared/spec/protect.md, "Stream URLs"; fixtures in
/// `shared/fixtures/stream-url`). The counterpart of Android's
/// `StreamUrlValidator`.
///
/// Android leans on `java.net.URI`, whose parse rejects `rtsp://`,
/// `rtsp:token` and a host with spaces. Foundation's `URL` and
/// `URLComponents` are laxer (they percent-encode spaces, and accept an
/// empty authority), and those rejections are the rule rather than an
/// accident of Java's parser. So this does not ask Foundation: it parses the
/// few parts it needs itself, holding them to what `java.net.URI` accepts as
/// a server authority:
///
/// - No whitespace, control characters or ASCII characters a URI may not
///   carry, and every `%` starts a two-digit escape.
/// - The scheme is followed by `//` and an authority: an opaque URL has no host.
/// - The host is an IPv4 address, a bracketed IPv6 address, or a hostname of
///   letter/digit/hyphen labels whose last label starts with a letter (so
///   `192.168.1.256` or `cam_1` is not a host, as in Java).
/// - A port, if any, is digits.
enum StreamUrlValidator {
    /// Whether `raw` is an `rtsp` or `rtsps` URL, in any case, with a host.
    static func isValid(_ raw: String) -> Bool {
        guard let scheme = ParsedUrl(raw)?.scheme.lowercased() else { return false }
        return scheme == "rtsp" || scheme == "rtsps"
    }

    /// Only a legacy safety net: `normalize` rewrites every `rtsps://` entry
    /// to a plain `rtsp://` one before it is saved, so this should only ever
    /// see a stale pre-normalization camera. RTSP over TLS is not what
    /// Protect's `rtsps://` link serves (shared/spec/protect.md), so such an
    /// entry cannot be listened to over RTSP.
    static func isMonitorable(_ raw: String) -> Bool {
        ParsedUrl(raw)?.scheme.lowercased() == "rtsp"
    }

    /// Protect's console only ever shows a camera's `rtsps://` link (port
    /// 7441), but that link is not a stream common players can open
    /// (community.ui.com and AlexxIT/go2rtc#2071). The same alias plays on
    /// the plain `rtsp://` port (7447), so rewrite to that before a URL is
    /// ever persisted: same user info, host and path, 7441 becoming 7447 and
    /// any other port kept, query and fragment dropped. Anything else, an
    /// unparseable URL included, is only trimmed.
    static func normalize(_ raw: String) -> String {
        let candidate = trimmed(raw)
        guard let url = ParsedUrl(raw), url.scheme.lowercased() == "rtsps" else { return candidate }
        let port = url.port == protectSecurePort ? protectPlainPort : url.port
        var result = "rtsp://"
        if let userInfo = url.userInfo { result += userInfo + "@" }
        result += url.host
        if let port { result += ":\(port)" }
        return result + url.path
    }

    private static let protectSecurePort = 7441
    private static let protectPlainPort = 7447

    /// Kotlin's `trim()`: whitespace at either end.
    fileprivate static func trimmed(_ raw: String) -> String {
        let scalars = raw.unicodeScalars
        guard let first = scalars.firstIndex(where: { !$0.properties.isWhitespace }),
            let last = scalars.lastIndex(where: { !$0.properties.isWhitespace })
        else { return "" }
        return String(scalars[first...last])
    }
}

/// The parts of a trimmed stream URL with a server authority, or nil when it
/// has none (see `StreamUrlValidator`). Components keep their escapes, so
/// `normalize` writes them back as they were typed.
private struct ParsedUrl {
    let scheme: String
    let userInfo: String?
    let host: String
    let port: Int?
    let path: String

    init?(_ raw: String) {
        let text = StreamUrlValidator.trimmed(raw)
        guard !text.isEmpty, Self.isUriText(text) else { return nil }

        guard let colon = text.firstIndex(of: ":") else { return nil }
        let scheme = text[..<colon]
        guard let head = scheme.first, head.isASCII, head.isLetter,
            scheme.allSatisfy({ $0.isASCII && ($0.isLetter || $0.isNumber || "+-.".contains($0)) })
        else { return nil }

        let rest = text[text.index(after: colon)...]
        guard rest.hasPrefix("//") else { return nil }  // opaque, like rtsp:token
        let afterSlashes = rest.dropFirst(2)
        let authorityEnd = afterSlashes.firstIndex(where: { "/?#".contains($0) }) ?? afterSlashes.endIndex
        var authority = afterSlashes[..<authorityEnd]
        let pathEnd = afterSlashes[authorityEnd...].firstIndex(where: { "?#".contains($0) }) ?? afterSlashes.endIndex

        var userInfo: Substring?
        if let at = authority.firstIndex(of: "@") {
            userInfo = authority[..<at]
            authority = authority[authority.index(after: at)...]
        }
        if let userInfo, userInfo.contains(where: { "[]".contains($0) }) { return nil }

        let host: Substring
        let portText: Substring?
        if authority.hasPrefix("[") {
            guard let close = authority.firstIndex(of: "]") else { return nil }
            host = authority[...close]
            let after = authority[authority.index(after: close)...]
            guard after.isEmpty || after.hasPrefix(":") else { return nil }
            portText = after.isEmpty ? nil : after.dropFirst()
            guard Self.isIPv6(host.dropFirst().dropLast()) else { return nil }
        } else {
            if let portColon = authority.firstIndex(of: ":") {
                host = authority[..<portColon]
                portText = authority[authority.index(after: portColon)...]
            } else {
                host = authority
                portText = nil
            }
            guard Self.isIPv4(host) || Self.isHostname(host) else { return nil }
        }

        // `host:` with no digits is allowed and means no port, as in Java.
        var port: Int?
        if let portText, !portText.isEmpty {
            guard portText.allSatisfy({ $0.isASCII && $0.isNumber }), let value = Int32(portText) else { return nil }
            port = Int(value)
        }

        self.scheme = String(scheme)
        self.userInfo = userInfo.map(String.init)
        self.host = String(host)
        self.port = port
        self.path = String(afterSlashes[authorityEnd..<pathEnd])
    }

    /// Characters a URI may carry, as `java.net.URI` reads them: ASCII
    /// unreserved and reserved characters, well-formed `%XX` escapes, and
    /// non-ASCII characters other than spaces and controls.
    private static func isUriText(_ text: String) -> Bool {
        let scalars = Array(text.unicodeScalars)
        var i = 0
        while i < scalars.count {
            let scalar = scalars[i]
            if scalar == "%" {
                guard i + 2 < scalars.count, scalars[i + 1].properties.isASCIIHexDigit,
                    scalars[i + 2].properties.isASCIIHexDigit
                else { return false }
                i += 3
                continue
            }
            if scalar.isASCII {
                let allowed =
                    ("a"..."z").contains(scalar) || ("A"..."Z").contains(scalar) || ("0"..."9").contains(scalar)
                    || "-_.!~*'();/?:@&=+$,[]#".unicodeScalars.contains(scalar)
                guard allowed else { return false }
            } else {
                let category = scalar.properties.generalCategory
                guard !scalar.properties.isWhitespace, category != .control, category != .spaceSeparator
                else { return false }
            }
            i += 1
        }
        return true
    }

    /// Four dot-separated decimal bytes, each one to three digits.
    private static func isIPv4(_ host: Substring) -> Bool {
        let parts = host.split(separator: ".", omittingEmptySubsequences: false)
        return parts.count == 4
            && parts.allSatisfy { part in
                (1...3).contains(part.count) && part.allSatisfy { $0.isASCII && $0.isNumber }
                    && Int(part)! <= 255
            }
    }

    /// Labels of ASCII letters, digits and inner hyphens, dot-separated, with
    /// an optional trailing dot. When there is more than one label, the last
    /// starts with a letter: an all-numeric name is an IPv4 address or
    /// nothing.
    private static func isHostname(_ host: Substring) -> Bool {
        var labels = host.split(separator: ".", omittingEmptySubsequences: false)
        if labels.count > 1, labels.last?.isEmpty == true { labels.removeLast() }
        guard !labels.isEmpty else { return false }
        for label in labels {
            guard let first = label.first, let last = label.last,
                first.isASCII && (first.isLetter || first.isNumber), last != "-",
                label.allSatisfy({ $0.isASCII && ($0.isLetter || $0.isNumber || $0 == "-") })
            else { return false }
        }
        return labels.count == 1 || labels.last!.first!.isLetter
    }

    private static func isIPv6(_ address: Substring) -> Bool {
        var storage = in6_addr()
        return inet_pton(AF_INET6, String(address), &storage) == 1
    }
}

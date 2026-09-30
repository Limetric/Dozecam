import Foundation
import Security

/// Two self-signed certificates in `DozecamTests/Resources/`, copied into the
/// test bundle, standing in for a console that reissues its certificate.
///
/// Made once with OpenSSL (the keys were thrown away; nothing signs with
/// them):
///
///     openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -nodes \
///         -keyout /dev/null -days 36500 -subj "/CN=dozecam-test-console-a" \
///         -outform DER -out console-a.der
///
/// and likewise for `console-b`. The expected fingerprints are what
/// `openssl x509 -inform DER -in console-a.der -noout -fingerprint -sha256`
/// prints, which is also the format Android's `sha256Fingerprint()` produces.
enum TestCertificates {
    struct Missing: Error {
        let name: String
    }

    static let consoleAFingerprint =
        "FF:42:07:9C:15:EF:15:79:5E:12:BF:EC:89:17:A5:6C:75:51:56:06:AE:94:F2:CD:29:56:85:FE:1E:06:1D:D8"
    static let consoleBFingerprint =
        "95:84:E4:14:47:DE:E9:48:C5:8E:2A:79:1B:FA:72:20:3D:FE:6E:A1:1F:6D:F3:23:F4:6C:C5:1D:2E:6A:20:B7"

    static func der(_ name: String) throws -> Data {
        guard let url = Bundle(for: BundleToken.self).url(forResource: name, withExtension: "der") else {
            throw Missing(name: name)
        }
        return try Data(contentsOf: url)
    }

    static func certificate(_ name: String) throws -> SecCertificate {
        guard let certificate = SecCertificateCreateWithData(nil, try der(name) as CFData) else {
            throw Missing(name: name)
        }
        return certificate
    }

    /// A server trust presenting `name` as its leaf, as a TLS handshake would
    /// hand it over.
    static func trust(_ name: String) throws -> SecTrust {
        var trust: SecTrust?
        let status = SecTrustCreateWithCertificates(
            try certificate(name), SecPolicyCreateSSL(true, "192.168.1.1" as CFString), &trust)
        guard status == errSecSuccess, let trust else { throw Missing(name: name) }
        return trust
    }

    private final class BundleToken {}
}

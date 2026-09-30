import CryptoKit
import Foundation
import Security

/// SHA-256 certificate fingerprints in the format Android shows and stores:
/// uppercase hex pairs joined with `:` (`AB:CD:…`, 95 characters), the same
/// as `openssl x509 -fingerprint -sha256` prints.
enum CertificateFingerprint {
    /// The fingerprint of a DER-encoded certificate.
    static func sha256(der: Data) -> String {
        SHA256.hash(data: der).map { byte in
            let hex = String(byte, radix: 16, uppercase: true)
            return byte < 0x10 ? "0" + hex : hex
        }.joined(separator: ":")
    }

    static func sha256(_ certificate: SecCertificate) -> String {
        sha256(der: SecCertificateCopyData(certificate) as Data)
    }

    /// The fingerprint of the leaf certificate a server presented, or nil
    /// when the chain is empty. The leaf is what is pinned; the rest of the
    /// chain, if any, plays no part.
    static func leaf(of trust: SecTrust) -> String? {
        guard let chain = SecTrustCopyCertificateChain(trust) as? [SecCertificate],
            let leaf = chain.first
        else { return nil }
        return sha256(leaf)
    }
}

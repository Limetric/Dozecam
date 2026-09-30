import Foundation

/// Why a pinned connection was refused.
///
/// URLSession reports a refused certificate as a cancelled `URLError`; get
/// this back from it with `PinnedSession.trustFailure(in:)` or
/// `PinnedSession.surfacingTrustFailures(_:)`.
enum TofuTrustError: Error, Equatable, Sendable, CustomStringConvertible {
    /// First contact: nothing is pinned for this console endpoint. Show
    /// `presented` for the user to confirm, then sign in again through a
    /// session built with `confirming: presented`.
    case unpinned(endpoint: TofuEndpoint, presented: String)

    /// The console presents a certificate other than the pinned one. Asked
    /// about again rather than refused outright: consoles reissue
    /// certificates for ordinary reasons (firmware update, factory reset), and
    /// an impostor on the network looks the same from here, so the user needs
    /// both fingerprints to tell them apart.
    case changed(endpoint: TofuEndpoint, pinned: String, presented: String)

    /// A media endpoint presented a certificate other than the one learned
    /// for it. The learned pin is already forgotten: negotiate a new URL with
    /// the console and connect again, which learns the new certificate.
    case mediaChanged(endpoint: TofuEndpoint, pinned: String, presented: String)

    /// A media session was used before the console that vouches for its
    /// endpoints was pinned. A programming error, not something to ask about.
    case consoleNotPinned(endpoint: TofuEndpoint, console: TofuEndpoint)

    /// The server presented no certificate.
    case noCertificate(endpoint: TofuEndpoint)

    var endpoint: TofuEndpoint {
        switch self {
        case .unpinned(let endpoint, _), .changed(let endpoint, _, _), .mediaChanged(let endpoint, _, _),
            .consoleNotPinned(let endpoint, _), .noCertificate(let endpoint):
            endpoint
        }
    }

    /// Whether the user has to be asked: first contact with a console, or a
    /// console whose certificate changed.
    var needsConfirmation: Bool {
        switch self {
        case .unpinned, .changed: true
        case .mediaChanged, .consoleNotPinned, .noCertificate: false
        }
    }

    /// The fingerprint the server presented, when it presented a certificate.
    var presentedFingerprint: String? {
        switch self {
        case .unpinned(_, let presented), .changed(_, _, let presented), .mediaChanged(_, _, let presented):
            presented
        case .consoleNotPinned, .noCertificate: nil
        }
    }

    /// The fingerprint pinned for the endpoint, when one was.
    var pinnedFingerprint: String? {
        switch self {
        case .changed(_, let pinned, _), .mediaChanged(_, let pinned, _): pinned
        case .unpinned, .consoleNotPinned, .noCertificate: nil
        }
    }

    var description: String {
        switch self {
        case .unpinned(let endpoint, let presented):
            "Unpinned certificate at \(endpoint): \(presented)"
        case .changed(let endpoint, let pinned, let presented):
            "Certificate changed at \(endpoint): pinned \(pinned), presented \(presented)"
        case .mediaChanged(let endpoint, let pinned, let presented):
            "Media certificate changed at \(endpoint): pinned \(pinned), presented \(presented)"
        case .consoleNotPinned(let endpoint, let console):
            "Media endpoint \(endpoint) reached before its console \(console) was pinned"
        case .noCertificate(let endpoint):
            "No certificate presented at \(endpoint)"
        }
    }
}

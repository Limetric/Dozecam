import Foundation

/// A stored pin: the SHA-256 fingerprint of the leaf certificate an endpoint
/// presented.
struct TofuPin: Codable, Equatable, Sendable {
    let fingerprint: String
    /// For a media endpoint learned without a prompt, the console that
    /// vouched for it. Nil for a pin the user confirmed.
    let learnedVia: TofuEndpoint?

    init(fingerprint: String, learnedVia: TofuEndpoint? = nil) {
        self.fingerprint = fingerprint
        self.learnedVia = learnedVia
    }
}

/// How a connection's endpoint came to be reached, which decides what an
/// unknown certificate means.
enum TofuRole: Equatable, Sendable {
    /// The console the user signs in to. `confirming` is a fingerprint the
    /// user has just accepted at the prompt: it is trusted for this session
    /// but not pinned, because the pin is stored only after a sign-in
    /// succeeds behind it (`TofuTrustStore.confirmConsole`).
    case console(confirming: String? = nil)

    /// A media endpoint whose URL the pinned console `vouchedBy` minted over
    /// its verified connection. Learned on first use, without a prompt.
    case media(vouchedBy: TofuEndpoint)
}

/// What to do with the certificate an endpoint presented.
enum TofuDecision: Equatable, Sendable {
    case accept
    /// Accept, and store this pin: a media endpoint's first sighting.
    case learn(TofuPin)
    case refuse(TofuTrustError)
    /// Refuse, and forget the endpoint's pin: a learned media pin that no
    /// longer matches, relearned on the next negotiation.
    case forgetAndRefuse(TofuTrustError)
}

/// Trust on first use for self-signed Protect consoles: identity is the
/// certificate the user confirmed, not a CA chain or a hostname
/// (shared/spec/protect.md, "Certificate pinning (TOFU)"). Pure: the store
/// applies the decision.
///
/// Android reference: `TofuTrustManager`, `ProtectLivestreamProvider`
/// (`mediaFingerprintFor`, `onMediaCertificateChanged`).
enum TofuPolicy {
    /// - Parameters:
    ///   - presented: the leaf certificate's fingerprint, nil when the server
    ///     presented none.
    ///   - pin: looks up the pin stored for an endpoint.
    static func decide(
        endpoint: TofuEndpoint,
        presented: String?,
        role: TofuRole,
        pin: (TofuEndpoint) -> TofuPin?
    ) -> TofuDecision {
        guard let presented else { return .refuse(.noCertificate(endpoint: endpoint)) }
        let pinned = pin(endpoint)

        switch role {
        case .console(let confirming):
            // The fingerprint the user just confirmed stands in for the pin,
            // exactly as Android builds the sign-in client with it.
            guard let expected = confirming ?? pinned?.fingerprint else {
                return .refuse(.unpinned(endpoint: endpoint, presented: presented))
            }
            if presented == expected { return .accept }
            return .refuse(.changed(endpoint: endpoint, pinned: expected, presented: presented))

        case .media(let console):
            // A media URL pointing back at the console itself is judged as the
            // console: nothing reached through it may learn or forget the pin
            // the user confirmed.
            if endpoint == console {
                return decide(endpoint: endpoint, presented: presented, role: .console(), pin: pin)
            }
            // The console vouches only once it is pinned itself.
            guard pin(console) != nil else {
                return .refuse(.consoleNotPinned(endpoint: endpoint, console: console))
            }
            guard let pinned else { return .learn(TofuPin(fingerprint: presented, learnedVia: console)) }
            if presented == pinned.fingerprint { return .accept }
            // Only a pin learned silently is forgotten silently. A pin the
            // user confirmed (this endpoint was once signed in to as a
            // console) is asked about like any console's.
            guard pinned.learnedVia != nil else {
                return .refuse(.changed(endpoint: endpoint, pinned: pinned.fingerprint, presented: presented))
            }
            return .forgetAndRefuse(
                .mediaChanged(endpoint: endpoint, pinned: pinned.fingerprint, presented: presented))
        }
    }
}

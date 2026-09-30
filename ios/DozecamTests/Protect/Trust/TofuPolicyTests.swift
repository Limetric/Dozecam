import Foundation
import Testing

@testable import Dozecam

/// Every rule of shared/spec/protect.md, "Certificate pinning (TOFU)", as the
/// pure decision.
struct TofuPolicyTests {
    let console = TofuEndpoint(host: "192.168.1.1", port: 443)
    let media = TofuEndpoint(host: "192.168.1.1", port: 7443)
    let a = "AA:AA"
    let b = "BB:BB"

    func decide(
        _ endpoint: TofuEndpoint, presenting presented: String?, as role: TofuRole,
        pins: [TofuEndpoint: TofuPin] = [:]
    ) -> TofuDecision {
        TofuPolicy.decide(endpoint: endpoint, presented: presented, role: role, pin: { pins[$0] })
    }

    // MARK: Console

    @Test func firstContactIsRefusedAndShowsThePresentedFingerprint() {
        let decision = decide(console, presenting: a, as: .console())
        #expect(decision == .refuse(.unpinned(endpoint: console, presented: a)))
        guard case .refuse(let error) = decision else { return }
        #expect(error.needsConfirmation)
        #expect(error.presentedFingerprint == a)
        #expect(error.pinnedFingerprint == nil)
    }

    @Test func aPinnedMatchingCertificateIsAccepted() {
        #expect(decide(console, presenting: a, as: .console(), pins: [console: TofuPin(fingerprint: a)]) == .accept)
    }

    @Test func aConfirmedFingerprintIsAcceptedWithoutPinningIt() {
        // Accept, not learn: the pin waits for a sign-in to succeed.
        #expect(decide(console, presenting: a, as: .console(confirming: a)) == .accept)
    }

    @Test func aConfirmedFingerprintOtherThanThePresentedOneIsAskedAboutAgain() {
        #expect(
            decide(console, presenting: b, as: .console(confirming: a))
                == .refuse(.changed(endpoint: console, pinned: a, presented: b)))
    }

    @Test func aChangedConsoleCertificateAsksAgainWithBothFingerprints() {
        let decision = decide(console, presenting: b, as: .console(), pins: [console: TofuPin(fingerprint: a)])
        #expect(decision == .refuse(.changed(endpoint: console, pinned: a, presented: b)))
        guard case .refuse(let error) = decision else { return }
        #expect(error.needsConfirmation)
        #expect(error.pinnedFingerprint == a)
        #expect(error.presentedFingerprint == b)
    }

    @Test func confirmingTheNewCertificateOfAChangedConsoleAcceptsIt() {
        #expect(
            decide(console, presenting: b, as: .console(confirming: b), pins: [console: TofuPin(fingerprint: a)])
                == .accept)
    }

    @Test func pinsArePerEndpointNotPerHost() {
        // The console's UI pin says nothing about another port on the host.
        let pins = [console: TofuPin(fingerprint: a)]
        #expect(
            decide(media, presenting: b, as: .console(), pins: pins)
                == .refuse(.unpinned(endpoint: media, presented: b)))
        #expect(
            decide(TofuEndpoint(host: "192.168.1.2", port: 443), presenting: a, as: .console(), pins: pins)
                == .refuse(.unpinned(endpoint: TofuEndpoint(host: "192.168.1.2", port: 443), presented: a)))
    }

    @Test func noCertificateIsRefused() {
        #expect(decide(console, presenting: nil, as: .console()) == .refuse(.noCertificate(endpoint: console)))
        #expect(
            decide(media, presenting: nil, as: .media(vouchedBy: console), pins: [console: TofuPin(fingerprint: a)])
                == .refuse(.noCertificate(endpoint: media)))
    }

    // MARK: Media

    @Test func aMediaEndpointIsLearnedOnFirstUseWithoutAPrompt() {
        #expect(
            decide(media, presenting: b, as: .media(vouchedBy: console), pins: [console: TofuPin(fingerprint: a)])
                == .learn(TofuPin(fingerprint: b, learnedVia: console)))
    }

    @Test func aLearnedMediaEndpointIsPinned() {
        let pins = [console: TofuPin(fingerprint: a), media: TofuPin(fingerprint: b, learnedVia: console)]
        #expect(decide(media, presenting: b, as: .media(vouchedBy: console), pins: pins) == .accept)
    }

    @Test func aChangedMediaCertificateIsForgottenWithoutAPrompt() {
        let pins = [console: TofuPin(fingerprint: a), media: TofuPin(fingerprint: b, learnedVia: console)]
        let decision = decide(media, presenting: a, as: .media(vouchedBy: console), pins: pins)
        #expect(decision == .forgetAndRefuse(.mediaChanged(endpoint: media, pinned: b, presented: a)))
        guard case .forgetAndRefuse(let error) = decision else { return }
        #expect(!error.needsConfirmation)
    }

    @Test func mediaIsLearnedOnlyByWayOfAPinnedConsole() {
        #expect(
            decide(media, presenting: b, as: .media(vouchedBy: console))
                == .refuse(.consoleNotPinned(endpoint: media, console: console)))
    }

    @Test func aMediaURLPointingAtTheConsoleIsJudgedAsTheConsole() {
        let pins = [console: TofuPin(fingerprint: a)]
        #expect(decide(console, presenting: a, as: .media(vouchedBy: console), pins: pins) == .accept)
        #expect(
            decide(console, presenting: b, as: .media(vouchedBy: console), pins: pins)
                == .refuse(.changed(endpoint: console, pinned: a, presented: b)))
    }

    @Test func aConfirmedPinIsNeverForgottenSilentlyByMediaTraffic() {
        let other = TofuEndpoint(host: "192.168.1.2", port: 443)
        let pins = [console: TofuPin(fingerprint: a), other: TofuPin(fingerprint: a)]
        #expect(
            decide(other, presenting: b, as: .media(vouchedBy: console), pins: pins)
                == .refuse(.changed(endpoint: other, pinned: a, presented: b)))
    }
}

struct TofuEndpointTests {
    @Test func keysAreHostColonPort() {
        #expect(TofuEndpoint(host: "Console.LOCAL", port: 443).key == "console.local:443")
        #expect(TofuEndpoint(host: "192.168.1.1", port: 7443).key == "192.168.1.1:7443")
        #expect(TofuEndpoint(host: "[fe80::1]", port: 443).key == "[fe80::1]:443")
        #expect(TofuEndpoint(host: "[fe80::1]", port: 443) == TofuEndpoint(host: "fe80::1", port: 443))
    }

    @Test(arguments: [
        ("https://192.168.1.1", "192.168.1.1:443"),
        ("https://192.168.1.1:8443/api", "192.168.1.1:8443"),
        ("wss://192.168.1.1:7443/ws/livestream?token=x", "192.168.1.1:7443"),
        ("wss://console.local/ws", "console.local:443"),
        ("rtsps://192.168.1.1:7441/alias?enableSrtp", "192.168.1.1:7441"),
        ("https://[fe80::1]:8443", "[fe80::1]:8443"),
    ])
    func endpointsFromURLs(url: String, key: String) throws {
        let parsed = try #require(URL(string: url))
        #expect(TofuEndpoint(url: parsed)?.key == key)
    }

    @Test func aURLWithoutAHostHasNoEndpoint() throws {
        #expect(TofuEndpoint(url: try #require(URL(string: "file:///tmp/x"))) == nil)
    }
}

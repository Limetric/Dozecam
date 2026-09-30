import Foundation
import Testing

@testable import Dozecam

/// The store applying the decisions: what gets pinned, learned and forgotten,
/// and that it survives a restart.
struct TofuTrustStoreTests {
    let console = TofuEndpoint(host: "192.168.1.1", port: 443)
    let media = TofuEndpoint(host: "192.168.1.1", port: 7443)
    let otherMedia = TofuEndpoint(host: "192.168.1.1", port: 7441)
    let a = "AA:AA"
    let b = "BB:BB"
    let c = "CC:CC"

    @Test func confirmThenPinOnlyAfterSignInSucceeds() {
        let store = TofuTrustStore(fileURL: nil)
        // First contact: refused, nothing stored.
        #expect(
            store.evaluate(presented: a, at: console, role: .console()).failure
                == .unpinned(endpoint: console, presented: a))
        // The user confirms; the sign-in session accepts it, still unpinned,
        // so a wrong password leaves nothing behind.
        #expect(store.evaluate(presented: a, at: console, role: .console(confirming: a)).failure == nil)
        #expect(store.pin(for: console) == nil)
        // The sign-in succeeded: now it is pinned, and a plain session accepts.
        store.confirmConsole(console, fingerprint: a)
        #expect(store.pin(for: console) == TofuPin(fingerprint: a))
        #expect(store.evaluate(presented: a, at: console, role: .console()).failure == nil)
    }

    @Test func aChangedConsoleCertificateKeepsThePinUntilConfirmed() {
        let store = TofuTrustStore(fileURL: nil)
        store.confirmConsole(console, fingerprint: a)
        #expect(
            store.evaluate(presented: b, at: console, role: .console()).failure
                == .changed(endpoint: console, pinned: a, presented: b))
        #expect(store.fingerprint(for: console) == a)
        store.confirmConsole(console, fingerprint: b)
        #expect(store.fingerprint(for: console) == b)
    }

    @Test func mediaIsLearnedThenPinned() {
        let store = TofuTrustStore(fileURL: nil)
        store.confirmConsole(console, fingerprint: a)
        #expect(store.evaluate(presented: b, at: media, role: .media(vouchedBy: console)).failure == nil)
        #expect(store.pin(for: media) == TofuPin(fingerprint: b, learnedVia: console))
        #expect(store.evaluate(presented: b, at: media, role: .media(vouchedBy: console)).failure == nil)
    }

    @Test func aChangedMediaCertificateIsForgottenAndRelearned() {
        let store = TofuTrustStore(fileURL: nil)
        store.confirmConsole(console, fingerprint: a)
        _ = store.evaluate(presented: b, at: media, role: .media(vouchedBy: console))
        #expect(
            store.evaluate(presented: c, at: media, role: .media(vouchedBy: console)).failure
                == .mediaChanged(endpoint: media, pinned: b, presented: c))
        #expect(store.pin(for: media) == nil)
        // The next negotiation learns the new certificate.
        #expect(store.evaluate(presented: c, at: media, role: .media(vouchedBy: console)).failure == nil)
        #expect(store.fingerprint(for: media) == c)
        // The console's own pin was never touched.
        #expect(store.fingerprint(for: console) == a)
    }

    @Test func confirmingANewConsoleCertificateForgetsTheMediaLearnedOnIt() {
        let store = TofuTrustStore(fileURL: nil)
        let otherConsole = TofuEndpoint(host: "192.168.2.1", port: 443)
        let otherConsoleMedia = TofuEndpoint(host: "192.168.2.1", port: 7443)
        store.confirmConsole(console, fingerprint: a)
        store.confirmConsole(otherConsole, fingerprint: a)
        _ = store.evaluate(presented: b, at: media, role: .media(vouchedBy: console))
        _ = store.evaluate(presented: c, at: otherMedia, role: .media(vouchedBy: console))
        _ = store.evaluate(presented: b, at: otherConsoleMedia, role: .media(vouchedBy: otherConsole))

        store.confirmConsole(console, fingerprint: c)

        #expect(store.fingerprint(for: console) == c)
        #expect(store.pin(for: media) == nil)
        #expect(store.pin(for: otherMedia) == nil)
        // Another console's pins are its own.
        #expect(store.fingerprint(for: otherConsole) == a)
        #expect(store.fingerprint(for: otherConsoleMedia) == b)
    }

    @Test func anOrdinarySignInKeepsTheLearnedMediaPins() {
        let store = TofuTrustStore(fileURL: nil)
        store.confirmConsole(console, fingerprint: a)
        _ = store.evaluate(presented: b, at: media, role: .media(vouchedBy: console))
        // Re-confirming the pin already stored is an ordinary sign-in.
        store.confirmConsole(console, fingerprint: a)
        #expect(store.fingerprint(for: media) == b)
    }

    @Test func forgetDropsOneEndpoint() {
        let store = TofuTrustStore(fileURL: nil)
        store.confirmConsole(console, fingerprint: a)
        _ = store.evaluate(presented: b, at: media, role: .media(vouchedBy: console))
        store.forget(media)
        #expect(store.pin(for: media) == nil)
        #expect(store.fingerprint(for: console) == a)
    }

    @Test func pinsSurviveARestartAndStayOutOfBackups() throws {
        let directory = URL.temporaryDirectory.appending(path: "tofu-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: directory) }
        let file = directory.appending(path: "Trust/tofu-pins.json")

        let store = TofuTrustStore(fileURL: file)
        store.confirmConsole(console, fingerprint: a)
        _ = store.evaluate(presented: b, at: media, role: .media(vouchedBy: console))

        let reopened = TofuTrustStore(fileURL: file)
        #expect(reopened.pin(for: console) == TofuPin(fingerprint: a))
        #expect(reopened.pin(for: media) == TofuPin(fingerprint: b, learnedVia: console))

        let values = try file.deletingLastPathComponent().resourceValues(forKeys: [.isExcludedFromBackupKey])
        #expect(values.isExcludedFromBackup == true)
    }

    @Test func anUnreadableFileStartsEmpty() throws {
        let directory = URL.temporaryDirectory.appending(path: "tofu-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: directory) }
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let file = directory.appending(path: "tofu-pins.json")
        try Data("not json".utf8).write(to: file)
        #expect(TofuTrustStore(fileURL: file).pin(for: console) == nil)
    }
}

extension Result {
    /// The error, or nil on success.
    fileprivate var failure: Failure? {
        if case .failure(let error) = self { return error }
        return nil
    }
}

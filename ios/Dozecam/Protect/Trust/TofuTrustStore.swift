import Foundation
import Security
import Synchronization
import os

/// The certificate pins, one per endpoint, and the decisions that change
/// them.
///
/// Pins are public values (shared/spec/privacy.md: not encrypted), so they
/// live in a small JSON file in Application Support rather than the
/// Keychain: private to the app, excluded from backups, readable after the
/// first unlock so the monitor can reconnect with the screen locked, and gone
/// with the app, unlike Keychain items, which outlive an uninstall. Reads are
/// served from memory: the TLS callback that consults them runs on
/// URLSession's queue and must not wait on disk.
///
/// Android reference: `TofuTrustStore`.
final class TofuTrustStore: Sendable {
    /// The app's pins, in `Application Support/Trust/tofu-pins.json`.
    static let shared = TofuTrustStore(fileURL: defaultFileURL)

    static var defaultFileURL: URL {
        URL.applicationSupportDirectory.appending(path: "Trust/tofu-pins.json")
    }

    private let fileURL: URL?
    private let pins: Mutex<[TofuEndpoint: TofuPin]>

    /// - Parameter fileURL: where pins persist; nil keeps them in memory only.
    init(fileURL: URL?) {
        self.fileURL = fileURL
        pins = Mutex(fileURL.map(Self.read(from:)) ?? [:])
    }

    func pin(for endpoint: TofuEndpoint) -> TofuPin? {
        pins.withLock { $0[endpoint] }
    }

    /// The fingerprint pinned for an endpoint, for showing next to a
    /// presented one.
    func fingerprint(for endpoint: TofuEndpoint) -> String? {
        pin(for: endpoint)?.fingerprint
    }

    /// Judges the certificate an endpoint presented and applies the decision:
    /// learns a media endpoint's first certificate, forgets a learned one that
    /// changed. Never pins a console; that is `confirmConsole`.
    func evaluate(presented: String?, at endpoint: TofuEndpoint, role: TofuRole) -> Result<Void, TofuTrustError> {
        mutate { pins in
            switch TofuPolicy.decide(endpoint: endpoint, presented: presented, role: role, pin: { pins[$0] }) {
            case .accept:
                return .success(())
            case .learn(let pin):
                pins[endpoint] = pin
                return .success(())
            case .refuse(let error):
                return .failure(error)
            case .forgetAndRefuse(let error):
                pins[endpoint] = nil
                return .failure(error)
            }
        }
    }

    /// Judges the leaf certificate of a server trust, for URLSession and the
    /// `rtsps://` TLS proxy alike. The chain itself is never evaluated:
    /// consoles are self-signed and hostname verification is off.
    func evaluate(_ trust: SecTrust, at endpoint: TofuEndpoint, role: TofuRole) -> Result<Void, TofuTrustError> {
        evaluate(presented: CertificateFingerprint.leaf(of: trust), at: endpoint, role: role)
    }

    /// Pins the certificate the user confirmed for a console. Call it only
    /// once a sign-in has succeeded behind it (a session built with
    /// `confirming: fingerprint`), so a wrong password never leaves a console
    /// pinned.
    ///
    /// Replacing a different pin means the console reissued its certificate,
    /// and its media ports will have reissued theirs; those pins were learned
    /// without a prompt, so nothing on screen could clear them, and they are
    /// forgotten here. Confirming the pin already stored changes nothing.
    func confirmConsole(_ console: TofuEndpoint, fingerprint: String) {
        mutate { pins in
            let previous = pins[console]
            pins[console] = TofuPin(fingerprint: fingerprint)
            if let previous, previous.fingerprint != fingerprint {
                pins = pins.filter { $0.value.learnedVia != console }
            }
        }
    }

    /// Forgets one endpoint's pin.
    func forget(_ endpoint: TofuEndpoint) {
        mutate { $0[endpoint] = nil }
    }

    /// Runs `body` on the pins under the lock and persists them if it changed
    /// them. Writing under the lock keeps the file in the order of the
    /// changes.
    private func mutate<T>(_ body: (inout [TofuEndpoint: TofuPin]) -> T) -> T {
        pins.withLock { pins in
            let before = pins
            let result = body(&pins)
            if pins != before, let fileURL { Self.write(pins, to: fileURL) }
            return result
        }
    }

    // MARK: - File

    private struct Entry: Codable {
        let endpoint: TofuEndpoint
        let pin: TofuPin
    }

    private static let log = Logger(subsystem: "app.dozecam", category: "tofu")

    private static func read(from url: URL) -> [TofuEndpoint: TofuPin] {
        guard let data = try? Data(contentsOf: url) else { return [:] }
        do {
            let entries = try JSONDecoder().decode([Entry].self, from: data)
            return Dictionary(entries.map { ($0.endpoint, $0.pin) }, uniquingKeysWith: { _, last in last })
        } catch {
            // An unreadable file costs a prompt, never trust: every console
            // is asked about again.
            log.error("Unreadable pin file, starting empty: \(error, privacy: .public)")
            return [:]
        }
    }

    private static func write(_ pins: [TofuEndpoint: TofuPin], to url: URL) {
        let entries = pins.map { Entry(endpoint: $0.key, pin: $0.value) }.sorted { $0.endpoint.key < $1.endpoint.key }
        do {
            var directory = url.deletingLastPathComponent()
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            // Excluding the directory covers the file, which an atomic write
            // replaces each time.
            var values = URLResourceValues()
            values.isExcludedFromBackup = true
            try directory.setResourceValues(values)
            let encoder = JSONEncoder()
            encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
            try encoder.encode(entries).write(
                to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        } catch {
            // The pins still hold in memory for this run.
            log.error("Could not save pins: \(error, privacy: .public)")
        }
    }
}

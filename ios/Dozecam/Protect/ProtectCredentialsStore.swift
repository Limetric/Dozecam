import Foundation

/// A console sign-in: the address as the user typed it (trimmed), the
/// account, and the Integration API key minted for it.
///
/// Android reference: `ProtectCredentials`.
struct ProtectCredentials: Codable, Equatable, Sendable {
    let host: String
    let username: String
    let password: String
    /// The console API key for the public Integration API, minted during
    /// onboarding and reused on the next run so each setup does not litter
    /// the console with keys. Nil on consoles that cannot issue one.
    var apiKey: String?

    init(host: String, username: String, password: String, apiKey: String? = nil) {
        self.host = host
        self.username = username
        self.password = password
        self.apiKey = apiKey
    }

    /// The stored key, when it was minted for this console and user: onboarding
    /// tries it first and mints a new one only when there is none or the
    /// console no longer accepts it (shared/spec/protect.md). A key minted for
    /// one console is never sent to another.
    func apiKey(reusableFor host: String, username: String) -> String? {
        self.host == host.trimmingCharacters(in: .whitespacesAndNewlines) && self.username == username ? apiKey : nil
    }
}

/// Where the console sign-in is kept. One console is signed in at a time.
protocol CredentialsStore: Sendable {
    func save(_ credentials: ProtectCredentials) throws
    /// Nil when nothing usable is stored. Throws when the store cannot be
    /// read, which is not the same as empty: the Keychain is unreadable before
    /// the first unlock after a reboot.
    func load() throws -> ProtectCredentials?
    func clear() throws
}

/// The console sign-in, encrypted at rest in the Keychain as one item, so a
/// save replaces address, account, password and key together. Readable while
/// locked after the first unlock, never synced or restored onto another
/// device (see `Keychain`).
///
/// Android reference: `EncryptedCredentialsStore` (`SecurePrefs`). iOS needs
/// no plain-file fallback: the Keychain has no equivalent of the Android
/// Keystore corruption that one covers.
struct KeychainCredentialsStore: CredentialsStore {
    private static let account = "protect-console"

    let keychain: Keychain

    init(keychain: Keychain = Keychain(service: "app.dozecam.protect-credentials")) {
        self.keychain = keychain
    }

    func save(_ credentials: ProtectCredentials) throws {
        try keychain.set(JSONEncoder().encode(credentials), for: Self.account)
    }

    func load() throws -> ProtectCredentials? {
        guard let data = try keychain.data(for: Self.account) else { return nil }
        // Undecodable is treated as absent, like Android's missing fields: the
        // user signs in again.
        return try? JSONDecoder().decode(ProtectCredentials.self, from: data)
    }

    func clear() throws {
        try keychain.remove(Self.account)
    }
}

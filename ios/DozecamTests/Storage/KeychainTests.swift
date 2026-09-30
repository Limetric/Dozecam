import Foundation
import Security
import Testing

@testable import Dozecam

/// Round trips through the simulator's Keychain, under a service of their own
/// that each test removes again.
struct KeychainTests {
    let keychain = Keychain(service: "app.dozecam.tests.\(UUID().uuidString)")

    @Test func storesReadsOverwritesAndDeletes() throws {
        defer { try? keychain.removeAll() }
        #expect(try keychain.data(for: "item") == nil)

        try keychain.set(Data("first".utf8), for: "item")
        #expect(try keychain.data(for: "item") == Data("first".utf8))

        try keychain.set(Data("second".utf8), for: "item")
        #expect(try keychain.data(for: "item") == Data("second".utf8))

        try keychain.remove("item")
        #expect(try keychain.data(for: "item") == nil)
        // Removing what is not there is fine.
        try keychain.remove("item")
    }

    @Test func accountsAndServicesAreSeparate() throws {
        let other = Keychain(service: keychain.service + ".other")
        defer {
            try? keychain.removeAll()
            try? other.removeAll()
        }
        try keychain.set(Data("one".utf8), for: "a")
        try keychain.set(Data("two".utf8), for: "b")
        #expect(try keychain.data(for: "a") == Data("one".utf8))
        #expect(try other.data(for: "a") == nil)

        try keychain.removeAll()
        #expect(try keychain.data(for: "a") == nil)
        #expect(try keychain.data(for: "b") == nil)
    }

    /// Readable while locked after the first unlock, and never synced or
    /// restored onto another device.
    @Test func itemsAreThisDeviceOnlyAfterFirstUnlock() throws {
        defer { try? keychain.removeAll() }
        try keychain.set(Data("x".utf8), for: "item")
        try keychain.set(Data("y".utf8), for: "item")  // an update keeps it too
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: keychain.service,
            kSecAttrAccount as String: "item",
            kSecReturnAttributes as String: true,
        ]
        var result: CFTypeRef?
        #expect(SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess)
        let attributes = try #require(result as? [String: Any])
        #expect(
            attributes[kSecAttrAccessible as String] as? String
                == kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly as String)
    }
}

struct ProtectCredentialsStoreTests {
    let store = KeychainCredentialsStore(keychain: Keychain(service: "app.dozecam.tests.\(UUID().uuidString)"))

    @Test func savesLoadsReplacesAndClears() throws {
        defer { try? store.clear() }
        #expect(try store.load() == nil)

        let first = ProtectCredentials(host: "192.168.1.1", username: "parent", password: "secret", apiKey: "key-1")
        try store.save(first)
        #expect(try store.load() == first)

        let second = ProtectCredentials(host: "console.local", username: "other", password: "pw")
        try store.save(second)
        #expect(try store.load() == second)
        #expect(try store.load()?.apiKey == nil)

        try store.clear()
        #expect(try store.load() == nil)
    }

    @Test func undecodableIsTreatedAsNothingStored() throws {
        defer { try? store.clear() }
        try store.keychain.set(Data("garbage".utf8), for: "protect-console")
        #expect(try store.load() == nil)
    }

    /// The stored key is tried first for the same console and user only.
    @Test func theAPIKeyIsReusedForTheSameConsoleAndUserOnly() {
        let saved = ProtectCredentials(host: "192.168.1.1", username: "parent", password: "secret", apiKey: "key-1")
        #expect(saved.apiKey(reusableFor: "192.168.1.1", username: "parent") == "key-1")
        #expect(saved.apiKey(reusableFor: " 192.168.1.1 ", username: "parent") == "key-1")
        #expect(saved.apiKey(reusableFor: "192.168.1.2", username: "parent") == nil)
        #expect(saved.apiKey(reusableFor: "192.168.1.1", username: "someone") == nil)
        #expect(
            ProtectCredentials(host: "192.168.1.1", username: "parent", password: "secret")
                .apiKey(reusableFor: "192.168.1.1", username: "parent") == nil)
    }
}

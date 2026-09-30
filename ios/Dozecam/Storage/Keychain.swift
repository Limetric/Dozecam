import Foundation
import Security

/// A failed Keychain call.
struct KeychainError: Error, Equatable, Sendable, CustomStringConvertible {
    let operation: String
    let status: OSStatus

    var description: String {
        let message = SecCopyErrorMessageString(status, nil) as String? ?? "unknown error"
        return "Keychain \(operation) failed: \(message) (\(status))"
    }
}

/// Generic-password items under one service.
///
/// Every item is `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`: readable
/// while the device is locked, once it has been unlocked since boot, so the
/// monitor can reconnect overnight; never synced to iCloud Keychain and never
/// restored from a backup onto another device.
struct Keychain: Sendable {
    let service: String

    /// The item's data, or nil when there is no item. Throws on anything
    /// else, notably `errSecInteractionNotAllowed` before the first unlock,
    /// which must not be mistaken for "nothing stored".
    func data(for account: String) throws(KeychainError) -> Data? {
        var query = baseQuery(account)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        switch status {
        case errSecSuccess:
            guard let data = result as? Data else { throw KeychainError(operation: "read", status: errSecDecode) }
            return data
        case errSecItemNotFound:
            return nil
        default:
            throw KeychainError(operation: "read", status: status)
        }
    }

    /// Stores `data`, replacing the item if there is one.
    func set(_ data: Data, for account: String) throws(KeychainError) {
        let attributes: [String: Any] = [
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
        ]
        let status = SecItemUpdate(baseQuery(account) as CFDictionary, attributes as CFDictionary)
        switch status {
        case errSecSuccess:
            return
        case errSecItemNotFound:
            let query = baseQuery(account).merging(attributes) { _, new in new }
            let added = SecItemAdd(query as CFDictionary, nil)
            guard added == errSecSuccess else { throw KeychainError(operation: "add", status: added) }
        default:
            throw KeychainError(operation: "update", status: status)
        }
    }

    /// Deletes the item; deleting one that is not there is not an error.
    func remove(_ account: String) throws(KeychainError) {
        try delete(baseQuery(account))
    }

    /// Deletes every item under the service.
    func removeAll() throws(KeychainError) {
        try delete(baseQuery(nil))
    }

    private func delete(_ query: [String: Any]) throws(KeychainError) {
        let status = SecItemDelete(query as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else {
            throw KeychainError(operation: "delete", status: status)
        }
    }

    private func baseQuery(_ account: String?) -> [String: Any] {
        var query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecUseDataProtectionKeychain as String: true,
        ]
        if let account { query[kSecAttrAccount as String] = account }
        return query
    }
}

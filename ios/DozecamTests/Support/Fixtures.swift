import Foundation

/// Loads the golden test vectors in `shared/fixtures` (#61), which the
/// Android tests read too. Tests run in the simulator, which sees the host's
/// file system, so fixtures are read straight from the checkout rather than
/// copied into the test bundle.
enum Fixtures {
    struct Missing: Error, CustomStringConvertible {
        let url: URL
        var description: String { "no fixture at \(url.path)" }
    }

    /// `<repo>/shared/fixtures`, found from this file's location
    /// (`<repo>/ios/DozecamTests/Support/Fixtures.swift`).
    static let root: URL = URL(filePath: #filePath)
        .deletingLastPathComponent()  // Support
        .deletingLastPathComponent()  // DozecamTests
        .deletingLastPathComponent()  // ios
        .deletingLastPathComponent()  // <repo>
        .appending(path: "shared/fixtures", directoryHint: .isDirectory)

    static func url(_ path: String, in root: URL = root) throws -> URL {
        let url = root.appending(path: path)
        guard FileManager.default.fileExists(atPath: url.path) else { throw Missing(url: url) }
        return url
    }

    static func data(_ path: String, in root: URL = root) throws -> Data {
        try Data(contentsOf: url(path, in: root))
    }

    struct UnknownKeys: Error, CustomStringConvertible {
        let path: String
        let keys: [String]
        var description: String { "\(path) has keys the test type does not read: \(keys.joined(separator: ", "))" }
    }

    /// Decodes a fixture and, like Android's loader, rejects keys the type
    /// does not read, so a typo in a fixture fails instead of silently testing
    /// nothing. JSONDecoder has no strict mode, so the value is encoded back
    /// and every key of the fixture must survive the round trip (or hold null).
    static func decode<T: Codable>(_ type: T.Type, from path: String, in root: URL = root) throws -> T {
        let raw = try data(path, in: root)
        let value = try JSONDecoder().decode(type, from: raw)
        let original = try JSONSerialization.jsonObject(with: raw, options: .fragmentsAllowed)
        let roundTrip = try JSONSerialization.jsonObject(with: JSONEncoder().encode(value), options: .fragmentsAllowed)
        let unknown = unknownKeys(original, known: roundTrip, at: "")
        guard unknown.isEmpty else { throw UnknownKeys(path: path, keys: unknown) }
        return value
    }

    private static func unknownKeys(_ value: Any, known: Any?, at path: String) -> [String] {
        switch value {
        case let object as [String: Any]:
            let knownObject = known as? [String: Any] ?? [:]
            return object.keys.sorted().flatMap { key -> [String] in
                let child = object[key]!
                let childPath = path.isEmpty ? key : "\(path).\(key)"
                guard let knownChild = knownObject[key] else {
                    return child is NSNull ? [] : [childPath]
                }
                return unknownKeys(child, known: knownChild, at: childPath)
            }
        case let array as [Any]:
            let knownArray = known as? [Any] ?? []
            return array.enumerated().flatMap { index, element in
                let known = index < knownArray.count ? knownArray[index] : nil
                return unknownKeys(element, known: known, at: "\(path)[\(index)]")
            }
        default:
            return []
        }
    }
}

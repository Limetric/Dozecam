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

    static func decode<T: Decodable>(_ type: T.Type, from path: String, in root: URL = root) throws -> T {
        try JSONDecoder().decode(type, from: data(path, in: root))
    }
}

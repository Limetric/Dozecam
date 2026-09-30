import Foundation

/// A UserDefaults suite of its own for one test, removed when the test ends.
final class TestDefaults: Sendable {
    let suiteName = "DozecamTests-\(UUID().uuidString)"

    /// A new object for the suite on every read. UserDefaults is not Sendable,
    /// so a repository actor can only be handed one nobody else holds; every
    /// object for a suite reads and writes the same values.
    var defaults: UserDefaults { UserDefaults(suiteName: suiteName)! }

    deinit {
        defaults.removePersistentDomain(forName: suiteName)
    }
}

/// A directory of its own for one test, removed when the test ends.
final class TestDirectory {
    let url: URL

    init() throws {
        url = FileManager.default.temporaryDirectory.appending(
            path: "DozecamTests-\(UUID().uuidString)", directoryHint: .isDirectory)
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
    }

    deinit {
        try? FileManager.default.removeItem(at: url)
    }
}

/// The next `count` values a stream delivers.
func take<Value>(_ count: Int, from stream: AsyncStream<Value>) async -> [Value] {
    var values: [Value] = []
    for await value in stream {
        values.append(value)
        if values.count == count { break }
    }
    return values
}

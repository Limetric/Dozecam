import Foundation
import Testing

/// The iOS twin of Android's `FixtureCoverageTest`: tests pick shared fixture
/// cases by name, so every named case in a fixture iOS has adopted must appear,
/// as a string literal, in some iOS test. Names are unique across all fixtures
/// (Android enforces that for every file).
///
/// A fixture is adopted once an iOS test reads it; add it to `adopted` in the
/// same change. Areas arrive with their features (#64, #67, #68).
struct FixtureCoverageTests {
    static let adopted: [String] = [
        "livestream/av1-config-repair.json",
        "livestream/decoder.json",
        "protect-api/cameras.expected.json",
        "protect-api/legacy/expected.json",
        "protect-api/public/expected.json",
        "stream-url/monitorable.json",
        "stream-url/normalize.json",
        "stream-url/valid.json",
    ]

    @Test func everyCaseOfAnAdoptedFixtureIsRunByAnIOSTest() throws {
        let testSources = Fixtures.root
            .deletingLastPathComponent()  // shared
            .deletingLastPathComponent()  // <repo>
            .appending(path: "ios/DozecamTests", directoryHint: .isDirectory)
        let sources = try swiftSources(in: testSources)
        var unused: [String] = []
        for path in Self.adopted {
            for name in try caseNames(in: path) where !sources.contains("\"\(name)\"") {
                unused.append("\(path): \"\(name)\"")
            }
        }
        #expect(unused.isEmpty, "fixture cases no iOS test runs:\n\(unused.joined(separator: "\n"))")
    }

    @Test func adoptedFixturesExist() throws {
        for path in Self.adopted {
            _ = try Fixtures.url(path)
        }
    }

    /// Names of the objects in the fixture's top-level arrays.
    private func caseNames(in path: String) throws -> [String] {
        let top = try JSONSerialization.jsonObject(with: Fixtures.data(path))
        guard let object = top as? [String: Any] else { return [] }
        return object.values.compactMap { $0 as? [Any] }.flatMap { array in
            array.compactMap { ($0 as? [String: Any])?["name"] as? String }
        }
    }

    private func swiftSources(in directory: URL) throws -> String {
        let files =
            FileManager.default.enumerator(at: directory, includingPropertiesForKeys: nil)?
            .compactMap { $0 as? URL }
            .filter { $0.pathExtension == "swift" } ?? []
        return try files.map { try String(contentsOf: $0, encoding: .utf8) }.joined(separator: "\n")
    }
}

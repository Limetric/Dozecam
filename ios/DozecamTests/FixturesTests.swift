import Foundation
import Testing

struct FixturesTests {
    @Test func rootIsTheSharedFixturesDirectoryOfThisCheckout() {
        #expect(Fixtures.root.pathComponents.suffix(2) == ["shared", "fixtures"])
        let repo = Fixtures.root.deletingLastPathComponent().deletingLastPathComponent()
        #expect(FileManager.default.fileExists(atPath: repo.appending(path: "ios/project.yml").path))
    }

    @Test func decodesJSONFixtures() throws {
        let root = try temporaryRoot()
        defer { try? FileManager.default.removeItem(at: root) }
        try FileManager.default.createDirectory(at: root.appending(path: "detector"), withIntermediateDirectories: true)
        try Data(#"{"threshold": 0.1, "sustainMs": 1500}"#.utf8).write(to: root.appending(path: "detector/case.json"))

        struct Case: Codable, Equatable {
            let threshold: Double
            let sustainMs: Int
        }
        let decoded = try Fixtures.decode(Case.self, from: "detector/case.json", in: root)
        #expect(decoded == Case(threshold: 0.1, sustainMs: 1500))
    }

    @Test func readsRawBytes() throws {
        let root = try temporaryRoot()
        defer { try? FileManager.default.removeItem(at: root) }
        try Data([0x00, 0x01, 0xFF]).write(to: root.appending(path: "frame.bin"))
        #expect(try Fixtures.data("frame.bin", in: root) == Data([0x00, 0x01, 0xFF]))
    }

    @Test func aMissingFixtureNamesItsPath() throws {
        let root = try temporaryRoot()
        defer { try? FileManager.default.removeItem(at: root) }
        #expect {
            try Fixtures.data("nope.json", in: root)
        } throws: { error in
            (error as? Fixtures.Missing)?.url.lastPathComponent == "nope.json"
        }
    }

    private func temporaryRoot() throws -> URL {
        let root = FileManager.default.temporaryDirectory.appending(path: "fixtures-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        return root
    }
}

struct FixturesStrictDecodingTests {
    struct Case: Codable, Equatable {
        let threshold: Double
        let label: String?
    }

    @Test func unknownKeysAreRejected() throws {
        let root = FileManager.default.temporaryDirectory.appending(path: "fixtures-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        try Data(#"{"cases": [{"threshold": 0.1, "treshold": 0.2}]}"#.utf8).write(to: root.appending(path: "c.json"))
        struct Table: Codable { let cases: [Case] }
        #expect {
            try Fixtures.decode(Table.self, from: "c.json", in: root)
        } throws: { error in
            (error as? Fixtures.UnknownKeys)?.keys == ["cases[0].treshold"]
        }
    }

    @Test func explicitNullsAndKnownKeysPass() throws {
        let root = FileManager.default.temporaryDirectory.appending(path: "fixtures-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        try Data(#"{"threshold": 0.1, "label": null}"#.utf8).write(to: root.appending(path: "c.json"))
        #expect(try Fixtures.decode(Case.self, from: "c.json", in: root) == Case(threshold: 0.1, label: nil))
    }
}

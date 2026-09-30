import Foundation
import Testing

@testable import Dozecam

/// Exercised against the real initialization segment a UniFi G6 camera sent
/// over the livestream socket, captured from the device. The capture, its
/// repaired form and the sizes the repair must produce are the shared vectors
/// in `shared/fixtures/livestream/av1-config-repair.json`. One test per
/// Android `Av1ConfigRepairTest` test.
struct Av1ConfigRepairTests {
    private struct Table: Decodable {
        let repair: Repair
        let unchanged: [Unchanged]
    }

    private struct Repair: Decodable {
        let name: String
        let input: String
        let output: String
        let av1cSizeBefore: Int
        let av1cSizeAfter: Int
        let appendedHex: String
        let grownBoxes: [String]
        let untouchedBoxes: [String]
    }

    private struct Unchanged: Decodable {
        let name: String
        let input: String
    }

    private let table: Table
    private let fixture: Repair
    private let realInitSegment: Data
    private let appended: Data

    init() throws {
        table = try Fixtures.decode(Table.self, from: "livestream/av1-config-repair.json")
        fixture = table.repair
        realInitSegment = try Self.bytes(fixture.input)
        var hex = Substring(fixture.appendedHex)
        var appended = Data()
        while !hex.isEmpty {
            appended.append(try #require(UInt8(hex.prefix(2), radix: 16), "\(fixture.name): appendedHex"))
            hex = hex.dropFirst(2)
        }
        self.appended = appended
    }

    private static func bytes(_ file: String) throws -> Data {
        try Fixtures.data("livestream/\(file)")
    }

    /// Checks that the fixture's `unchanged` case called `name` comes back byte for byte.
    private func expectUnchanged(_ name: String) throws {
        let cases = table.unchanged.filter { $0.name == name }
        guard cases.count == 1, let unchanged = cases.first else {
            Issue.record("no single fixture case \"\(name)\"")
            return
        }
        let input = try Self.bytes(unchanged.input)
        #expect(Av1ConfigRepair.repair(input) == input, "\(unchanged.name)")
    }

    /// Scan for the boxes this repair resizes, with their declared sizes.
    private func boxes(_ data: Data) -> [String: Int] {
        let buffer = [UInt8](data)
        var found: [String: Int] = [:]
        func walk(_ from: Int, _ end: Int) {
            var cursor = from
            while cursor + 8 <= end {
                let size = readSize(buffer, cursor)
                if size < 8 || cursor + size > end { return }
                let type = String(decoding: buffer[cursor + 4..<cursor + 8], as: UTF8.self)
                // First occurrence wins: this segment carries a video trak
                // followed by an audio one, and the assertions below are
                // about the video chain that encloses av1C.
                if found[type] == nil { found[type] = size }
                let childrenFrom: Int? =
                    switch type {
                    case "moov", "trak", "mdia", "minf", "stbl": cursor + 8
                    case "stsd": cursor + 16
                    case "av01": cursor + 8 + 78
                    default: nil
                    }
                if let childrenFrom { walk(childrenFrom, cursor + size) }
                cursor += size
            }
        }
        walk(0, buffer.count)
        return found
    }

    private func readSize(_ buffer: [UInt8], _ offset: Int) -> Int {
        Int(buffer[offset]) << 24 | Int(buffer[offset + 1]) << 16 | Int(buffer[offset + 2]) << 8
            | Int(buffer[offset + 3])
    }

    private func indexOfAv1cEnd(_ data: Data) throws -> Int {
        let buffer = [UInt8](data)
        let tag = Array("av1C".utf8)
        let at = try #require(
            (4...buffer.count - 4).first { Array(buffer[$0..<$0 + 4]) == tag }, "\(fixture.name): no av1C")
        let start = at - 4
        return start + readSize(buffer, start)
    }

    @Test func theCapturedSegmentIsTheShapeThatBreaksMedia3() {
        // Guards the premise: a 12-byte av1C is header plus a bare 4-byte
        // config record, with no configOBUs for the parser to read.
        #expect(boxes(realInitSegment)["av1C"] == fixture.av1cSizeBefore, "\(fixture.name): av1C before")
    }

    @Test func fillsInConfigOBUsSoTheRecordIsNoLongerTruncated() throws {
        let repaired = Av1ConfigRepair.repair(realInitSegment)

        #expect(repaired.count == realInitSegment.count + appended.count, "\(fixture.name): size")
        #expect(boxes(repaired)["av1C"] == fixture.av1cSizeAfter, "\(fixture.name): av1C after")
        #expect(repaired == (try Self.bytes(fixture.output)), "\(fixture.name): output")
    }

    @Test func appendsAZeroLengthTemporalDelimiterOBU() throws {
        let repaired = [UInt8](Av1ConfigRepair.repair(realInitSegment))

        let av1cEnd = repaired.count - (realInitSegment.count - (try indexOfAv1cEnd(realInitSegment)))
        let tail = Data(repaired[av1cEnd - appended.count..<av1cEnd])
        // obu_type = 2 with a size field, then size 0. Media3 reads the type,
        // finds it is not a sequence header, and returns instead of throwing.
        #expect(tail == appended, "\(fixture.name): appended")
    }

    @Test func growsEveryEnclosingBoxSoTheTreeStaysParseable() {
        let before = boxes(realInitSegment)
        let after = boxes(Av1ConfigRepair.repair(realInitSegment))

        for type in fixture.grownBoxes {
            #expect(
                after[type] != nil && after[type] == before[type].map { $0 + appended.count },
                "\(fixture.name): \(type) size")
        }
        // The audio track and ftyp are untouched.
        for type in fixture.untouchedBoxes {
            #expect(after[type] != nil && after[type] == before[type], "\(fixture.name): \(type) size")
        }
    }

    @Test func theRepairedSegmentStillParsesAsACompleteBoxTree() {
        let repaired = Av1ConfigRepair.repair(realInitSegment)

        // A stale ancestor size would desynchronise the scan and lose boxes.
        #expect(Set(boxes(repaired).keys).isSuperset(of: boxes(realInitSegment).keys), "\(fixture.name)")
    }

    @Test func leavesASegmentThatAlreadyCarriesConfigOBUsAlone() throws {
        // Repairing twice must not keep appending. The fixture's repaired
        // segment is the repair's own output, which the test above holds it to.
        try expectUnchanged("a segment that already carries configOBUs is left alone")
    }

    @Test func leavesASegmentWithoutAnAv1CBoxAlone() throws {
        try expectUnchanged("a segment without an av1C box is left alone")
    }

    @Test func doesNotWalkOffTheEndOfATruncatedSegment() throws {
        // Returning it unchanged is correct; trapping would kill playback.
        try expectUnchanged("a truncated segment is returned unchanged rather than walked off the end of")
    }
}

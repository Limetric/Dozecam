import Foundation
import Testing

@testable import Dozecam

/// `shared/fixtures/sound-detector/rms.json`, and the float scale libVLC
/// delivers on iOS (shared/spec/alerts-and-sound-modes.md, iOS note).
struct PcmRmsTests {
    struct Fixture: Codable {
        struct Case: Codable {
            let name: String
            let samples: [Int16]
            let expected: Float
            let tolerance: Float
        }
        let cases: [Case]
    }

    static let fixture = Result { try Fixtures.decode(Fixture.self, from: "sound-detector/rms.json") }

    static func cases(_ names: String...) throws -> [Fixture.Case] {
        let all = try fixture.get().cases
        return try names.map { name in
            try #require(all.first { $0.name == name }, "rms.json has no case \"\(name)\"")
        }
    }

    @Test func theFixtureLevelsMatch() throws {
        let cases = try Self.cases(
            "silence is zero",
            "empty buffer is zero",
            "full-scale square wave is one",
            "half-scale square wave is one half",
        )
        #expect(try Self.fixture.get().cases.count == cases.count, "a case in rms.json is not run here")
        for testCase in cases {
            let level = PcmRms.of(int16: testCase.samples)
            #expect(abs(level - testCase.expected) <= testCase.tolerance, "\(testCase.name): \(level)")
        }
    }

    @Test func floatSamplesUseOneAsFullScale() {
        #expect(PcmRms.of([1, -1, 1, -1]) == 1)
        #expect(abs(PcmRms.of([0.5, -0.5]) - 0.5) < 0.000_1)
        #expect(PcmRms.of([Float]()) == 0)
    }

    /// Over-range floats (a decoder's overshoot) still read as at most full scale.
    @Test func levelsAreClampedToOne() {
        #expect(PcmRms.of([2, -2]) == 1)
    }

    /// A sine's RMS is its amplitude over the square root of two: the kind of
    /// signal the testbed's noise is (#59 measured 0.35).
    @Test func aSineReadsItsAmplitudeOverRootTwo() {
        let sine = (0..<48_000).map { Float(0.5 * sin(2 * Double.pi * 660 * Double($0) / 48_000)) }
        #expect(abs(PcmRms.of(sine) - Float(0.5 / 2.0.squareRoot())) < 0.001)
    }
}

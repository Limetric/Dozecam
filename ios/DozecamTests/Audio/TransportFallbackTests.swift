import Testing

@testable import Dozecam

/// `shared/fixtures/transport-fallback/fallback.json`, the rules Android's
/// `TransportFallbackTest` runs too.
struct TransportFallbackTests {
    struct Fixture: Codable {
        struct Step: Codable {
            let event: String
            let times: Int?
            let movesOn: Bool?
            let index: Int?
        }
        struct Case: Codable {
            let name: String
            let transportCount: Int
            let steps: [Step]
        }
        let restartsBeforeFallback: Int
        let cases: [Case]
    }

    static let fixture = Result { try Fixtures.decode(Fixture.self, from: "transport-fallback/fallback.json") }

    @Test func theDefaultIsTheSharedRestartCount() throws {
        #expect(TransportFallback.defaultRestartsBeforeFallback == (try Self.fixture.get().restartsBeforeFallback))
    }

    @Test(arguments: [
        "a transport is given several restarts before being abandoned",
        "restarts are counted here rather than read off the watchdog",
        "a transport that has ever decoded is kept through any later trouble",
        "a lone transport is never abandoned, because there is nowhere to go",
        "a fallback that is no better itself hands the turn back",
        "each transport gets its own run of restarts rather than the tail of the last",
    ])
    func fixtureCase(_ name: String) throws {
        let testCase = try #require(try Self.fixture.get().cases.first { $0.name == name })
        var fallback = TransportFallback(transportCount: testCase.transportCount)
        for (number, step) in testCase.steps.enumerated() {
            for _ in 0..<(step.times ?? 1) {
                switch step.event {
                case "restart":
                    let movedOn = fallback.onRestart()
                    if let movesOn = step.movesOn {
                        #expect(movedOn == movesOn, "\(name): step \(number)")
                    }
                case "audioDecoded":
                    fallback.onAudioDecoded()
                default:
                    Issue.record("\(name): unknown event \(step.event)")
                }
            }
            if let index = step.index {
                #expect(fallback.index == index, "\(name): step \(number)")
            }
        }
    }

    @Test func everyFixtureCaseIsListedAbove() throws {
        #expect(try Self.fixture.get().cases.count == 6)
    }
}

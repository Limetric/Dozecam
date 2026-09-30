import Foundation
import Testing

@testable import Dozecam

/// The byte streams and what they must decode to are the shared vectors in
/// `shared/fixtures/livestream` (see its README for the wire format). One test
/// per Android `LivestreamDecoderTest` test, playing the same named case.
struct LivestreamDecoderTests {
    private struct Table: Codable {
        let cases: [Case]
    }

    private struct Case: Codable {
        let name: String
        let messages: [Message]
    }

    private struct Message: Codable {
        let file: String
        let segments: [Segment]?
        let error: String?
    }

    private struct Segment: Codable {
        let type: String
        let codec: String?
        let text: String?
        let file: String?
    }

    private func bytes(_ file: String) throws -> Data {
        try Fixtures.data("livestream/\(file)")
    }

    /// Feeds the fixture case called `name` to a fresh decoder, message by message.
    private func play(_ name: String) throws {
        let cases = try Fixtures.decode(Table.self, from: "livestream/decoder.json").cases.filter { $0.name == name }
        guard cases.count == 1, let fixture = cases.first else {
            Issue.record("no single fixture case \"\(name)\"")
            return
        }
        var decoder = LivestreamDecoder()
        for (i, message) in fixture.messages.enumerated() {
            let place = "\(fixture.name), message \(i + 1)"
            let chunk = try bytes(message.file)
            switch message.error {
            case nil:
                guard let expected = message.segments else {
                    Issue.record("\(place): neither segments nor error")
                    return
                }
                let segments = try decoder.decode(chunk)
                try #require(segments.count == expected.count, "\(place): segment count")
                for (n, (want, got)) in zip(expected, segments).enumerated() {
                    try check("\(place), segment \(n + 1)", want, got)
                }
            case "protocol":
                #expect(throws: LivestreamProtocolError.self, "\(place)") { try decoder.decode(chunk) }
            case let error?:
                Issue.record("\(place): unknown error \(error)")
            }
        }
    }

    private func check(_ place: String, _ want: Segment, _ got: LivestreamSegment) throws {
        let data: Data
        switch (want.type, got) {
        case ("init", .initialization(let payload, let codec)):
            if let wanted = want.codec { #expect(codec == wanted, "\(place): codec") }
            data = payload
        case ("media", .media(let payload)):
            data = payload
        default:
            Issue.record("\(place): expected \(want.type), got \(got)")
            return
        }
        let expected: Data
        if let text = want.text {
            expected = Data(text.utf8)
        } else if let file = want.file {
            expected = try bytes(file)
        } else {
            Issue.record("\(place): segment has neither text nor file")
            return
        }
        #expect(data == expected, "\(place): data")
    }

    @Test func emitsTheInitSegmentWithTheCodecAnnouncedBeforeIt() throws {
        try play("emits the init segment with the codec announced before it")
    }

    @Test func assemblesAFragmentInMoofMdatVideoAudioOrder() throws {
        // Deliberately out of order on the wire.
        try play("assembles a fragment in moof mdat video audio order")
    }

    @Test func carriesAFrameSplitAcrossWebsocketMessages() throws {
        // Split mid-header, then mid-payload: both must survive.
        try play("carries a frame split across websocket messages")
    }

    @Test func decodesSeveralFragmentsArrivingInOneMessage() throws {
        try play("decodes several fragments arriving in one message")
    }

    @Test func doesNotLeakBoxesFromOneFragmentIntoTheNext() throws {
        // The second fragment carries no audio; the first one's must not ride along.
        try play("does not leak boxes from one fragment into the next")
    }

    @Test func ignoresAnEmptyFragmentRatherThanEmittingZeroBytes() throws {
        try play("ignores an empty fragment rather than emitting zero bytes")
    }

    @Test func ignoresTimestampFrames() throws {
        try play("ignores timestamp frames")
    }

    @Test func readsAPayloadLongerThanA16BitLength() throws {
        try play("reads a payload longer than a 16-bit length")
    }

    @Test func rejectsAnUnknownFrameTypeInsteadOfDesyncingSilently() throws {
        try play("rejects an unknown frame type instead of desyncing silently")
    }

    @Test func concatenatesABoxDeliveredAsSeveralChunks() throws {
        // The negotiated chunk size caps a frame's payload, so a large mdat
        // arrives as a run of MDAT frames. Keeping only the last would hand the
        // demuxer a truncated fragment.
        try play("concatenates a box delivered as several chunks")
    }

    @Test func keepsBoxOrderWhenEveryTypeIsChunked() throws {
        // Grouped by box, chunks in arrival order within each box.
        try play("keeps box order when every type is chunked")
    }

    @Test func chunksDoNotSurviveIntoTheFollowingFragment() throws {
        try play("chunks do not survive into the following fragment")
    }
}

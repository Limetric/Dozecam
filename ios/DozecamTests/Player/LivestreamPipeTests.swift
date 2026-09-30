import Foundation
import Testing

@testable import Dozecam

/// Mirrors Android's `LivestreamPipeTest`: bounded, fails rather than drops,
/// drains before the end, and never leaves libVLC's input thread blocked.
struct LivestreamPipeTests {
    /// Reads up to `count` bytes as libVLC's read callback would.
    private func read(_ pipe: LivestreamPipe, _ count: Int) -> (LivestreamPipe.ReadResult, Data) {
        var buffer = [UInt8](repeating: 0, count: count)
        let result = buffer.withUnsafeMutableBytes { pipe.read(into: $0) }
        if case .bytes(let n) = result { return (result, Data(buffer[..<n])) }
        return (result, Data())
    }

    /// Runs `read` on a thread of its own, as libVLC does, so a test can
    /// observe it blocking.
    private func readOnAThread(_ pipe: LivestreamPipe, _ count: Int) async -> LivestreamPipe.ReadResult {
        await withCheckedContinuation { done in
            Thread.detachNewThread {
                var buffer = [UInt8](repeating: 0, count: count)
                done.resume(returning: buffer.withUnsafeMutableBytes { pipe.read(into: $0) })
            }
        }
    }

    @Test func readsSegmentsInOrderAcrossReadBoundaries() {
        let pipe = LivestreamPipe()
        #expect(pipe.offer(Data([1, 2, 3])))
        #expect(pipe.offer(Data([4, 5])))

        #expect(read(pipe, 2) == (.bytes(2), Data([1, 2])))
        // A read never spans segments: the rest of the first comes alone.
        #expect(read(pipe, 10) == (.bytes(1), Data([3])))
        #expect(read(pipe, 10) == (.bytes(2), Data([4, 5])))
    }

    @Test func anEmptyOfferIsAcceptedAndCarriesNothing() {
        let pipe = LivestreamPipe(maxPendingSegments: 1)
        #expect(pipe.offer(Data()))
        #expect(pipe.offer(Data([9])))
        #expect(read(pipe, 4) == (.bytes(1), Data([9])))
    }

    @Test func overflowingFailsTheStreamInsteadOfDroppingASegment() {
        let pipe = LivestreamPipe(maxPendingSegments: 2)
        #expect(pipe.offer(Data([1])))
        #expect(pipe.offer(Data([2])))

        #expect(!pipe.offer(Data([3])))
        #expect(pipe.failureCause as? LivestreamPipe.Failure == .overflow)
        // Everything after is refused, and the reader sees the failure at
        // once rather than a stream with a hole in it.
        #expect(!pipe.offer(Data([4])))
        #expect(read(pipe, 4).0 == .failed)
    }

    @Test func readingMakesRoomAgain() {
        let pipe = LivestreamPipe(maxPendingSegments: 1)
        #expect(pipe.offer(Data([1])))
        #expect(read(pipe, 1).0 == .bytes(1))
        #expect(pipe.offer(Data([2])))
    }

    @Test func aFinishedStreamDrainsItsBacklogThenEnds() {
        let pipe = LivestreamPipe()
        pipe.offer(Data([1, 2]))
        pipe.finish()

        #expect(!pipe.offer(Data([3])))
        #expect(read(pipe, 8) == (.bytes(2), Data([1, 2])))
        #expect(read(pipe, 8).0 == .endOfStream)
        #expect(read(pipe, 8).0 == .endOfStream)
    }

    @Test func aFailureOutranksTheBacklog() {
        let pipe = LivestreamPipe()
        pipe.offer(Data([1, 2]))
        pipe.fail(ProtectLivestreamSocket.Failure.closedByConsole(code: 1008))

        #expect(read(pipe, 8).0 == .failed)
        #expect(pipe.failureCause as? ProtectLivestreamSocket.Failure == .closedByConsole(code: 1008))
    }

    @Test func theFirstFailureIsKept() {
        let pipe = LivestreamPipe()
        pipe.fail(ProtectLivestreamSocket.Failure.unexpectedTextMessage)
        pipe.fail(ProtectLivestreamSocket.Failure.consumerFellBehind)
        #expect(pipe.failureCause as? ProtectLivestreamSocket.Failure == .unexpectedTextMessage)
    }

    @Test func closingDropsTheBacklogAndFailsReads() {
        let pipe = LivestreamPipe()
        pipe.offer(Data([1, 2]))
        pipe.close()

        #expect(read(pipe, 8).0 == .failed)
        #expect(!pipe.offer(Data([3])))
    }

    @Test func anEmptyBufferReadsNothingWithoutBlocking() {
        let pipe = LivestreamPipe()
        #expect(read(pipe, 0).0 == .bytes(0))
    }

    @Test func aBlockedReadWakesForBytes() async {
        let pipe = LivestreamPipe()
        async let result = readOnAThread(pipe, 8)
        try? await Task.sleep(for: .milliseconds(50))
        pipe.offer(Data([7, 7, 7]))
        #expect(await result == .bytes(3))
    }

    @Test func aBlockedReadWakesWhenThePipeIsClosed() async {
        // What stopping a player relies on: libVLC waits for its input
        // thread, which is parked here until the pipe lets it go.
        let pipe = LivestreamPipe()
        async let result = readOnAThread(pipe, 8)
        try? await Task.sleep(for: .milliseconds(50))
        pipe.close()
        #expect(await result == .failed)
    }

    @Test func aBlockedReadWakesAtTheEndOfTheStream() async {
        let pipe = LivestreamPipe()
        async let result = readOnAThread(pipe, 8)
        try? await Task.sleep(for: .milliseconds(50))
        pipe.finish()
        #expect(await result == .endOfStream)
    }

    @Test func manySegmentsSurviveCompaction() {
        let pipe = LivestreamPipe(maxPendingSegments: 300)
        for i in 0..<300 { #expect(pipe.offer(Data([UInt8(i % 256), UInt8(i / 256)]))) }
        for i in 0..<300 {
            #expect(read(pipe, 2).1 == Data([UInt8(i % 256), UInt8(i / 256)]))
            // Keep the queue full while the consumer drains it.
            if i < 150 { #expect(pipe.offer(Data([0xFF, 0xFF]))) }
        }
        for _ in 0..<150 { #expect(read(pipe, 2).1 == Data([0xFF, 0xFF])) }
    }
}

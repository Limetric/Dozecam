import Foundation

/// Hands fMP4 from the WebSocket to libVLC's input thread, which pulls it
/// through the media's read callback. The counterpart of Android's
/// `LivestreamPipe`.
///
/// Bounded on purpose. A live camera has no back pressure (the console keeps
/// sending whether or not anything drains), so an unbounded buffer would
/// answer a stalled decoder by growing until the process dies. Overflowing
/// instead fails the stream, which the watchdog sees and reconnects from.
///
/// Thread-safe: one producer (the socket's feeding task) and one consumer
/// (libVLC's input thread, blocking in `read`), plus `close` from anywhere.
final class LivestreamPipe: @unchecked Sendable {
    /// What a `read` produced.
    enum ReadResult: Equatable, Sendable {
        /// This many bytes were copied: at least one, unless the buffer was empty.
        case bytes(Int)
        /// The producer finished and everything it sent has been read.
        case endOfStream
        /// The stream failed, or the consumer closed the pipe.
        case failed
    }

    /// Why a pipe stopped accepting bytes.
    enum Failure: Error, Equatable, Sendable {
        /// The consumer fell more than `maxPendingSegments` behind.
        case overflow
    }

    /// ~25 s of 100 ms fragments: a real stall, not a passing hiccup.
    static let defaultMaxPendingSegments = 256

    let maxPendingSegments: Int

    private let condition = NSCondition()
    // Guarded by `condition`.
    private var queue: [Data] = []
    private var head = 0
    private var position = 0
    private var failure: (any Error)?
    private var finished = false
    private var closed = false

    init(maxPendingSegments: Int = LivestreamPipe.defaultMaxPendingSegments) {
        precondition(maxPendingSegments > 0)
        self.maxPendingSegments = maxPendingSegments
    }

    /// Producer side. Returns false, and fails the pipe, when the consumer
    /// has fallen too far behind; a pipe already ended drops `bytes` and
    /// returns false too.
    @discardableResult
    func offer(_ bytes: Data) -> Bool {
        condition.withLock {
            guard !finished, !closed else { return false }
            if bytes.isEmpty { return true }
            guard queue.count - head < maxPendingSegments else {
                // Dropping a segment would corrupt the fMP4 for good; ending
                // the stream lets a reconnect start clean.
                failure = failure ?? Failure.overflow
                finished = true
                condition.broadcast()
                return false
            }
            queue.append(bytes)
            condition.broadcast()
            return true
        }
    }

    /// Producer side: the stream ended for a reason the consumer should see.
    /// A failure outranks the backlog: the next read fails.
    func fail(_ cause: any Error) {
        condition.withLock {
            if failure == nil { failure = cause }
            finished = true
            condition.broadcast()
        }
    }

    /// Producer side: the stream ended cleanly. Reads drain the backlog, then
    /// see the end.
    func finish() {
        condition.withLock {
            finished = true
            condition.broadcast()
        }
    }

    /// Either side: nobody wants the stream any more. Wakes a blocked read,
    /// which fails, drops the backlog, and refuses further bytes.
    func close() {
        condition.withLock {
            closed = true
            finished = true
            queue.removeAll()
            head = 0
            position = 0
            condition.broadcast()
        }
    }

    /// The failure the stream ended with, if it failed.
    var failureCause: (any Error)? { condition.withLock { failure } }

    /// Consumer side. Blocks until bytes arrive, the stream ends, or it fails
    /// or is closed; copies at most `buffer.count` bytes.
    func read(into buffer: UnsafeMutableRawBufferPointer) -> ReadResult {
        guard buffer.count > 0 else { return .bytes(0) }
        return condition.withLock {
            while true {
                if closed || failure != nil { return .failed }
                if head < queue.count { break }
                if finished { return .endOfStream }
                condition.wait()
            }
            let current = queue[head]
            let count = min(buffer.count, current.count - position)
            current.withUnsafeBytes { source in
                let start = source.baseAddress!.advanced(by: position)
                buffer.baseAddress!.copyMemory(from: start, byteCount: count)
            }
            position += count
            if position == current.count {
                position = 0
                head += 1
                // Compact now and then rather than shifting on every segment.
                if head >= 64, head * 2 >= queue.count {
                    queue.removeFirst(head)
                    head = 0
                }
            }
            return .bytes(count)
        }
    }
}

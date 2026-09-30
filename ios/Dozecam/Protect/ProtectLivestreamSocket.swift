import Foundation

/// One livestream WebSocket, handing on its binary messages as they arrive.
///
/// The messages are raw: Protect's frame protocol does not respect message
/// boundaries, so decoding them (`LivestreamDecoder`) happens downstream,
/// where bytes not yet decodable can be carried into the next message.
///
/// The socket is single-use. Its authorization token is minted per
/// negotiation, so recovering from a drop means negotiating a fresh URL
/// through `ProtectLivestreamProvider`, never reopening this one.
struct ProtectLivestreamSocket: Sendable {
    /// Why a socket ended other than by its consumer walking away.
    enum Failure: Error, Equatable, Sendable {
        /// The console sent a close frame.
        case closedByConsole(code: Int)
        /// A text message: the livestream is binary only, so the stream is
        /// not what it claims to be.
        case unexpectedTextMessage
        /// The consumer stopped reading and the buffer filled. Dropping
        /// messages would corrupt the stream, so it ends instead.
        case consumerFellBehind
    }

    let urlSession: URLSession
    /// A live socket is idle between fragments; pings keep the path, and any
    /// idle timeout on the injected session, from tearing it down mid-stream.
    let pingInterval: Duration
    /// Messages held for a consumer that has not caught up yet.
    let bufferLimit: Int

    init(urlSession: URLSession, pingInterval: Duration = .seconds(10), bufferLimit: Int = 512) {
        self.urlSession = urlSession
        self.pingInterval = pingInterval
        self.bufferLimit = bufferLimit
    }

    /// Opens the socket and yields its binary messages. The stream finishes
    /// by throwing when the socket fails or the console closes it
    /// (`Failure`, or `ProtectAPIError.unreachable` for a transport error);
    /// it never finishes cleanly on its own, because a livestream has no
    /// end. Cancelling the consuming task, or dropping the stream, closes
    /// the socket.
    func open(_ url: URL) -> AsyncThrowingStream<Data, any Error> {
        let (stream, continuation) = AsyncThrowingStream<Data, any Error>.makeStream(
            bufferingPolicy: .bufferingOldest(bufferLimit)
        )
        let task = urlSession.webSocketTask(with: url)
        let receiving = Task {
            do {
                while true {
                    switch try await task.receive() {
                    case .data(let data):
                        if case .dropped = continuation.yield(data) { throw Failure.consumerFellBehind }
                    case .string:
                        throw Failure.unexpectedTextMessage
                    @unknown default:
                        throw Failure.unexpectedTextMessage
                    }
                }
            } catch {
                let failure = Self.failure(error, task: task)
                task.cancel(with: .goingAway, reason: nil)
                continuation.finish(throwing: failure)
            }
        }
        let interval = pingInterval
        let pinging = Task {
            while !Task.isCancelled {
                try? await Task.sleep(for: interval)
                // A missed pong is not acted on here: a dead path also fails
                // the pending receive, which ends the stream.
                task.sendPing { _ in }
            }
        }
        continuation.onTermination = { _ in
            receiving.cancel()
            pinging.cancel()
            task.cancel(with: .normalClosure, reason: nil)
        }
        task.resume()
        return stream
    }

    private static func failure(_ error: any Error, task: URLSessionWebSocketTask) -> any Error {
        if error is Failure { return error }
        if task.closeCode != .invalid { return Failure.closedByConsole(code: task.closeCode.rawValue) }
        if let error = error as? URLError { return ProtectAPIError.unreachable(error) }
        return error
    }
}

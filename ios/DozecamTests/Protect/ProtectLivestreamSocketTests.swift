import Foundation
import Network
import Testing

@testable import Dozecam

/// Runs the socket against a real WebSocket server on the loopback
/// interface: `URLProtocol` cannot stand in for a WebSocket task.
struct ProtectLivestreamSocketTests {
    @Test func yieldsBinaryMessagesRawAndInOrderThenFailsWhenTheConsoleCloses() async throws {
        let messages = [Data([0x01, 0x00, 0x00, 0x02]), Data([0xAB, 0xCD]), Data(repeating: 0x7F, count: 70_000)]
        let server = try await LoopbackWebSocketServer.start { connection in
            for message in messages { await connection.send(binary: message) }
            await connection.close(code: .protocolCode(.policyViolation))
        }
        defer { server.stop() }

        let stream = ProtectLivestreamSocket(urlSession: URLSession(configuration: .ephemeral)).open(server.url)
        var received: [Data] = []
        await #expect {
            for try await message in stream { received.append(message) }
        } throws: { error in
            error as? ProtectLivestreamSocket.Failure == .closedByConsole(code: 1008)
        }

        #expect(received == messages)
    }

    @Test func aTextMessageEndsTheStream() async throws {
        let server = try await LoopbackWebSocketServer.start { connection in
            await connection.send(text: "hello")
        }
        defer { server.stop() }

        let stream = ProtectLivestreamSocket(urlSession: URLSession(configuration: .ephemeral)).open(server.url)
        await #expect {
            for try await _ in stream {}
        } throws: { error in
            error as? ProtectLivestreamSocket.Failure == .unexpectedTextMessage
        }
    }

    @Test func anUnreachableEndpointFailsAsUnreachable() async throws {
        // Port 9 (discard) on loopback: nothing listens there.
        let stream = ProtectLivestreamSocket(urlSession: URLSession(configuration: .ephemeral))
            .open(URL(string: "ws://127.0.0.1:9/ws")!)
        await #expect {
            for try await _ in stream {}
        } throws: { error in
            guard case .unreachable = error as? ProtectAPIError else { return false }
            return true
        }
    }
}

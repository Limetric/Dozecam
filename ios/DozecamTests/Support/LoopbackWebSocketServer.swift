import Foundation
import Network

/// A one-connection WebSocket server on 127.0.0.1, built on Network.
final class LoopbackWebSocketServer: Sendable {
    struct Connection: Sendable {
        let connection: NWConnection

        func send(binary data: Data) async {
            await send(data, opcode: .binary)
        }

        func send(text: String) async {
            await send(Data(text.utf8), opcode: .text)
        }

        func close(code: NWProtocolWebSocket.CloseCode) async {
            let metadata = NWProtocolWebSocket.Metadata(opcode: .close)
            metadata.closeCode = code
            await send(Data(), metadata: metadata)
        }

        private func send(_ data: Data, opcode: NWProtocolWebSocket.Opcode) async {
            await send(data, metadata: NWProtocolWebSocket.Metadata(opcode: opcode))
        }

        private func send(_ data: Data, metadata: NWProtocolWebSocket.Metadata) async {
            let context = NWConnection.ContentContext(identifier: "message", metadata: [metadata])
            await withCheckedContinuation { (done: CheckedContinuation<Void, Never>) in
                connection.send(
                    content: data,
                    contentContext: context,
                    isComplete: true,
                    completion: .contentProcessed { _ in done.resume() }
                )
            }
        }
    }

    let listener: NWListener
    let url: URL

    private init(listener: NWListener, port: UInt16) {
        self.listener = listener
        url = URL(string: "ws://127.0.0.1:\(port)/ws/livestream")!
    }

    static func start(
        serve: @escaping @Sendable (Connection) async -> Void
    ) async throws -> LoopbackWebSocketServer {
        let parameters = NWParameters.tcp
        let options = NWProtocolWebSocket.Options()
        options.autoReplyPing = true
        parameters.defaultProtocolStack.applicationProtocols.insert(options, at: 0)
        parameters.requiredLocalEndpoint = NWEndpoint.hostPort(host: "127.0.0.1", port: .any)
        let listener = try NWListener(using: parameters)
        let queue = DispatchQueue(label: "LoopbackWebSocketServer")
        listener.newConnectionHandler = { connection in
            connection.stateUpdateHandler = { state in
                if case .ready = state {
                    Task { await serve(Connection(connection: connection)) }
                }
            }
            connection.start(queue: queue)
        }
        let port: UInt16 = try await withCheckedThrowingContinuation { ready in
            listener.stateUpdateHandler = { state in
                switch state {
                case .ready:
                    listener.stateUpdateHandler = nil
                    ready.resume(returning: listener.port?.rawValue ?? 0)
                case .failed(let error):
                    listener.stateUpdateHandler = nil
                    ready.resume(throwing: error)
                default:
                    break
                }
            }
            listener.start(queue: queue)
        }
        return LoopbackWebSocketServer(listener: listener, port: port)
    }

    func stop() {
        listener.cancel()
    }
}

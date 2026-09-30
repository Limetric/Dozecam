import Foundation
import Network
import Synchronization

/// Whether Dozecam may reach the LAN, as far as it can know.
///
/// iOS has no API that answers this. It shows its prompt the first time the
/// app connects to a LAN address, and afterwards reports a refusal only as the
/// state of a connection: `NWPath.unsatisfiedReason == .localNetworkDenied`.
/// So the status is learned from connections, and `.unknown` is honest.
enum LocalNetworkAccessStatus: String, Sendable {
    /// Never determined: no LAN connection has told either way yet.
    case unknown
    /// A LAN host answered, so traffic got through.
    case granted
    /// A connection was held back with `localNetworkDenied`.
    case denied
}

/// What one connection to a LAN host says about local-network access.
enum LocalNetworkEvidence: Equatable, Sendable {
    /// Traffic reached the host: the connection is ready, or the host refused
    /// it, which it could only do if packets got through.
    case reachable
    /// The system held the connection back for local-network privacy.
    case denied
    /// Nothing either way yet (preparing, no network, a timeout, an error that
    /// says nothing about the grant).
    case inconclusive
}

extension LocalNetworkEvidence {
    /// Reads an `NWConnection` state together with its current path.
    init(state: NWConnection.State, unsatisfiedReason: NWPath.UnsatisfiedReason?) {
        if unsatisfiedReason == .localNetworkDenied {
            self = .denied
            return
        }
        switch state {
        case .ready:
            self = .reachable
        case .waiting(let error), .failed(let error):
            self = Self.evidence(from: error)
        default:
            self = .inconclusive
        }
    }

    private static func evidence(from error: NWError) -> LocalNetworkEvidence {
        switch error {
        case .posix(.ECONNREFUSED):
            return .reachable
        // kDNSServiceErr_PolicyDenied: how a denial shows when the host is a
        // name resolved on the LAN (mDNS) rather than an address.
        case .dns(let code) where code == -65_570:
            return .denied
        default:
            return .inconclusive
        }
    }
}

/// Opens a probe connection and reports what it learns; `NWConnection` in the
/// app, a script in tests.
protocol LocalNetworkProbe: Sendable {
    /// Evidence from a connection to `host:port`, as it arrives. The
    /// connection is cancelled when the stream ends or its reader stops.
    func connect(host: String, port: UInt16) -> AsyncStream<LocalNetworkEvidence>
}

/// A TCP `NWConnection` as the probe. Its handlers run on the connection's
/// own queue and are created here, outside any actor, so they never inherit
/// MainActor isolation (ios/AGENTS.md, #58).
struct SystemLocalNetworkProbe: LocalNetworkProbe {
    func connect(host: String, port: UInt16) -> AsyncStream<LocalNetworkEvidence> {
        let (stream, continuation) = AsyncStream.makeStream(of: LocalNetworkEvidence.self)
        guard let port = NWEndpoint.Port(rawValue: port) else {
            continuation.finish()
            return stream
        }
        let connection = NWConnection(host: NWEndpoint.Host(host), port: port, using: .tcp)
        let queue = DispatchQueue(label: "app.dozecam.local-network-probe")
        connection.stateUpdateHandler = { [weak connection] state in
            let reason = connection?.currentPath?.unsatisfiedReason
            continuation.yield(LocalNetworkEvidence(state: state, unsatisfiedReason: reason))
            switch state {
            case .failed, .cancelled: continuation.finish()
            default: break
            }
        }
        // A waiting connection is re-evaluated when its path changes; the path
        // is also where a denial arriving after the prompt shows up.
        connection.pathUpdateHandler = { [weak connection] path in
            guard let connection else { return }
            continuation.yield(LocalNetworkEvidence(state: connection.state, unsatisfiedReason: path.unsatisfiedReason))
        }
        continuation.onTermination = { _ in connection.cancel() }
        connection.start(queue: queue)
        return stream
    }
}

/// Local-network access: the counterpart of Android's `LocalNetworkPermission`
/// and `LocalNetworkPermissionRequest`, and the one status that onboarding,
/// the monitor's "Not monitoring" badge and the night checklist all read.
///
/// The rules:
///
/// - **Asking is deliberate.** `requestAccess(probing:port:)` connects to the
///   console, which is what makes iOS show its prompt the first time. Only
///   onboarding calls it, at a moment it chooses. Nothing else connects just
///   to find out.
/// - **Evidence from any LAN connection counts.** A connection that reaches a
///   LAN host, or is held back as `localNetworkDenied`, can `record` what it
///   saw; that is how a grant withdrawn in Settings shows up in the monitor.
/// - **Remembered across launches.** A determined status is stored, since
///   nothing else could say it again without connecting.
/// - **Re-checked on every return to the foreground**, with `refresh`, since
///   the grant can be switched either way in Settings while Dozecam is away.
///   Only once determined: iOS prompts once per install, so a probe after a
///   decision can never put the prompt on screen, while one before it would
///   be exactly the side effect the first rule forbids.
/// - **Inconclusive evidence changes nothing.** No network, a console that is
///   down, a timeout: none of them says anything about the grant.
final class LocalNetworkAccess: Sendable {
    static let statusKey = "local_network_access"

    private let probe: any LocalNetworkProbe
    /// UserDefaults is documented as thread-safe but not marked Sendable.
    nonisolated(unsafe) private let defaults: UserDefaults
    private let changes: Broadcast<LocalNetworkAccessStatus>
    /// Keeps what is stored and what is published in one order when
    /// connections on different queues record at once.
    private let recording = Mutex(())

    init(probe: any LocalNetworkProbe = SystemLocalNetworkProbe(), defaults: UserDefaults = .standard) {
        self.probe = probe
        self.defaults = defaults
        let stored = defaults.string(forKey: Self.statusKey).flatMap(LocalNetworkAccessStatus.init(rawValue:))
        changes = Broadcast(stored ?? .unknown)
    }

    var status: LocalNetworkAccessStatus { changes.value }

    /// `status` now, then on every change.
    func statusUpdates() -> AsyncStream<LocalNetworkAccessStatus> { changes.stream() }

    /// Records what a LAN connection saw. Safe from any thread, including a
    /// connection's own queue.
    func record(_ evidence: LocalNetworkEvidence) {
        let next: LocalNetworkAccessStatus
        switch evidence {
        case .reachable: next = .granted
        case .denied: next = .denied
        case .inconclusive: return
        }
        recording.withLock { _ in
            defaults.set(next.rawValue, forKey: Self.statusKey)
            changes.sendIfChanged(next)
        }
    }

    /// Connects to the console at `host:port`, which puts the iOS prompt on
    /// screen if the user has not answered it yet, and returns the status
    /// once the connection has settled it or `timeout` has passed.
    ///
    /// Returns as soon as the console answers. A denial does not end the
    /// probe early: while the prompt is up the connection can already read as
    /// denied, and an Allow tapped afterwards lets it through. The status
    /// stream carries every step, so a caller showing it need not wait for
    /// the return.
    @discardableResult
    func requestAccess(probing host: String, port: UInt16, timeout: Duration = .seconds(30)) async
        -> LocalNetworkAccessStatus
    {
        await run(host: host, port: port, timeout: timeout)
        return status
    }

    /// Re-checks a determined status against `host:port`; for a return to the
    /// foreground. Does nothing while the status is unknown (see the rules
    /// above).
    @discardableResult
    func refresh(probing host: String, port: UInt16, timeout: Duration = .seconds(10)) async
        -> LocalNetworkAccessStatus
    {
        guard status != .unknown else { return .unknown }
        await run(host: host, port: port, timeout: timeout)
        return status
    }

    private func run(host: String, port: UInt16, timeout: Duration) async {
        let evidence = probe.connect(host: host, port: port)
        await withTaskGroup(of: Void.self) { group in
            group.addTask { [self] in
                for await item in evidence {
                    record(item)
                    if item == .reachable { return }
                }
            }
            group.addTask {
                try? await Task.sleep(for: timeout)
            }
            await group.next()
            group.cancelAll()
        }
    }
}

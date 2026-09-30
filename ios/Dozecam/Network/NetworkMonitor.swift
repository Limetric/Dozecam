import Network
import Synchronization

/// Where the device's network stands in relation to the cameras, the
/// counterpart of Android's `NetworkReach`.
///
/// Dozecam is LAN-only: the console and its cameras live on the house's own
/// network. So "has a network" and "can reach the cameras" are different
/// questions, and a phone on mobile data answers yes to the first and no to
/// the second.
enum NetworkReach: Sendable {
    /// No usable network at all.
    case offline
    /// A network that could carry LAN traffic: Wi-Fi, Ethernet, a tunnel home.
    case local
    /// Online, but by a route that cannot reach the console: mobile data.
    case mobileData
}

/// What `NWPath` says, reduced to what Dozecam reads from it; the seam that
/// lets tests describe a path without the system.
struct NetworkPathSnapshot: Equatable, Sendable {
    enum Status: Sendable {
        case satisfied
        case unsatisfied
        /// Usable once a connection asks for it (VPN on demand, say).
        case requiresConnection
    }

    enum Interface: Sendable {
        case wifi
        case wiredEthernet
        case cellular
        case loopback
        /// Anything else, which includes VPN tunnels (`utun`).
        case other
    }

    var status: Status
    /// The kinds of interface the path uses.
    var interfaces: Set<Interface>
    /// Which network this is, as far as the path can tell: its interface names
    /// and gateways. A change here with `reach` unchanged is a handover, from
    /// one Wi-Fi network to another say.
    var interfaceNames: [String] = []
    var gateways: [String] = []

    /// Android's `NetworkMonitor.reachOf`/`reachesLocalNetwork`: asked as a
    /// question about mobile data rather than about Wi-Fi. Wi-Fi is the usual
    /// answer, but a docked iPad on Ethernet reaches the console too, and so
    /// does a phone tunnelled home (a VPN shows as `.other`). Anything
    /// unrecognised gets the benefit of the doubt: a warning that fires at a
    /// viewer streaming perfectly well is one the user learns to ignore.
    var reach: NetworkReach {
        // A path that needs a connection to come up is still a network: read
        // as offline, every camera would stop retrying on a phone whose VPN
        // connects on demand.
        guard status != .unsatisfied else { return .offline }
        let local: Set<Interface> = [.wifi, .wiredEthernet, .other]
        if !interfaces.isDisjoint(with: local) || !interfaces.contains(.cellular) {
            return .local
        }
        return .mobileData
    }
}

extension NetworkPathSnapshot {
    init(_ path: NWPath) {
        let status: Status =
            switch path.status {
            case .satisfied: .satisfied
            case .requiresConnection: .requiresConnection
            case .unsatisfied: .unsatisfied
            @unknown default: .unsatisfied
            }
        let kinds: [(NWInterface.InterfaceType, Interface)] = [
            (.wifi, .wifi), (.wiredEthernet, .wiredEthernet), (.cellular, .cellular),
            (.loopback, .loopback), (.other, .other),
        ]
        self.init(
            status: status,
            interfaces: Set(kinds.filter { path.usesInterfaceType($0.0) }.map(\.1)),
            interfaceNames: path.availableInterfaces.map(\.name),
            gateways: path.gateways.map { "\($0)" }
        )
    }
}

/// Delivers path snapshots; `NWPathMonitor` in the app, a script in tests.
protocol NetworkPathSource: Sendable {
    /// Starts delivering; `onUpdate` may be called on any thread.
    func start(onUpdate: @escaping @Sendable (NetworkPathSnapshot) -> Void)
    func cancel()
}

/// `NWPathMonitor` behind `NetworkPathSource`. Its handler runs on the
/// monitor's own queue, and is created here, outside any actor, so it never
/// inherits MainActor isolation (ios/AGENTS.md, #58).
final class SystemNetworkPathSource: NetworkPathSource {
    private let monitor = NWPathMonitor()
    private let queue = DispatchQueue(label: "app.dozecam.network-monitor")

    func start(onUpdate: @escaping @Sendable (NetworkPathSnapshot) -> Void) {
        monitor.pathUpdateHandler = { path in onUpdate(NetworkPathSnapshot(path)) }
        monitor.start(queue: queue)
    }

    func cancel() {
        monitor.cancel()
    }
}

/// How the device's network stands, and every change to it: the counterpart
/// of Android's `NetworkMonitor`, exposing the same three views.
///
/// Until the first path arrives (the system delivers one right after start)
/// it reads `.local`, as Android reads a network whose capabilities have not
/// arrived yet: guessing the other way would flash the "no network" warning,
/// and take every camera offline, at every launch.
final class NetworkMonitor: Sendable {
    private let source: any NetworkPathSource
    private let reachChanges = Broadcast(NetworkReach.local)
    private let onlineChanges = Broadcast(true)
    private let networkEvents = Broadcast(())
    private let lastPath = Mutex<NetworkPathSnapshot?>(nil)

    init(source: any NetworkPathSource = SystemNetworkPathSource()) {
        self.source = source
        source.start { [weak self] path in self?.receive(path) }
    }

    deinit {
        source.cancel()
    }

    /// Android's `reach`: whether the network could carry LAN traffic.
    var reach: NetworkReach { reachChanges.value }

    /// Android's `isOnline`: whether there is a network at all. What a camera
    /// session reads to go offline (no retries) and reconnect when it returns,
    /// and what the failure ledger reads to say "no network" rather than
    /// blaming the camera (shared/spec/connection-state.md, failure-alerts.md).
    var isOnline: Bool { onlineChanges.value }

    /// `reach` now, then on every change.
    func reachUpdates() -> AsyncStream<NetworkReach> { reachChanges.stream() }

    /// `isOnline` now, then on every change.
    func onlineUpdates() -> AsyncStream<Bool> { onlineChanges.stream() }

    /// Android's `defaultNetworkChanges`: once for every network the device
    /// settles on, including a replacement that leaves `reach` where it was
    /// (one Wi-Fi network for another). For anything holding knowledge about a
    /// particular network, such as whether a given camera answers. No replay:
    /// it is an event, not a state.
    func networkChanges() -> AsyncStream<Void> { networkEvents.stream(replayingCurrent: false) }

    private func receive(_ path: NetworkPathSnapshot) {
        let previous = lastPath.withLock { last in
            defer { last = path }
            return last
        }
        reachChanges.sendIfChanged(path.reach)
        onlineChanges.sendIfChanged(path.reach != .offline)
        if previous.map({ !Self.sameNetwork($0, path) }) ?? true {
            networkEvents.send(())
        }
    }

    private static func sameNetwork(_ a: NetworkPathSnapshot, _ b: NetworkPathSnapshot) -> Bool {
        a.status == b.status && a.interfaceNames == b.interfaceNames && a.gateways == b.gateways
    }
}

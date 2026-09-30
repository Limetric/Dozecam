import Synchronization
import Testing

@testable import Dozecam

/// A path source the test drives by hand.
private final class ScriptedPathSource: NetworkPathSource {
    private let handler = Mutex<(@Sendable (NetworkPathSnapshot) -> Void)?>(nil)
    private let cancelled = Mutex(false)

    func start(onUpdate: @escaping @Sendable (NetworkPathSnapshot) -> Void) {
        handler.withLock { $0 = onUpdate }
    }

    func cancel() {
        cancelled.withLock { $0 = true }
    }

    var isCancelled: Bool { cancelled.withLock { $0 } }

    func deliver(_ path: NetworkPathSnapshot) {
        handler.withLock { $0 }?(path)
    }
}

struct NetworkMonitorTests {
    typealias Path = NetworkPathSnapshot

    static let wifi = Path(status: .satisfied, interfaces: [.wifi], interfaceNames: ["en0"], gateways: ["192.168.1.1"])
    static let otherWifi = Path(
        status: .satisfied, interfaces: [.wifi], interfaceNames: ["en0"], gateways: ["10.0.0.1"])
    static let cellular = Path(status: .satisfied, interfaces: [.cellular], interfaceNames: ["pdp_ip0"])
    static let offline = Path(status: .unsatisfied, interfaces: [])

    /// Android's `NetworkMonitor.reachesLocalNetwork`: Wi-Fi, Ethernet and a
    /// tunnel home reach the LAN; mobile data alone does not; anything
    /// unrecognised gets the benefit of the doubt.
    @Test(arguments: [
        (Path(status: .satisfied, interfaces: [.wifi]), NetworkReach.local),
        (Path(status: .satisfied, interfaces: [.wiredEthernet]), .local),
        (Path(status: .satisfied, interfaces: [.other, .cellular]), .local),  // VPN over mobile data
        (Path(status: .satisfied, interfaces: [.wifi, .cellular]), .local),
        (Path(status: .satisfied, interfaces: [.cellular]), .mobileData),
        (Path(status: .satisfied, interfaces: []), .local),
        (Path(status: .satisfied, interfaces: [.loopback]), .local),
        (Path(status: .requiresConnection, interfaces: [.other]), .local),
        (Path(status: .requiresConnection, interfaces: [.cellular]), .mobileData),
        (Path(status: .unsatisfied, interfaces: [.wifi]), .offline),
        (Path(status: .unsatisfied, interfaces: []), .offline),
    ])
    func reachOfAPath(path: Path, reach: NetworkReach) {
        #expect(path.reach == reach)
    }

    @Test func beforeTheFirstPathItReadsLocalAndOnline() {
        let monitor = NetworkMonitor(source: ScriptedPathSource())
        #expect(monitor.reach == .local)
        #expect(monitor.isOnline)
    }

    @Test func followsThePath() {
        let source = ScriptedPathSource()
        let monitor = NetworkMonitor(source: source)
        source.deliver(Self.cellular)
        #expect(monitor.reach == .mobileData)
        #expect(monitor.isOnline)
        source.deliver(Self.offline)
        #expect(monitor.reach == .offline)
        #expect(!monitor.isOnline)
    }

    @Test func reachUpdatesAreDistinct() async {
        let source = ScriptedPathSource()
        let monitor = NetworkMonitor(source: source)
        var updates = monitor.reachUpdates().makeAsyncIterator()
        #expect(await updates.next() == .local)
        source.deliver(Self.wifi)  // still local: nothing sent
        source.deliver(Self.cellular)
        #expect(await updates.next() == .mobileData)
        source.deliver(Self.offline)
        #expect(await updates.next() == .offline)
    }

    @Test func onlineUpdatesFollowWhetherThereIsANetworkAtAll() async {
        let source = ScriptedPathSource()
        let monitor = NetworkMonitor(source: source)
        var updates = monitor.onlineUpdates().makeAsyncIterator()
        #expect(await updates.next() == true)
        source.deliver(Self.cellular)  // mobile data is still a network
        source.deliver(Self.offline)
        #expect(await updates.next() == false)
        source.deliver(Self.wifi)
        #expect(await updates.next() == true)
    }

    /// Android's `defaultNetworkChanges`: a new network is news even when
    /// `reach` reads the same, and the same network again is not.
    @Test func networkChangesFireForANewNetworkEvenWhenReachIsUnchanged() async {
        let source = ScriptedPathSource()
        let monitor = NetworkMonitor(source: source)
        source.deliver(Self.wifi)
        let changes = monitor.networkChanges()
        let received = Mutex(0)
        let listener = Task {
            for await _ in changes { received.withLock { $0 += 1 } }
        }
        source.deliver(Self.wifi)
        try? await Task.sleep(for: .milliseconds(100))
        #expect(received.withLock { $0 } == 0)
        source.deliver(Self.otherWifi)
        try? await Task.sleep(for: .milliseconds(100))
        #expect(received.withLock { $0 } == 1)
        #expect(monitor.reach == .local)
        listener.cancel()
    }

    @Test func releasingTheMonitorCancelsTheSource() {
        let source = ScriptedPathSource()
        var monitor: NetworkMonitor? = NetworkMonitor(source: source)
        _ = monitor
        monitor = nil
        #expect(source.isCancelled)
    }
}

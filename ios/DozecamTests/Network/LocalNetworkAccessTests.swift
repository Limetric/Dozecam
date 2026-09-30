import Foundation
import Network
import Synchronization
import Testing

@testable import Dozecam

/// A probe that plays back scripted evidence, optionally leaving the
/// connection open afterwards (a console that never answers).
private final class ScriptedProbe: LocalNetworkProbe {
    private let script: [LocalNetworkEvidence]
    private let staysOpen: Bool
    private let connects = Mutex<[String]>([])
    private let terminations = Mutex(0)

    init(_ script: [LocalNetworkEvidence], staysOpen: Bool = true) {
        self.script = script
        self.staysOpen = staysOpen
    }

    var connections: [String] { connects.withLock { $0 } }
    var closed: Int { terminations.withLock { $0 } }

    func connect(host: String, port: UInt16) -> AsyncStream<LocalNetworkEvidence> {
        connects.withLock { $0.append("\(host):\(port)") }
        let (stream, continuation) = AsyncStream.makeStream(of: LocalNetworkEvidence.self)
        continuation.onTermination = { [self] _ in terminations.withLock { $0 += 1 } }
        for evidence in script { continuation.yield(evidence) }
        if !staysOpen { continuation.finish() }
        return stream
    }
}

struct LocalNetworkAccessTests {
    let storage = TestDefaults()
    static let short = Duration.milliseconds(200)

    @Test func startsUnknown() {
        let access = LocalNetworkAccess(probe: ScriptedProbe([]), defaults: storage.defaults)
        #expect(access.status == .unknown)
    }

    @Test func aConsoleThatAnswersGrantsAccess() async {
        let probe = ScriptedProbe([.inconclusive, .reachable])
        let access = LocalNetworkAccess(probe: probe, defaults: storage.defaults)
        let clock = ContinuousClock()
        let started = clock.now
        let status = await access.requestAccess(probing: "10.0.0.1", port: 443, timeout: .seconds(30))
        #expect(status == .granted)
        #expect(access.status == .granted)
        #expect(probe.connections == ["10.0.0.1:443"])
        // Returns on the answer, not at the timeout, and closes the connection.
        #expect(clock.now - started < .seconds(5))
        #expect(probe.closed == 1)
    }

    @Test func aDenialIsReported() async {
        let access = LocalNetworkAccess(probe: ScriptedProbe([.denied]), defaults: storage.defaults)
        let status = await access.requestAccess(probing: "10.0.0.1", port: 443, timeout: Self.short)
        #expect(status == .denied)
    }

    /// While the prompt is up the connection can read as denied; Allow lets it
    /// through, and the probe is still listening.
    @Test func allowAfterAnEarlyDenialGrants() async {
        let access = LocalNetworkAccess(
            probe: ScriptedProbe([.inconclusive, .denied, .reachable]), defaults: storage.defaults)
        var updates = access.statusUpdates().makeAsyncIterator()
        #expect(await updates.next() == .unknown)
        let status = await access.requestAccess(probing: "10.0.0.1", port: 443, timeout: .seconds(30))
        #expect(status == .granted)
        #expect(await updates.next() == .granted)
    }

    @Test func nothingConclusiveLeavesItUnknown() async {
        let probe = ScriptedProbe([.inconclusive, .inconclusive])
        let access = LocalNetworkAccess(probe: probe, defaults: storage.defaults)
        let status = await access.requestAccess(probing: "10.0.0.1", port: 443, timeout: Self.short)
        #expect(status == .unknown)
        #expect(storage.defaults.object(forKey: LocalNetworkAccess.statusKey) == nil)
        #expect(probe.closed == 1)
    }

    @Test func aDeterminedStatusIsRememberedAcrossLaunches() async {
        let first = LocalNetworkAccess(probe: ScriptedProbe([.denied]), defaults: storage.defaults)
        await first.requestAccess(probing: "10.0.0.1", port: 443, timeout: Self.short)
        let relaunched = LocalNetworkAccess(probe: ScriptedProbe([]), defaults: storage.defaults)
        #expect(relaunched.status == .denied)
    }

    /// Refreshing is safe only once iOS has asked: before that a probe would
    /// put the prompt up as a side effect.
    @Test func refreshNeverProbesWhileUnknown() async {
        let probe = ScriptedProbe([.reachable])
        let access = LocalNetworkAccess(probe: probe, defaults: storage.defaults)
        let status = await access.refresh(probing: "10.0.0.1", port: 443, timeout: Self.short)
        #expect(status == .unknown)
        #expect(probe.connections.isEmpty)
    }

    @Test func refreshPicksUpAGrantFromSettings() async {
        storage.defaults.set("denied", forKey: LocalNetworkAccess.statusKey)
        let access = LocalNetworkAccess(probe: ScriptedProbe([.reachable]), defaults: storage.defaults)
        #expect(access.status == .denied)
        #expect(await access.refresh(probing: "10.0.0.1", port: 443) == .granted)
        #expect(LocalNetworkAccess(probe: ScriptedProbe([]), defaults: storage.defaults).status == .granted)
    }

    @Test func refreshPicksUpAWithdrawalFromSettings() async {
        storage.defaults.set("granted", forKey: LocalNetworkAccess.statusKey)
        let access = LocalNetworkAccess(probe: ScriptedProbe([.denied]), defaults: storage.defaults)
        #expect(await access.refresh(probing: "10.0.0.1", port: 443, timeout: Self.short) == .denied)
    }

    /// A console that is down, or no network at all, says nothing about the
    /// grant.
    @Test func anInconclusiveRefreshKeepsTheStatus() async {
        storage.defaults.set("granted", forKey: LocalNetworkAccess.statusKey)
        let access = LocalNetworkAccess(probe: ScriptedProbe([.inconclusive]), defaults: storage.defaults)
        #expect(await access.refresh(probing: "10.0.0.1", port: 443, timeout: Self.short) == .granted)
    }

    @Test func evidenceFromAnyConnectionCounts() {
        let access = LocalNetworkAccess(probe: ScriptedProbe([]), defaults: storage.defaults)
        access.record(.inconclusive)
        #expect(access.status == .unknown)
        access.record(.denied)
        #expect(access.status == .denied)
        access.record(.reachable)
        #expect(access.status == .granted)
        #expect(storage.defaults.string(forKey: LocalNetworkAccess.statusKey) == "granted")
    }

    @Test func aProbeThatEndsEarlyEndsTheRequest() async {
        let probe = ScriptedProbe([.inconclusive], staysOpen: false)
        let access = LocalNetworkAccess(probe: probe, defaults: storage.defaults)
        let clock = ContinuousClock()
        let started = clock.now
        #expect(await access.requestAccess(probing: "10.0.0.1", port: 443, timeout: .seconds(30)) == .unknown)
        #expect(clock.now - started < .seconds(5))
    }

    @Test func anUnknownStoredValueReadsAsUnknown() {
        storage.defaults.set("maybe", forKey: LocalNetworkAccess.statusKey)
        #expect(LocalNetworkAccess(probe: ScriptedProbe([]), defaults: storage.defaults).status == .unknown)
    }
}

/// Reading an `NWConnection`'s state and path.
struct LocalNetworkEvidenceTests {
    @Test(
        arguments: [
            (NWConnection.State.ready, nil, LocalNetworkEvidence.reachable),
            (.waiting(.posix(.ECONNREFUSED)), nil, .reachable),
            (.failed(.posix(.ECONNREFUSED)), nil, .reachable),
            (.waiting(.posix(.ENETDOWN)), .localNetworkDenied, .denied),
            (.preparing, .localNetworkDenied, .denied),
            (.waiting(.dns(-65_570)), nil, .denied),
            (.waiting(.posix(.ETIMEDOUT)), nil, .inconclusive),
            (.waiting(.posix(.ENETDOWN)), .notAvailable, .inconclusive),
            (.failed(.posix(.EHOSTUNREACH)), nil, .inconclusive),
            (.preparing, nil, .inconclusive),
            (.setup, nil, .inconclusive),
            (.cancelled, nil, .inconclusive),
        ] as [(NWConnection.State, NWPath.UnsatisfiedReason?, LocalNetworkEvidence)])
    func evidenceFromAConnection(
        state: NWConnection.State, reason: NWPath.UnsatisfiedReason?, evidence: LocalNetworkEvidence
    ) {
        #expect(LocalNetworkEvidence(state: state, unsatisfiedReason: reason) == evidence)
    }
}

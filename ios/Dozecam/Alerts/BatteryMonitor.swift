import Observation
import UIKit

/// The battery, as far as the failure rules read it
/// (shared/spec/failure-alerts.md, "Battery low" and "Unplugging").
struct BatteryReading: Equatable, Sendable {
    enum Power: Equatable, Sendable {
        /// Not known yet, or not reported (the simulator).
        case unknown
        case unplugged
        case charging
        /// On a charger and full.
        case full
    }

    /// 0 to 1; nil until known. iPadOS reports it in 5 % steps (#58).
    var level: Float?
    var power: Power

    static let unknown = BatteryReading(level: nil, power: .unknown)

    /// The level as a whole percentage, for the wording.
    var percent: Int? { level.map { Int(($0 * 100).rounded()) } }

    /// On a charger: charging or full. Unknown is not plugged in, and not
    /// unplugged either.
    var isPluggedIn: Bool { power == .charging || power == .full }

    /// The charger came out: plugged in before, unplugged now. Unknown on
    /// either side is not a transition, so a first reading never is one.
    static func unplugged(from previous: BatteryReading, to current: BatteryReading) -> Bool {
        previous.isPluggedIn && current.power == .unplugged
    }
}

/// Where readings come from: `UIDevice` in the app, a script in tests.
@MainActor
protocol BatterySource: AnyObject {
    /// The reading now.
    var reading: BatteryReading { get }
    /// Starts reporting; `onChange` is called on the main actor after every
    /// change of level or power.
    func start(onChange: @escaping @MainActor () -> Void)
    func stop()
}

/// `UIDevice.current`, with battery monitoring switched on while started.
@MainActor
final class SystemBatterySource: BatterySource {
    private var observers: [any NSObjectProtocol] = []

    var reading: BatteryReading {
        let device = UIDevice.current
        let level = device.batteryLevel
        let power: BatteryReading.Power =
            switch device.batteryState {
            case .unplugged: .unplugged
            case .charging: .charging
            case .full: .full
            case .unknown: .unknown
            @unknown default: .unknown
            }
        return BatteryReading(level: level < 0 ? nil : min(level, 1), power: power)
    }

    func start(onChange: @escaping @MainActor () -> Void) {
        guard observers.isEmpty else { return }
        UIDevice.current.isBatteryMonitoringEnabled = true
        observers = Self.observe(relay: Self.relay(onChange))
    }

    func stop() {
        for observer in observers { NotificationCenter.default.removeObserver(observer) }
        observers = []
        UIDevice.current.isBatteryMonitoringEnabled = false
    }

    // Built outside the main actor, so the observer blocks never inherit its
    // isolation (#58); the change is hopped back onto it.

    private nonisolated static func relay(_ onChange: @escaping @MainActor () -> Void) -> @Sendable () -> Void {
        { DispatchQueue.main.async { MainActor.assumeIsolated { onChange() } } }
    }

    private nonisolated static func observe(relay: @escaping @Sendable () -> Void) -> [any NSObjectProtocol] {
        let center = NotificationCenter.default
        return [
            center.addObserver(forName: UIDevice.batteryLevelDidChangeNotification, object: nil, queue: nil) { _ in
                relay()
            },
            center.addObserver(forName: UIDevice.batteryStateDidChangeNotification, object: nil, queue: nil) { _ in
                relay()
            },
        ]
    }
}

/// The battery's level and power while monitoring, for the failure ledger's
/// "battery low" and the unplugged notice. The ledger judges the transition
/// between two of its judgements (`BatteryReading.unplugged(from:to:)`);
/// `unpluggings()` is there for anything that wants the event itself.
@MainActor
@Observable
final class BatteryMonitor {
    private(set) var reading: BatteryReading = .unknown

    @ObservationIgnored private let source: any BatterySource
    @ObservationIgnored private let readings = Broadcast(BatteryReading.unknown)
    @ObservationIgnored private let unplugs = Broadcast(())
    @ObservationIgnored private(set) var isStarted = false

    init(source: any BatterySource = SystemBatterySource()) {
        self.source = source
    }

    /// Starts following the battery. Idempotent.
    func start() {
        guard !isStarted else { return }
        isStarted = true
        source.start { [weak self] in self?.refresh() }
        refresh()
    }

    /// Stops following it, and forgets the reading: the next start begins
    /// from unknown, so an unplugging while stopped is not reported late.
    func stop() {
        guard isStarted else { return }
        isStarted = false
        source.stop()
        reading = .unknown
        readings.sendIfChanged(.unknown)
    }

    /// The reading now, then on every change.
    func updates() -> AsyncStream<BatteryReading> { readings.stream() }

    /// Once each time the charger comes out while started. No replay.
    func unpluggings() -> AsyncStream<Void> { unplugs.stream(replayingCurrent: false) }

    /// Reads the source again; the source calls it on every change.
    func refresh() {
        guard isStarted else { return }
        let next = source.reading
        let previous = reading
        guard next != previous else { return }
        reading = next
        readings.send(next)
        if BatteryReading.unplugged(from: previous, to: next) { unplugs.send(()) }
    }
}

import Testing

@testable import Dozecam

/// The battery, set by hand.
@MainActor
final class FakeBatterySource: BatterySource {
    var reading: BatteryReading = .unknown
    private(set) var isStarted = false
    private var onChange: (@MainActor () -> Void)?

    func start(onChange: @escaping @MainActor () -> Void) {
        isStarted = true
        self.onChange = onChange
    }

    func stop() {
        isStarted = false
        onChange = nil
    }

    func set(_ reading: BatteryReading) {
        self.reading = reading
        onChange?()
    }
}

@MainActor
struct BatteryMonitorTests {
    typealias Reading = BatteryReading
    let source = FakeBatterySource()
    let monitor: BatteryMonitor

    init() {
        monitor = BatteryMonitor(source: source)
    }

    nonisolated static let charging = Reading(level: 0.8, power: .charging)
    nonisolated static let full = Reading(level: 1, power: .full)
    nonisolated static let onBattery = Reading(level: 0.8, power: .unplugged)

    // MARK: - The unplugged transition

    @Test(arguments: [
        (charging, onBattery, true),
        (full, onBattery, true),
        (onBattery, onBattery, false),
        (onBattery, charging, false),
        (Reading.unknown, onBattery, false),  // a first reading is no transition
        (charging, Reading.unknown, false),
        (charging, full, false),
    ])
    func unplugged(from: Reading, to: Reading, expected: Bool) {
        #expect(Reading.unplugged(from: from, to: to) == expected)
    }

    @Test func percentRoundsAndIsNilUntilKnown() {
        #expect(Reading.unknown.percent == nil)
        #expect(Reading(level: 0.25, power: .unplugged).percent == 25)
        #expect(Reading(level: 0.349, power: .unplugged).percent == 35)
    }

    // MARK: - Following the source

    @Test func startsFromUnknownAndReadsTheSource() {
        #expect(monitor.reading == .unknown)
        source.reading = Self.charging
        monitor.start()
        #expect(source.isStarted)
        #expect(monitor.reading == Self.charging)
    }

    @Test func followsChanges() async {
        monitor.start()
        var updates = monitor.updates().makeAsyncIterator()
        #expect(await updates.next() == .unknown)

        source.set(Self.charging)
        #expect(monitor.reading == Self.charging)
        #expect(await updates.next() == Self.charging)
    }

    @Test func reportsUnpluggingOnce() async {
        source.reading = Self.charging
        monitor.start()
        let unpluggings = monitor.unpluggings()
        let counted = Task { @MainActor in
            var count = 0
            for await _ in unpluggings { count += 1 }
            return count
        }
        await settleAlerts()

        source.set(Self.onBattery)
        await settleAlerts()
        source.set(Reading(level: 0.75, power: .unplugged))
        await settleAlerts()
        counted.cancel()

        #expect(await counted.value == 1)
    }

    @Test func stoppingForgetsTheReading() {
        source.reading = Self.charging
        monitor.start()
        monitor.stop()

        #expect(!source.isStarted)
        #expect(monitor.reading == .unknown)
        // Unplugged while stopped: the next start is a first reading, not a
        // transition.
        source.reading = Self.onBattery
        monitor.start()
        #expect(monitor.reading == Self.onBattery)
    }

    @Test func changesWhileStoppedAreIgnored() {
        monitor.refresh()
        #expect(monitor.reading == .unknown)
    }
}

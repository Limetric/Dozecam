import Foundation

/// Wake-on-sound tuning, the counterpart of Android's `DetectorSettings`.
/// Every nursery (and white noise machine) is different, so all three knobs
/// are the user's. Defaults and ranges: shared/spec/alerts-and-sound-modes.md
/// (#the-detector). Field names and units match shared/fixtures/sound-detector.
struct DetectorSettings: Equatable, Sendable {
    /// Normalised RMS level (0...1) that counts as loud.
    var threshold: Float = 0.10
    /// How long the level must stay loud before triggering.
    var sustainMs = 1_500
    /// How long the level must stay quiet after a trigger before re-arming.
    var quietMs = 10_000

    /// The spec's user ranges; Android's detection sliders (`DetectionSettings.kt`).
    static let thresholdRange: ClosedRange<Float> = 0.01...0.5
    static let sustainMsRange = 500...5_000
    static let quietMsRange = 2_000...30_000

    func clamped() -> DetectorSettings {
        DetectorSettings(
            threshold: threshold.clamped(to: Self.thresholdRange),
            sustainMs: sustainMs.clamped(to: Self.sustainMsRange),
            quietMs: quietMs.clamped(to: Self.quietMsRange)
        )
    }
}

protocol DetectorSettingsStore: Sendable {
    var settings: DetectorSettings { get }

    /// `settings` now, then after every change.
    func settingsUpdates() -> AsyncStream<DetectorSettings>

    /// Atomic read-modify-write; rapid successive updates cannot clobber each
    /// other.
    func update(_ transform: @Sendable (DetectorSettings) -> DetectorSettings) async
}

/// Detector settings in UserDefaults, under Android's DataStore key names.
actor DetectorSettingsRepository: DetectorSettingsStore {
    enum Key {
        static let threshold = "detector_threshold"
        static let sustainMs = "detector_sustain_ms"
        static let quietMs = "detector_quiet_ms"
    }

    /// UserDefaults is documented as thread-safe but not marked Sendable.
    nonisolated(unsafe) private let defaults: UserDefaults
    private let changes: Broadcast<DetectorSettings>

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        changes = Broadcast(Self.settings(from: defaults))
    }

    nonisolated var settings: DetectorSettings { changes.value }

    nonisolated func settingsUpdates() -> AsyncStream<DetectorSettings> { changes.stream() }

    func update(_ transform: @Sendable (DetectorSettings) -> DetectorSettings) async {
        let next = transform(Self.settings(from: defaults)).clamped()
        defaults.set(next.threshold, forKey: Key.threshold)
        defaults.set(next.sustainMs, forKey: Key.sustainMs)
        defaults.set(next.quietMs, forKey: Key.quietMs)
        changes.sendIfChanged(next)
    }

    private static func settings(from defaults: UserDefaults) -> DetectorSettings {
        let fallback = DetectorSettings()
        return DetectorSettings(
            threshold: defaults.float(Key.threshold) ?? fallback.threshold,
            sustainMs: defaults.int(Key.sustainMs) ?? fallback.sustainMs,
            quietMs: defaults.int(Key.quietMs) ?? fallback.quietMs
        )
        .clamped()
    }
}

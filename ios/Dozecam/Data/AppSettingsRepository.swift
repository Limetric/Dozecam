import Foundation

enum OrientationLock: String, CaseIterable, Sendable {
    case auto = "AUTO"
    case portrait = "PORTRAIT"
    case landscape = "LANDSCAPE"
}

/// What the phone's speaker does with the cameras: one setting for the viewer
/// and the monitor both, because it is one speaker
/// (shared/spec/alerts-and-sound-modes.md#sound-modes). Raw values are
/// Android's `SoundMode` names.
enum SoundMode: String, CaseIterable, Sendable {
    case off = "OFF"
    /// The viewer's: one tile at a time takes a turn, and it ends when the
    /// screen does.
    case rotating = "ROTATING"
    /// Listen mode: every camera at once, on screen and with the screen off.
    case allAloud = "ALL_ALOUD"
}

/// The app's settings, the counterpart of Android's `AppSettings`: the same
/// settings, defaults and ranges (shared/spec wins where it states one).
/// Durations are milliseconds, as on Android and in shared/fixtures.
struct AppSettings: Equatable, Sendable {
    /// Dim red palette that preserves night vision next to a crib.
    var nightTheme = false
    var alertChime = true
    var alertVibrate = true
    /// The alert tone, or nil for the phone's own alarm sound. Never a
    /// notification tone: that is the one sound trained to be slept through.
    /// Android stores a ringtone URI; what identifies a tone on iOS is settled
    /// with the alert path (#68).
    var alertSound: String?
    /// Climb from a gentle first note instead of starting at full volume.
    var alertRamp = true
    var alertRepeatIntervalMs = 8_000
    /// Ceiling as a fraction of the phone's alarm volume; can quiet an alert,
    /// never make it louder than the user's own alarm.
    var alertVolume: Float = 1
    /// How long a monitored camera may be unreachable (or any other failure
    /// last) before it is announced (shared/spec/failure-alerts.md).
    var failureGraceMs = 60_000
    var orientationLock = OrientationLock.auto
    /// Off until asked for, and remembered, including all aloud.
    var soundMode = SoundMode.off
    /// Whether a room getting loud (or a failure) reaches anyone at all. On by
    /// default: a monitor that starts out not waking anyone is the failure
    /// nobody discovers until the one night it matters.
    var alertsEnabled = true
    /// Whether the viewer holds the display awake while cameras are showing.
    var keepScreenOn = true
    /// How loud a talk-back press comes out of the camera, as a 0...1 slider
    /// position applied to the samples this phone sends.
    var talkbackVolume: Float = 1

    // User ranges. Android enforces them with its sliders; here they are also
    // applied to what is stored (see `clamped()`).

    /// shared/spec/alerts-and-sound-modes.md: 3 – 30 s. Android:
    /// `AlarmSchedule.MIN_REPEAT_INTERVAL_MS` / `MAX_REPEAT_INTERVAL_MS`.
    static let alertRepeatIntervalMsRange = 3_000...30_000
    /// shared/spec/alerts-and-sound-modes.md: 10 – 100 %. Android: the
    /// alert-volume slider (`AlertsSettings.kt`), never zero.
    static let alertVolumeRange: ClosedRange<Float> = 0.1...1
    /// shared/spec/failure-alerts.md: 30 s – 5 min. Android:
    /// `FailureLedger.MIN_GRACE_MS` / `MAX_GRACE_MS`.
    static let failureGraceMsRange = 30_000...300_000
    /// Android: the talk-back volume slider (`DisplaySettings.kt`).
    static let talkbackVolumeRange: ClosedRange<Float> = 0...1

    /// These settings with every ranged value brought inside its range.
    func clamped() -> AppSettings {
        var settings = self
        settings.alertRepeatIntervalMs = alertRepeatIntervalMs.clamped(to: Self.alertRepeatIntervalMsRange)
        settings.alertVolume = alertVolume.clamped(to: Self.alertVolumeRange)
        settings.failureGraceMs = failureGraceMs.clamped(to: Self.failureGraceMsRange)
        settings.talkbackVolume = talkbackVolume.clamped(to: Self.talkbackVolumeRange)
        return settings
    }
}

protocol AppSettingsStore: Sendable {
    var settings: AppSettings { get }

    /// `settings` now, then after every change.
    func settingsUpdates() -> AsyncStream<AppSettings>

    /// Atomic read-modify-write; rapid successive updates cannot clobber each
    /// other.
    func update(_ transform: @Sendable (AppSettings) -> AppSettings) async
}

/// App settings in UserDefaults, under Android's DataStore key names. Not
/// secret (shared/spec/privacy.md), so no protection beyond the app sandbox.
actor AppSettingsRepository: AppSettingsStore {
    enum Key {
        static let nightTheme = "night_theme"
        static let alertChime = "alert_chime"
        static let alertVibrate = "alert_vibrate"
        static let alertSound = "alert_sound"
        static let alertRamp = "alert_ramp"
        static let alertRepeatIntervalMs = "alert_repeat_interval_ms"
        static let alertVolume = "alert_volume"
        static let failureGraceMs = "failure_grace_ms"
        static let orientationLock = "orientation_lock"
        static let soundMode = "sound_mode"
        static let alertsEnabled = "alerts_enabled"
        static let keepScreenOn = "keep_screen_on"
        static let talkbackVolume = "talkback_volume"
    }

    /// UserDefaults is documented as thread-safe but not marked Sendable.
    nonisolated(unsafe) private let defaults: UserDefaults
    private let changes: Broadcast<AppSettings>

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        changes = Broadcast(Self.settings(from: defaults))
    }

    nonisolated var settings: AppSettings { changes.value }

    nonisolated func settingsUpdates() -> AsyncStream<AppSettings> { changes.stream() }

    func update(_ transform: @Sendable (AppSettings) -> AppSettings) async {
        let next = transform(Self.settings(from: defaults)).clamped()
        defaults.set(next.nightTheme, forKey: Key.nightTheme)
        defaults.set(next.alertChime, forKey: Key.alertChime)
        defaults.set(next.alertVibrate, forKey: Key.alertVibrate)
        // Absent rather than empty: "no choice made" has to keep meaning the
        // phone's own alarm sound, whatever that becomes later.
        if let sound = next.alertSound {
            defaults.set(sound, forKey: Key.alertSound)
        } else {
            defaults.removeObject(forKey: Key.alertSound)
        }
        defaults.set(next.alertRamp, forKey: Key.alertRamp)
        defaults.set(next.alertRepeatIntervalMs, forKey: Key.alertRepeatIntervalMs)
        defaults.set(next.alertVolume, forKey: Key.alertVolume)
        defaults.set(next.failureGraceMs, forKey: Key.failureGraceMs)
        defaults.set(next.orientationLock.rawValue, forKey: Key.orientationLock)
        defaults.set(next.soundMode.rawValue, forKey: Key.soundMode)
        defaults.set(next.alertsEnabled, forKey: Key.alertsEnabled)
        defaults.set(next.keepScreenOn, forKey: Key.keepScreenOn)
        defaults.set(next.talkbackVolume, forKey: Key.talkbackVolume)
        changes.sendIfChanged(next)
    }

    /// A missing or unreadable value is its default, as on Android; an enum
    /// name this build does not know is too.
    private static func settings(from defaults: UserDefaults) -> AppSettings {
        let fallback = AppSettings()
        return AppSettings(
            nightTheme: defaults.bool(Key.nightTheme) ?? fallback.nightTheme,
            alertChime: defaults.bool(Key.alertChime) ?? fallback.alertChime,
            alertVibrate: defaults.bool(Key.alertVibrate) ?? fallback.alertVibrate,
            alertSound: defaults.string(forKey: Key.alertSound) ?? fallback.alertSound,
            alertRamp: defaults.bool(Key.alertRamp) ?? fallback.alertRamp,
            alertRepeatIntervalMs: defaults.int(Key.alertRepeatIntervalMs) ?? fallback.alertRepeatIntervalMs,
            alertVolume: defaults.float(Key.alertVolume) ?? fallback.alertVolume,
            failureGraceMs: defaults.int(Key.failureGraceMs) ?? fallback.failureGraceMs,
            orientationLock: defaults.string(forKey: Key.orientationLock).flatMap(OrientationLock.init(rawValue:))
                ?? fallback.orientationLock,
            soundMode: defaults.string(forKey: Key.soundMode).flatMap(SoundMode.init(rawValue:))
                ?? fallback.soundMode,
            alertsEnabled: defaults.bool(Key.alertsEnabled) ?? fallback.alertsEnabled,
            keepScreenOn: defaults.bool(Key.keepScreenOn) ?? fallback.keepScreenOn,
            talkbackVolume: defaults.float(Key.talkbackVolume) ?? fallback.talkbackVolume
        )
        .clamped()
    }
}

extension UserDefaults {
    /// The stored Bool, or nil when there is none (UserDefaults' own `bool`
    /// reads a missing value as false, which is not the default of most of
    /// these settings).
    func bool(_ key: String) -> Bool? { (object(forKey: key) as? NSNumber)?.boolValue }
    func int(_ key: String) -> Int? { (object(forKey: key) as? NSNumber)?.intValue }
    func float(_ key: String) -> Float? { (object(forKey: key) as? NSNumber)?.floatValue }
}

extension Comparable {
    func clamped(to range: ClosedRange<Self>) -> Self {
        min(max(self, range.lowerBound), range.upperBound)
    }
}

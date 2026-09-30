import Foundation

/// The on/off settings: what each one is called, what it says under its
/// title, and which field of `AppSettings` it is. Titles and descriptions are
/// Android's (`strings.xml`), reworded only where iOS behaves differently.
enum ToggleSetting: String, CaseIterable, Sendable {
    case alertsEnabled = "alerts"
    case alertChime = "chime"
    case alertVibrate = "vibrate"
    case alertRamp = "alert-ramp"
    case nightTheme = "night-theme"
    case keepScreenOn = "keep-screen"

    var id: String { rawValue }

    var title: String {
        switch self {
        case .alertsEnabled: "Wake alerts"
        case .alertChime: "Alert chime"
        case .alertVibrate: "Alert vibration"
        case .alertRamp: "Escalate volume"
        case .nightTheme: "Night theme"
        case .keepScreenOn: "Keep the screen awake"
        }
    }

    var description: String {
        switch self {
        // shared/spec: alertsEnabled gates failure alerts as well as sound.
        case .alertsEnabled: "Sound the alarm when a room gets loud or the monitor stops working"
        case .alertChime: "Play a sound when a wake alert fires"
        case .alertVibrate: "Vibrate when a wake alert fires"
        case .alertRamp: "Start gently and build over a few seconds"
        case .nightTheme: "Dim red palette that preserves night vision"
        case .keepScreenOn: "The display stays on while cameras are showing"
        }
    }

    var systemImage: String {
        switch self {
        case .alertsEnabled: "bell.badge"
        case .alertChime: "speaker.wave.2"
        case .alertVibrate: "iphone.radiowaves.left.and.right"
        case .alertRamp: "chart.line.uptrend.xyaxis"
        case .nightTheme: "moon"
        case .keepScreenOn: "sun.max"
        }
    }

    var section: SettingsSection {
        switch self {
        case .alertsEnabled, .alertChime, .alertVibrate, .alertRamp: .alerts
        case .nightTheme, .keepScreenOn: .display
        }
    }

    func value(in settings: AppSettings) -> Bool {
        switch self {
        case .alertsEnabled: settings.alertsEnabled
        case .alertChime: settings.alertChime
        case .alertVibrate: settings.alertVibrate
        case .alertRamp: settings.alertRamp
        case .nightTheme: settings.nightTheme
        case .keepScreenOn: settings.keepScreenOn
        }
    }

    func applying(_ isOn: Bool, to settings: AppSettings) -> AppSettings {
        var next = settings
        switch self {
        case .alertsEnabled: next.alertsEnabled = isOn
        case .alertChime: next.alertChime = isOn
        case .alertVibrate: next.alertVibrate = isOn
        case .alertRamp: next.alertRamp = isOn
        case .nightTheme: next.nightTheme = isOn
        case .keepScreenOn: next.keepScreenOn = isOn
        }
        return next
    }
}

/// The sliders. Each works in the unit it is shown in (percent or seconds),
/// converts to the stored unit (a 0...1 fraction or milliseconds), and takes
/// its range from the store's own, so the two cannot drift apart.
///
/// Android's sliders are continuous and only their labels round. Here each
/// moves in steps of what its label shows (1 %, 0.1 s, 1 s; 5 s for the
/// 30 s – 5 min grace period), so what is stored is what was read.
enum SliderSetting: String, CaseIterable, Sendable {
    case threshold
    case sustain
    case quiet
    case alertVolume = "alert-volume"
    case alertRepeat = "alert-repeat"
    case failureGrace = "failure-grace"
    case talkbackVolume = "talkback-volume"

    enum Unit: Sendable {
        case percent
        case seconds
    }

    var id: String { rawValue }

    var title: String {
        switch self {
        case .threshold: "Sound threshold"
        case .sustain: "Trigger after"
        case .quiet: "Re-arm after"
        case .alertVolume: "Alert volume"
        case .alertRepeat: "Repeat every"
        case .failureGrace: "Alarm when a camera is unreachable for"
        case .talkbackVolume: "Talk-back volume"
        }
    }

    var section: SettingsSection {
        switch self {
        case .threshold, .sustain, .quiet: .detection
        case .alertVolume, .alertRepeat, .failureGrace: .alerts
        case .talkbackVolume: .display
        }
    }

    var unit: Unit {
        switch self {
        case .threshold, .alertVolume, .talkbackVolume: .percent
        case .sustain, .quiet, .alertRepeat, .failureGrace: .seconds
        }
    }

    /// The range in the shown unit: the store's range, converted.
    var range: ClosedRange<Double> {
        switch self {
        case .threshold: Self.percent(DetectorSettings.thresholdRange)
        case .sustain: Self.seconds(DetectorSettings.sustainMsRange)
        case .quiet: Self.seconds(DetectorSettings.quietMsRange)
        case .alertVolume: Self.percent(AppSettings.alertVolumeRange)
        case .alertRepeat: Self.seconds(AppSettings.alertRepeatIntervalMsRange)
        case .failureGrace: Self.seconds(AppSettings.failureGraceMsRange)
        case .talkbackVolume: Self.percent(AppSettings.talkbackVolumeRange)
        }
    }

    var step: Double {
        switch self {
        case .threshold, .alertVolume, .talkbackVolume: 1
        case .sustain: 0.1
        case .quiet, .alertRepeat: 1
        case .failureGrace: 5
        }
    }

    /// Where `value` lands: inside the range, on a step.
    func snapped(_ value: Double) -> Double {
        let bounded = value.clamped(to: range)
        let steps = ((bounded - range.lowerBound) / step).rounded()
        return (range.lowerBound + steps * step).clamped(to: range)
    }

    /// The value as its row shows it, Android's label formats: "12%",
    /// "1.5 s", "10 s".
    func formatted(_ value: Double) -> String {
        let value = snapped(value)
        switch self {
        case .threshold, .alertVolume, .talkbackVolume: return "\(Int(value.rounded()))%"
        case .sustain: return String(format: "%.1f s", value)
        case .quiet, .alertRepeat, .failureGrace: return "\(Int(value.rounded())) s"
        }
    }

    /// What VoiceOver reads for the value: units spelled out, and the
    /// qualifier Android's labels carry ("of sound", "of your alarm volume").
    func spokenValue(_ value: Double) -> String {
        let value = snapped(value)
        let amount: String
        switch self {
        case .threshold, .alertVolume, .talkbackVolume: amount = "\(Int(value.rounded())) percent"
        case .sustain: amount = String(format: "%.1f seconds", value)
        case .quiet, .alertRepeat, .failureGrace:
            let seconds = Int(value.rounded())
            amount = "\(seconds) \(seconds == 1 ? "second" : "seconds")"
        }
        return [amount, qualifier].compactMap(\.self).joined(separator: " ")
    }

    /// Words after the value, on screen and spoken.
    var qualifier: String? {
        switch self {
        case .sustain: "of sound"
        case .quiet: "of quiet"
        case .alertVolume: "of your alarm volume"
        default: nil
        }
    }

    /// The line under the slider, where Android has one.
    var footnote: String? {
        switch self {
        case .alertRepeat:
            "An alert repeats until you touch the screen, and gives up on its own after five minutes."
        case .failureGrace:
            "A brief reconnect never fires this. A camera that stays unreachable this long sounds its own alarm, "
                + "distinct from a sound alert — as does a battery running low."
        case .talkbackVolume:
            "How loud your voice comes out of the camera's speaker. The bottom of the scale is a whisper, "
                + "not silence, and the camera's own volume setting is never touched."
        default: nil
        }
    }

    /// Extra words search should find this slider by.
    var keywords: [String] {
        switch self {
        case .threshold: ["sensitivity", "loud", "level", "meter"]
        case .sustain: ["sustain", "duration", "how long"]
        case .quiet: ["quiet", "rearm", "cooldown"]
        case .alertVolume: ["volume", "loudness", "alarm"]
        case .alertRepeat: ["repeat", "interval"]
        case .failureGrace: ["grace", "offline", "unreachable", "failure", "disconnect"]
        case .talkbackVolume: ["talk-back", "talkback", "microphone", "speaker", "voice"]
        }
    }

    // MARK: Stored values

    /// The stored value in the shown unit, not yet snapped.
    func value(app: AppSettings, detector: DetectorSettings) -> Double {
        switch self {
        case .threshold: Double(detector.threshold) * 100
        case .sustain: Double(detector.sustainMs) / 1_000
        case .quiet: Double(detector.quietMs) / 1_000
        case .alertVolume: Double(app.alertVolume) * 100
        case .alertRepeat: Double(app.alertRepeatIntervalMs) / 1_000
        case .failureGrace: Double(app.failureGraceMs) / 1_000
        case .talkbackVolume: Double(app.talkbackVolume) * 100
        }
    }

    /// Whether this slider is stored with the detector settings rather than
    /// the app settings.
    var isDetector: Bool { section == .detection }

    /// `settings` with this slider at `value` (in the shown unit, already
    /// snapped). Unchanged for a detector slider.
    func applying(_ value: Double, to settings: AppSettings) -> AppSettings {
        var next = settings
        switch self {
        case .alertVolume: next.alertVolume = Float(value / 100)
        case .alertRepeat: next.alertRepeatIntervalMs = Self.milliseconds(value)
        case .failureGrace: next.failureGraceMs = Self.milliseconds(value)
        case .talkbackVolume: next.talkbackVolume = Float(value / 100)
        case .threshold, .sustain, .quiet: break
        }
        return next.clamped()
    }

    /// `settings` with this slider at `value`. Unchanged for an app slider.
    func applying(_ value: Double, to settings: DetectorSettings) -> DetectorSettings {
        var next = settings
        switch self {
        case .threshold: next.threshold = Float(value / 100)
        case .sustain: next.sustainMs = Self.milliseconds(value)
        case .quiet: next.quietMs = Self.milliseconds(value)
        case .alertVolume, .alertRepeat, .failureGrace, .talkbackVolume: break
        }
        return next.clamped()
    }

    private static func milliseconds(_ seconds: Double) -> Int { Int((seconds * 1_000).rounded()) }

    private static func percent(_ range: ClosedRange<Float>) -> ClosedRange<Double> {
        (Double(range.lowerBound) * 100).rounded()...(Double(range.upperBound) * 100).rounded()
    }

    private static func seconds(_ range: ClosedRange<Int>) -> ClosedRange<Double> {
        Double(range.lowerBound) / 1_000...Double(range.upperBound) / 1_000
    }
}

extension OrientationLock {
    /// The segmented control's labels.
    var shortTitle: String {
        switch self {
        case .auto: "Auto"
        case .portrait: "Portrait"
        case .landscape: "Landscape"
        }
    }

    var description: String {
        switch self {
        case .auto: "Follow the device's rotation"
        case .portrait: "Lock to portrait"
        case .landscape: "Lock to landscape"
        }
    }
}

extension AppSettings {
    /// What the alert sound row says. Picking a tone arrives with the alert
    /// path (#68); until then this names what is stored, never pretending.
    var alertSoundTitle: String {
        alertSound == nil ? "Your device's alarm sound" : "Chosen sound"
    }
}

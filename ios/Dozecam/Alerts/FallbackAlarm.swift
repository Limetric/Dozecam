import AudioToolbox
import Foundation

/// The fallback alarm's speaker: what sounds when AlarmKit is not authorised
/// (#58). The counterpart of Android's `AlarmPlayer`, with the same three
/// verbs, so the same schedule drives it: *when* to make noise and how loud
/// (the ramp from 15 % to the ceiling, a burst every repeat interval, the
/// give-up) is `AlarmSchedule`'s to decide and the caller's to carry out; this
/// only obeys.
///
/// `volume` is the schedule's `volumeAt`, the ramp times the ceiling, as a
/// fraction of full scale. The media volume applies on top: iOS gives an app
/// no alarm stream, so the fallback is as loud as the media volume allows,
/// silent at zero (shared/spec/alerts-and-sound-modes.md, iOS notes).
@MainActor
protocol AlarmTonePlayer: AnyObject {
    /// Starts one burst of `tone` at `volume`, replacing any burst still
    /// playing. False when nothing can be heard now (the speaker is refused
    /// or interrupted, or the tone could not be loaded).
    @discardableResult
    func start(_ tone: AlarmTone, volume: Float) -> Bool
    /// Adjusts the burst in flight; ignored when nothing is playing.
    func setVolume(_ volume: Float)
    func stop()
    /// Whether a burst is sounding.
    var isPlaying: Bool { get }
}

/// The fallback tone through the speaker's running engine
/// (`Speaker.playAlarm`), so it plays from the background with the screen
/// locked, and needs no second audio session: a second one without mixing
/// would end monitoring the next time the app went to the background (#58).
@MainActor
final class SpeakerAlarmPlayer: AlarmTonePlayer {
    private let speaker: Speaker
    private let bundle: Bundle
    /// Decoded once and kept for the life of the player, so the render thread
    /// never frees one (`AlarmToneBuffer`).
    private var tones: [AlarmTone: AlarmToneBuffer] = [:]

    init(speaker: Speaker, bundle: Bundle = .main) {
        self.speaker = speaker
        self.bundle = bundle
    }

    /// Decodes every tone now, so the first alarm does not pay for it.
    func preload() {
        for tone in AlarmTone.allCases { _ = buffer(for: tone) }
    }

    @discardableResult
    func start(_ tone: AlarmTone, volume: Float) -> Bool {
        guard let buffer = buffer(for: tone) else { return false }
        return speaker.playAlarm(buffer, gain: volume)
    }

    func setVolume(_ volume: Float) {
        speaker.setAlarmGain(volume)
    }

    func stop() {
        speaker.stopAlarm()
    }

    var isPlaying: Bool { speaker.isAlarmPlaying }

    private func buffer(for tone: AlarmTone) -> AlarmToneBuffer? {
        if let loaded = tones[tone] { return loaded }
        let loaded = tone.load(from: bundle)
        tones[tone] = loaded
        return loaded
    }
}

/// The alarm's vibration, a pulse per burst like Android's `AlarmVibrator`.
@MainActor
protocol AlarmVibrator: AnyObject {
    func pulse()
    func cancel()
}

/// The system vibration. iOS offers apps no alarm-class vibration: this is the
/// ordinary one, which the Settings app's vibration switches can turn off, and
/// whether it runs in the background with the screen locked is unverified
/// (#58's deferred list). AlarmKit vibrates on its own when it rings.
@MainActor
final class SystemAlarmVibrator: AlarmVibrator {
    func pulse() {
        AudioServicesPlayAlertSound(kSystemSoundID_Vibrate)
    }

    /// A system vibration cannot be cut short.
    func cancel() {}
}

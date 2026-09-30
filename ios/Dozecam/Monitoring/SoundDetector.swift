/// Wake-on-sound state machine, the port of Android's `SoundDetector`
/// (shared/spec/alerts-and-sound-modes.md#the-detector; fixtures in
/// `shared/fixtures/sound-detector/detector.json`). Deliberately boring:
///
/// - `armed`: waiting for a loud level.
/// - `building`: the level went loud; a dip back below the threshold re-arms
///   (a single thud or dropped pacifier must not wake the room). Staying loud
///   for `DetectorSettings.sustainMs` fires the trigger.
/// - `triggered`: refractory; re-arms only after the level stays below the
///   threshold for `DetectorSettings.quietMs` straight.
///
/// A value type with no clock of its own: the caller feeds each level with the
/// time it was decoded, so the detector can be driven from whichever single
/// place owns it, and tests replay a timeline exactly.
struct SoundDetector: Sendable {
    enum Phase: Equatable, Sendable {
        case armed
        case building
        case triggered
    }

    private(set) var settings: DetectorSettings
    private(set) var phase: Phase = .armed

    private var loudSinceMs: Int64 = 0
    /// Nil until the first quiet level after a trigger: the re-arm window is
    /// timed from that sample, not from the trigger.
    private var quietSinceMs: Int64?

    init(settings: DetectorSettings) {
        self.settings = settings
    }

    /// Settings apply to the next level fed in; the phase is kept, so a slider
    /// moved mid-cry neither re-arms nor fires the detector on its own.
    mutating func updateSettings(_ settings: DetectorSettings) {
        self.settings = settings
    }

    /// Feeds one level (normalised RMS, 0...1) decoded at `nowMs`; returns true
    /// exactly when a trigger fires. Levels compare as 32-bit floats, as the
    /// fixtures and Android do.
    mutating func onLevel(_ rms: Float, nowMs: Int64) -> Bool {
        let loud = rms >= settings.threshold
        switch phase {
        case .armed:
            if loud {
                phase = .building
                loudSinceMs = nowMs
            }
        case .building:
            if !loud {
                phase = .armed
            } else if nowMs - loudSinceMs >= Int64(settings.sustainMs) {
                phase = .triggered
                quietSinceMs = nil
                return true
            }
        case .triggered:
            if loud {
                quietSinceMs = nil
            } else if let quietSinceMs {
                if nowMs - quietSinceMs >= Int64(settings.quietMs) { phase = .armed }
            } else {
                quietSinceMs = nowMs
            }
        }
        return false
    }
}

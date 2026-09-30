import Synchronization

/// One tone's PCM, mono Float at `SpeakerMix.sampleRate`, fixed once made.
///
/// Held for the life of the app by whoever loaded it (`SpeakerAlarmPlayer`),
/// so the render thread only ever retains and releases it, and never frees
/// it. Unchecked Sendable: the samples are written once, in `init`, and only
/// read afterwards.
final class AlarmToneBuffer: @unchecked Sendable {
    let samples: UnsafeMutablePointer<Float>
    let count: Int

    init(samples source: [Float]) {
        count = source.count
        samples = .allocate(capacity: max(source.count, 1))
        samples.initialize(repeating: 0, count: max(source.count, 1))
        source.withUnsafeBufferPointer { buffer in
            if let base = buffer.baseAddress { samples.update(from: base, count: buffer.count) }
        }
    }

    deinit {
        samples.deallocate()
    }

    var duration: Double { Double(count) / SpeakerMix.sampleRate }
}

/// The alarm's voice in the speaker's mix: the fallback when AlarmKit is not
/// authorised plays its tone through the engine that is already running for
/// monitoring, at media volume (#58, shared/spec/alerts-and-sound-modes.md).
///
/// A burst is one play of a tone from the top, at a gain the caller sets and
/// changes as the ramp climbs; it ends by itself at the tone's end, and a new
/// burst restarts it. The gain is the ramp times the ceiling, as a fraction of
/// full scale; the media volume applies on top, as it does to everything the
/// engine plays.
///
/// Render-thread safe the same way `SpeakerSink` is: a mutex held only to
/// copy a few values, and no allocation. A gain change glides across one
/// render buffer, so a ramp's steps do not click.
final class AlarmVoice: Sendable {
    /// Unchecked: the tone reference is only touched under the mutex.
    private struct State: @unchecked Sendable {
        var tone: AlarmToneBuffer?
        var position = 0
        /// What the caller asked for.
        var gain: Float = 0
        /// What the last buffer ended on, for the glide.
        var applied: Float = 0
    }

    private let state = Mutex(State())

    /// Starts a burst of `tone` from its first sample, replacing any burst in
    /// flight. The first buffer starts at `gain` rather than gliding up to it.
    func play(_ tone: AlarmToneBuffer, gain: Float) {
        let gain = Self.clamp(gain)
        state.withLock { state in
            state.tone = tone
            state.position = 0
            state.gain = gain
            state.applied = gain
        }
    }

    /// Changes the gain of the burst in flight, and of nothing else.
    func setGain(_ gain: Float) {
        let gain = Self.clamp(gain)
        state.withLock { $0.gain = gain }
    }

    func stop() {
        state.withLock { state in
            state.tone = nil
            state.position = 0
        }
    }

    /// Whether a burst is sounding now.
    var isPlaying: Bool { state.withLock { $0.tone != nil } }

    /// The gain asked for; for tests and logs.
    var gain: Float { state.withLock { $0.gain } }

    /// Render thread: adds up to `frames` samples of the burst onto `output`.
    /// True when it added anything.
    @discardableResult
    func mix(into output: UnsafeMutablePointer<Float>, frames: Int) -> Bool {
        state.withLock { state in
            guard let tone = state.tone, frames > 0 else { return false }
            let run = min(frames, tone.count - state.position)
            let from = state.applied
            let to = state.gain
            let step = (to - from) / Float(frames)
            let source = tone.samples + state.position
            for index in 0..<max(run, 0) {
                output[index] += source[index] * (from + step * Float(index + 1))
            }
            state.applied = to
            state.position += max(run, 0)
            if state.position >= tone.count {
                state.tone = nil
                state.position = 0
            }
            return run > 0
        }
    }

    private static func clamp(_ gain: Float) -> Float {
        gain.isFinite ? min(max(gain, 0), 1) : 0
    }
}

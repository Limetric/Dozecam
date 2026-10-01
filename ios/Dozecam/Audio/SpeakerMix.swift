import Synchronization

/// One camera's way into the speaker: the PCM its audio-only player decodes,
/// waiting for the engine's render thread to mix it out or throw it away.
///
/// A bounded ring of mono Float samples at `SpeakerMix.sampleRate`, written by
/// libVLC's audio thread and read by the render thread. Full, it drops the
/// oldest samples, so a render thread that stopped (an interruption, the
/// engine restarting) never turns into seconds of delay once it runs again: a
/// nursery heard late is worse than a gap.
///
/// Lock-protected rather than lock-free: both sides hold the lock only to copy
/// a buffer's worth of floats, with no allocation and nothing that can block
/// inside it, which is what the render thread needs. Sendable: its callers are
/// three threads (libVLC's, the render thread, and the main actor choosing
/// what is aloud).
final class SpeakerSink: Sendable {
    let cameraId: String

    /// Unchecked: its buffer is only ever touched under the mutex.
    private struct Ring: @unchecked Sendable {
        let buffer: UnsafeMutablePointer<Float>
        let capacity: Int
        var head = 0
        var count = 0
    }

    private let ring: Mutex<Ring>
    /// Whether the render thread mixes this sink out or discards it.
    private let aloud = Atomic<Bool>(false)

    init(cameraId: String, capacity: Int = SpeakerMix.sinkCapacity) {
        precondition(capacity > 0)
        self.cameraId = cameraId
        let buffer = UnsafeMutablePointer<Float>.allocate(capacity: capacity)
        buffer.initialize(repeating: 0, count: capacity)
        ring = Mutex(Ring(buffer: buffer, capacity: capacity))
    }

    deinit {
        ring.withLock { $0.buffer.deallocate() }
    }

    /// Producer side, from libVLC's audio thread: queues `samples`, dropping
    /// the oldest queued ones if they no longer fit.
    func write(_ samples: UnsafeBufferPointer<Float>) {
        guard let source = samples.baseAddress, !samples.isEmpty else { return }
        ring.withLock { ring in
            // Only the newest `capacity` samples of an oversized buffer matter.
            let incoming = min(samples.count, ring.capacity)
            let start = source + (samples.count - incoming)
            let overflow = ring.count + incoming - ring.capacity
            if overflow > 0 {
                ring.head = (ring.head + overflow) % ring.capacity
                ring.count -= overflow
            }
            var tail = (ring.head + ring.count) % ring.capacity
            var copied = 0
            while copied < incoming {
                let run = min(incoming - copied, ring.capacity - tail)
                (ring.buffer + tail).update(from: start + copied, count: run)
                copied += run
                tail = (tail + run) % ring.capacity
            }
            ring.count += incoming
        }
    }

    /// The samples waiting; for tests.
    var queued: Int { ring.withLock { $0.count } }

    var isAloud: Bool { aloud.load(ordering: .relaxed) }

    fileprivate func setAloud(_ value: Bool) {
        // Coming up aloud starts from now: whatever queued while it was
        // silent is the past.
        if value, !isAloud { discardAll() }
        aloud.store(value, ordering: .relaxed)
    }

    /// Consumer side: adds up to `frames` queued samples onto `output`, which
    /// already holds the mix so far. An underrun leaves the rest untouched,
    /// that is silent for this room.
    func mix(into output: UnsafeMutablePointer<Float>, frames: Int) {
        ring.withLock { ring in
            let available = min(frames, ring.count)
            var done = 0
            while done < available {
                let run = min(available - done, ring.capacity - ring.head)
                let from = ring.buffer + ring.head
                for index in 0..<run { output[done + index] += from[index] }
                done += run
                ring.head = (ring.head + run) % ring.capacity
            }
            ring.count -= available
        }
    }

    /// Consumer side: a room that is not aloud is read all the same and
    /// thrown away, so it builds no backlog for the moment it is.
    fileprivate func discardAll() {
        ring.withLock { ring in
            ring.head = 0
            ring.count = 0
        }
    }
}

/// Every camera's sink, and which of them come out of the speaker: what the
/// engine's render block plays. Thread-safe: the main actor adds sinks and
/// chooses the aloud set; the render thread reads.
final class SpeakerMix: Sendable {
    /// libVLC is asked for this rate (`AudioOnlyPlayer`), and the engine's
    /// source node renders at it; the engine converts to the hardware's own.
    static let sampleRate: Double = 48_000
    /// A second. libVLC hands audio over in bursts (on the testbed, ~0.7 s
    /// of it every ~0.7 s; see `LevelSample`), and a ring smaller than a burst
    /// would drop part of every one: a gap each time. It is also the most
    /// delay a room can build up.
    static let sinkCapacity = 48_000

    private struct Sinks {
        /// An array rather than a dictionary: the render thread copies it (a
        /// retain, no allocation) and walks it; there are a handful of cameras.
        var all: [SpeakerSink] = []
        /// Kept apart from the sinks, so a room chosen before its player has
        /// made a sink is aloud from its first sample.
        var aloud: Set<String> = []
    }

    private let sinks = Mutex(Sinks())

    /// The fallback alarm's tone, mixed over the rooms (`AlarmVoice`).
    let alarm = AlarmVoice()

    /// The sink for `cameraId`, made on first ask. The same sink for as long
    /// as the camera has one, so a player reconnecting keeps writing where the
    /// mix reads.
    func sink(for cameraId: String) -> SpeakerSink {
        sinks.withLock { sinks in
            if let existing = sinks.all.first(where: { $0.cameraId == cameraId }) { return existing }
            let sink = SpeakerSink(cameraId: cameraId)
            sink.setAloud(sinks.aloud.contains(cameraId))
            sinks.all.append(sink)
            return sink
        }
    }

    func removeSink(for cameraId: String) {
        sinks.withLock { $0.all.removeAll { $0.cameraId == cameraId } }
    }

    var cameraIds: [String] { sinks.withLock { $0.all.map(\.cameraId) } }

    /// Chooses the rooms mixed out; every other sink is drained into nothing.
    func setAloud(_ cameraIds: Set<String>) {
        sinks.withLock { sinks in
            sinks.aloud = cameraIds
            for sink in sinks.all { sink.setAloud(cameraIds.contains(sink.cameraId)) }
        }
    }

    /// The render thread's work: `frames` mono samples of every aloud room
    /// added together, and the alarm's burst over them, clipped to full
    /// scale. Silence when nothing is aloud, which is what keeps the engine
    /// (and so the app) running with the sound off.
    func render(into output: UnsafeMutablePointer<Float>, frames: Int) {
        output.update(repeating: 0, count: frames)
        let current = sinks.withLock { $0.all }
        var mixed = 0
        for sink in current {
            if sink.isAloud {
                sink.mix(into: output, frames: frames)
                mixed += 1
            } else {
                sink.discardAll()
            }
        }
        if alarm.mix(into: output, frames: frames) { mixed += 1 }
        // One source cannot exceed full scale; two loud ones summed can.
        guard mixed > 1 else { return }
        for index in 0..<frames { output[index] = min(max(output[index], -1), 1) }
    }
}

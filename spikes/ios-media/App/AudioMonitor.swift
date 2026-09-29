import AVFoundation
import Foundation
import Synchronization

/// Wake-on-sound's input on iOS: one audio-only libVLC player per room, whose
/// decoded PCM arrives through `libvlc_audio_set_callbacks` instead of going
/// to a speaker. The samples feed a level meter (the detector's input) and,
/// when listening, a per-room node in one AVAudioEngine mix.
final class AudioRoom: @unchecked Sendable {
    let name: String
    let url: String
    /// RMS of the most recent callback's samples, as Float bits.
    let rmsBits = Atomic<UInt32>(0)
    let samples = Atomic<Int>(0)
    let callbacks = Atomic<Int>(0)
    let ring = PcmRing(capacity: 48_000 / 2)
    fileprivate(set) var player: OpaquePointer?
    var sourceNode: AVAudioSourceNode?

    init(name: String, url: String) {
        self.name = name
        self.url = url
    }

    var rms: Float { Float(bitPattern: rmsBits.load(ordering: .relaxed)) }
}

/// A small mutex-guarded FIFO of mono Float samples between libVLC's audio
/// thread and the engine's render thread. Bounded so a stalled reader never
/// builds up latency: the oldest samples are dropped.
final class PcmRing: Sendable {
    private let storage: Mutex<[Float]>
    private let capacity: Int

    init(capacity: Int) {
        self.capacity = capacity
        storage = Mutex([])
    }

    func push(_ buffer: UnsafeBufferPointer<Float>) {
        storage.withLock { samples in
            samples.append(contentsOf: buffer)
            if samples.count > capacity { samples.removeFirst(samples.count - capacity) }
        }
    }

    func pop(into out: UnsafeMutablePointer<Float>, count: Int) {
        storage.withLock { samples in
            let n = min(count, samples.count)
            for i in 0..<n { out[i] = samples[i] }
            for i in n..<count { out[i] = 0 }
            samples.removeFirst(n)
        }
    }
}

// libVLC calls these on its own audio output thread: file-scope functions, so
// they carry no actor isolation (see the #58 crash).
private func audioPlay(_ opaque: UnsafeMutableRawPointer?, _ samples: UnsafeRawPointer?, _ count: UInt32, _ pts: Int64) {
    guard let opaque, let samples else { return }
    let room = Unmanaged<AudioRoom>.fromOpaque(opaque).takeUnretainedValue()
    let buffer = UnsafeBufferPointer(start: samples.assumingMemoryBound(to: Float.self), count: Int(count))
    var sum: Float = 0
    for s in buffer { sum += s * s }
    let rms = count > 0 ? (sum / Float(count)).squareRoot() : 0
    room.rmsBits.store(rms.bitPattern, ordering: .relaxed)
    room.samples.add(Int(count), ordering: .relaxed)
    room.callbacks.add(1, ordering: .relaxed)
    room.ring.push(buffer)
}

private func audioFlush(_ opaque: UnsafeMutableRawPointer?, _ pts: Int64) {}

private func dialogQuestion(
    _ data: UnsafeMutableRawPointer?, _ id: OpaquePointer?, _ title: UnsafePointer<CChar>?, _ text: UnsafePointer<CChar>?,
    _ type: libvlc_dialog_question_type, _ cancel: UnsafePointer<CChar>?, _ action1: UnsafePointer<CChar>?,
    _ action2: UnsafePointer<CChar>?
) {
    let a1 = action1.map { String(cString: $0) } ?? ""
    let a2 = action2.map { String(cString: $0) } ?? ""
    // Same rule as Android's VlcRuntime: take the affirmative chain.
    let answer: Int32 = a2.isEmpty ? 1 : 2
    SpikeLog.write(
        "DIALOG",
        "libvlc question title=\(title.map { String(cString: $0) } ?? "") actions=[\(a1)|\(a2)] → \(answer); text=\(text.map { String(cString: $0) }?.prefix(160) ?? "")"
    )
    libvlc_dialog_post_action(id, answer)
}

private func dialogLogin(
    _ data: UnsafeMutableRawPointer?, _ id: OpaquePointer?, _ title: UnsafePointer<CChar>?, _ text: UnsafePointer<CChar>?,
    _ user: UnsafePointer<CChar>?, _ store: Bool
) {
    SpikeLog.write("DIALOG", "libvlc login dialog dismissed")
    libvlc_dialog_dismiss(id)
}

/// Set from the `vlcLogLevel` launch argument (0 = debug); notices by default.
nonisolated(unsafe) private var vlcLogThreshold: Int32 = 2

/// libVLC's own log for the audio instance.
private func vlcLog(
    _ data: UnsafeMutableRawPointer?, _ level: Int32, _ ctx: OpaquePointer?, _ fmt: UnsafePointer<CChar>?,
    _ args: CVaListPointer?
) {
    guard level >= vlcLogThreshold, let fmt, let args else { return }
    var buffer = [CChar](repeating: 0, count: 512)
    _ = vsnprintf(&buffer, buffer.count, fmt, args)
    var module: UnsafePointer<CChar>?
    var file: UnsafePointer<CChar>?
    var line: UInt32 = 0
    libvlc_log_get_context(ctx, &module, &file, &line)
    let name = module.map { String(cString: $0) } ?? "?"
    SpikeLog.write("VLC", "L\(level) \(name): \(String(cString: buffer))")
}

private func dialogCancel(_ data: UnsafeMutableRawPointer?, _ id: OpaquePointer?) {
    libvlc_dialog_dismiss(id)
}

@MainActor
@Observable
final class AudioMonitor {
    private(set) var rooms: [AudioRoom] = []
    private(set) var running = false
    var listening = false { didSet { applyListening() } }

    private let instance: OpaquePointer?
    private var stateTask: Task<Void, Never>?
    private let engine = AVAudioEngine()
    private let format = AVAudioFormat(standardFormatWithSampleRate: 48_000, channels: 1)!

    init() {
        var args = ["--network-caching=150", "--rtsp-tcp"]
        if UserDefaults.standard.object(forKey: "vlcLogLevel") as? Int == 0 { args.append("-vv") }
        var cargs = args.map { strdup($0) }
        instance = cargs.withUnsafeMutableBufferPointer { buffer in
            buffer.baseAddress!.withMemoryRebound(to: UnsafePointer<CChar>?.self, capacity: buffer.count) {
                libvlc_new(Int32(buffer.count), $0)
            }
        }
        cargs.forEach { free($0) }
        var cbs = libvlc_dialog_cbs()
        cbs.version = 0
        cbs.pf_display_question = dialogQuestion
        cbs.pf_display_login = dialogLogin
        cbs.pf_cancel = dialogCancel
        libvlc_dialog_set_callbacks(instance, &cbs, nil)
        if let level = UserDefaults.standard.object(forKey: "vlcLogLevel") as? Int { vlcLogThreshold = Int32(level) }
        libvlc_log_set(instance, vlcLog, nil)
        SpikeLog.write("AUDIO", "libvlc instance \(instance == nil ? "FAILED" : "ok") version=\(String(cString: libvlc_get_version()))")
    }

    func start(urls: [(String, String)]) {
        guard !running else { return }
        do {
            try AVAudioSession.sharedInstance().setCategory(.playback, options: [.mixWithOthers])
            try AVAudioSession.sharedInstance().setActive(true)
        } catch {
            SpikeLog.write("AUDIO", "session FAILED: \(error)")
        }
        rooms = urls.map { AudioRoom(name: $0.0, url: $0.1) }
        for room in rooms {
            let node = Self.makeSource(format: format, ring: room.ring)
            engine.attach(node)
            engine.connect(node, to: engine.mainMixerNode, format: format)
            room.sourceNode = node
        }
        applyListening()
        do { try engine.start() } catch { SpikeLog.write("AUDIO", "engine FAILED: \(error)") }
        for room in rooms { play(room) }
        running = true
        stateTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(5))
                guard let self, self.running else { return }
                let line = self.rooms.map { room in
                    let state = room.player.map { libvlc_media_player_get_state($0).rawValue } ?? 99
                    return "\(room.name)[state=\(state) samples=\(room.samples.load(ordering: .relaxed)) cbs=\(room.callbacks.load(ordering: .relaxed)) rms=\(String(format: "%.3f", room.rms))]"
                }.joined(separator: " ")
                SpikeLog.write("AUDIO", line)
            }
        }
    }

    func stop() {
        stateTask?.cancel()
        for room in rooms {
            if let player = room.player {
                libvlc_media_player_stop_async(player)
                libvlc_media_player_release(player)
            }
            room.player = nil
            if let node = room.sourceNode { engine.detach(node) }
        }
        engine.stop()
        rooms = []
        running = false
        SpikeLog.write("AUDIO", "stopped")
    }

    private func play(_ room: AudioRoom) {
        // live555 in VLCKit has no TLS: rtsps goes through the in-app proxy.
        let location = TLSProxy.localURL(for: URL(string: room.url)!).absoluteString
        guard let media = libvlc_media_new_location(location) else {
            SpikeLog.write("AUDIO", "\(room.name): media FAILED")
            return
        }
        libvlc_media_add_option(media, ":no-video")
        let player = libvlc_media_player_new_from_media(instance, media, nil, nil)
        libvlc_media_release(media)
        libvlc_audio_set_callbacks(player, audioPlay, nil, nil, audioFlush, nil, Unmanaged.passUnretained(room).toOpaque())
        libvlc_audio_set_format(player, "FL32", 48_000, 1)
        room.player = player
        let result = libvlc_media_player_play(player)
        SpikeLog.write("AUDIO", "\(room.name): play \(room.url) via \(location) → \(result)")
    }

    private func applyListening() {
        for room in rooms { room.sourceNode?.volume = listening ? 1 : 0 }
    }

    private nonisolated static func makeSource(format: AVAudioFormat, ring: PcmRing) -> AVAudioSourceNode {
        AVAudioSourceNode(format: format) { _, _, frames, abl in
            let buffers = UnsafeMutableAudioBufferListPointer(abl)
            if let data = buffers.first?.mData?.assumingMemoryBound(to: Float.self) {
                ring.pop(into: data, count: Int(frames))
            }
            return noErr
        }
    }
}

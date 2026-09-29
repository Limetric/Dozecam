import SwiftUI
import VideoToolbox

enum Stream: String, CaseIterable, Identifiable {
    case clock, clockVT = "clock-vt", clockVTTLS = "clock-vt (rtsps)", clockVT360 = "clock-vt-360", clockLow = "clock-low", nursery, porch, clockTLS = "clock (rtsps)", nurseryTLS = "nursery (rtsps)"

    var id: String { rawValue }

    func url(host: String) -> URL {
        switch self {
        case .clock: URL(string: "rtsp://\(host):18554/clock")!
        case .clockVT: URL(string: "rtsp://\(host):18554/clock-vt")!
        case .clockVTTLS: URL(string: "rtsps://\(host):18323/clock-vt")!
        case .clockVT360: URL(string: "rtsp://\(host):18554/clock-vt-360")!
        case .clockLow: URL(string: "rtsp://\(host):18554/clock-low")!
        case .nursery: URL(string: "rtsp://\(host):18554/nursery")!
        case .porch: URL(string: "rtsp://\(host):18554/porch")!
        case .clockTLS: URL(string: "rtsps://\(host):18323/clock")!
        case .nurseryTLS: URL(string: "rtsps://\(host):18323/nursery")!
        }
    }

    /// Only the clock streams carry a readable clock.
    var clockSize: CGSize? {
        switch self {
        case .clock, .clockVT, .clockVTTLS, .clockTLS: CGSize(width: 1920, height: 1080)
        case .clockLow, .clockVT360: CGSize(width: 640, height: 360)
        default: nil
        }
    }
}

@main
struct SpikeApp: App {
    @State private var grid = VideoGrid()
    @State private var audio = AudioMonitor()

    init() {
        let h264 = VTIsHardwareDecodeSupported(kCMVideoCodecType_H264)
        let hevc = VTIsHardwareDecodeSupported(kCMVideoCodecType_HEVC)
        let av1 = VTIsHardwareDecodeSupported(kCMVideoCodecType_AV1)
        SpikeLog.write(
            "LAUNCH",
            "\(UIDevice.current.model) \(UIDevice.current.systemName) \(UIDevice.current.systemVersion); VideoToolbox hardware decode h264=\(h264) hevc=\(hevc) av1=\(av1)"
        )
    }

    var body: some Scene {
        WindowGroup {
            ContentView(grid: grid, audio: audio)
        }
    }
}

struct ContentView: View {
    let grid: VideoGrid
    @Bindable var audio: AudioMonitor
    @AppStorage("host") private var host = "192.168.0.158"
    @AppStorage("stream") private var stream = Stream.clock
    @AppStorage("tiles") private var tileCount = 1

    var body: some View {
        VStack(spacing: 0) {
            ZStack(alignment: .top) {
                let columns = Array(repeating: GridItem(.flexible(), spacing: 2), count: tileCount <= 1 ? 1 : (tileCount <= 4 ? 2 : 3))
                LazyVGrid(columns: columns, spacing: 2) {
                    ForEach(grid.tiles) { tile in
                        TileView(tile: tile).aspectRatio(16 / 9, contentMode: .fit)
                    }
                }
                TimelineView(.animation) { context in
                    Text(VideoGrid.clockFormatter.string(from: context.date))
                        .font(.system(size: 28, weight: .bold).monospacedDigit())
                        .padding(4)
                        .background(.black.opacity(0.6))
                        .foregroundStyle(.yellow)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(.black)

            Form {
                Section("Video (VLCKit 4)") {
                    TextField("Mac host", text: $host)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    Picker("Stream", selection: $stream) {
                        ForEach(Stream.allCases) { Text($0.rawValue).tag($0) }
                    }
                    Stepper("Tiles: \(tileCount)", value: $tileCount, in: 1...6)
                    HStack {
                        Button("Play") { grid.start(url: stream.url(host: host), count: tileCount) }
                            .buttonStyle(.borderedProminent)
                        Button("Stop") { grid.stop() }
                        Spacer()
                        Button(grid.measuring ? "Measuring…" : "Measure latency") {
                            if let size = stream.clockSize {
                                Task { await grid.measureLatency(host: host, streamSize: size) }
                            }
                        }
                        .disabled(grid.measuring || stream.clockSize == nil || grid.tiles.isEmpty)
                    }
                    if !grid.lastLatency.isEmpty { Text("Latency: \(grid.lastLatency)") }
                    Text(grid.lastMetrics).font(.caption.monospaced())
                }
                Section("Audio (libVLC callbacks → AVAudioEngine)") {
                    HStack {
                        Button(audio.running ? "Stop audio" : "Start audio") {
                            if audio.running {
                                audio.stop()
                            } else {
                                audio.start(urls: [
                                    ("nursery", Stream.nursery.url(host: host).absoluteString),
                                    ("nursery (rtsps)", Stream.nurseryTLS.url(host: host).absoluteString),
                                    ("porch", Stream.porch.url(host: host).absoluteString),
                                ])
                            }
                        }
                        .buttonStyle(.borderedProminent)
                        Toggle("Listen (all rooms aloud)", isOn: $audio.listening)
                    }
                    TimelineView(.periodic(from: .now, by: 0.1)) { _ in
                        VStack(alignment: .leading) {
                            ForEach(audio.rooms, id: \.name) { room in
                                HStack {
                                    Text(room.name).frame(width: 130, alignment: .leading)
                                    ProgressView(value: Double(min(room.rms / 0.5, 1)))
                                    Text(String(format: "%.3f", room.rms)).monospacedDigit()
                                    Text("\(room.samples.load(ordering: .relaxed) / 48_000)s").monospacedDigit()
                                }
                                .font(.caption)
                            }
                        }
                    }
                }
                Section("Log") {
                    ShareLink(item: SpikeLog.shared.url)
                    TimelineView(.periodic(from: .now, by: 2)) { _ in
                        VStack(alignment: .leading, spacing: 2) {
                            ForEach(Array(SpikeLog.shared.tail(60).enumerated()), id: \.offset) { _, line in
                                Text(line).font(.system(size: 10).monospaced())
                            }
                        }
                    }
                }
            }
            .frame(maxHeight: .infinity)
        }
        .task {
            // Launch arguments for agent-driven runs, e.g.
            // `-stream clock -tiles 1 -autoplay YES -automeasure YES -autoaudio YES`.
            let defaults = UserDefaults.standard
            if defaults.bool(forKey: "autoplay") {
                // `-path cam-vt` plays any testbed path instead of the picked stream.
                // `-url http://…` plays any URL (e.g. the AV1 files).
                let url =
                    defaults.string(forKey: "url").flatMap(URL.init(string:))
                    ?? defaults.string(forKey: "path").flatMap { URL(string: "rtsp://\(host):18554/\($0)") }
                grid.start(url: url ?? stream.url(host: host), count: tileCount)
            }
            if defaults.bool(forKey: "autoaudio") {
                audio.start(urls: [
                    ("nursery", Stream.nursery.url(host: host).absoluteString),
                    ("nursery (rtsps)", Stream.nurseryTLS.url(host: host).absoluteString),
                    ("porch", Stream.porch.url(host: host).absoluteString),
                ])
            }
            if defaults.bool(forKey: "automeasure"), let size = stream.clockSize {
                try? await Task.sleep(for: .seconds(8))
                await grid.measureLatency(host: host, streamSize: size)
            }
        }
    }
}

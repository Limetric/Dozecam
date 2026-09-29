import CoreGraphics
import Foundation
import ImageIO
import SwiftUI
import UIKit
import VLCKit

/// Answers libVLC's question dialogs (the rtsps self-signed certificate
/// prompts) the way Android's VlcRuntime does: take the affirmative chain.
final class AutoAnswer: NSObject, VLCCustomDialogRendererProtocol {
    weak var provider: VLCDialogProvider?

    func showError(withTitle error: String, message: String) {
        SpikeLog.write("DIALOG", "vlckit error: \(error) \(message)")
    }

    func showLogin(
        withTitle title: String, message: String, defaultUsername username: String?, askingForStorage: Bool,
        withReference reference: NSValue
    ) {
        SpikeLog.write("DIALOG", "vlckit login dismissed: \(title)")
        provider?.dismissDialog(withReference: reference)
    }

    func showQuestion(
        withTitle title: String, message: String, type questionType: VLCDialogQuestionType, cancel cancelString: String?,
        action1String: String?, action2String: String?, withReference reference: NSValue
    ) {
        let answer: Int32 = (action2String ?? "").isEmpty ? 1 : 2
        SpikeLog.write(
            "DIALOG",
            "vlckit question title=\(title) actions=[\(action1String ?? "")|\(action2String ?? "")] → \(answer); text=\(message.prefix(160))"
        )
        provider?.postAction(answer, forDialogReference: reference)
    }

    func showProgress(
        withTitle title: String, message: String, isIndeterminate: Bool, position: Float, cancel cancelString: String?,
        withReference reference: NSValue
    ) {}

    func updateProgress(withReference reference: NSValue, message: String?, position: Float) {}

    func cancelDialog(withReference reference: NSValue) {}
}

/// VLCKit's log for the video library (warnings and up, or everything with
/// the `vlcLogLevel 0` launch argument), filtered of per-frame noise.
final class VideoLogger: NSObject, VLCLogging {
    var level: VLCLogLevel =
        UserDefaults.standard.object(forKey: "vlcLogLevel") as? Int == 0 ? .debug : .warning

    func handleMessage(_ message: String, logLevel level: VLCLogLevel, context: VLCLogContext?) {
        SpikeLog.write("VLCV", "L\(level.rawValue) \(context?.module ?? "?"): \(message.prefix(300))")
    }
}

@MainActor
final class Tile: Identifiable {
    let id = UUID()
    let view = UIView()
    let player: VLCMediaPlayer
    let url: URL
    var lastStats: (decoded: UInt64, displayed: UInt64, lost: UInt64, late: UInt64) = (0, 0, 0, 0)

    init(library: VLCLibrary, url: URL) {
        self.url = url
        player = VLCMediaPlayer(library: library)
        view.backgroundColor = .black
        player.drawable = view
        player.media = VLCMedia(url: TLSProxy.localURL(for: url))
    }
}

@MainActor
@Observable
final class VideoGrid {
    private(set) var tiles: [Tile] = []
    private(set) var measuring = false
    private(set) var lastLatency = ""
    private(set) var lastMetrics = ""

    // The Android flags (VlcRuntime): sub-second latency on a trusted LAN.
    private let library = VLCLibrary(options: ["--network-caching=150", "--rtsp-tcp", "--drop-late-frames", "--skip-frames"])
    private let dialogs: VLCDialogProvider?
    private let answer = AutoAnswer()
    private var metricsTask: Task<Void, Never>?
    private var lastCPU = CPUSample.now()

    private let logger = VideoLogger()

    init() {
        library.loggers = [logger]
        dialogs = VLCDialogProvider(library: library, customUI: true)
        dialogs?.customRenderer = answer
        answer.provider = dialogs
        metricsTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(5))
                self?.logMetrics()
            }
        }
    }

    func start(url: URL, count: Int) {
        stop()
        tiles = (0..<count).map { _ in Tile(library: library, url: url) }
        for tile in tiles { tile.player.play() }
        SpikeLog.write("VIDEO", "start \(count) × \(url.absoluteString)")
    }

    func stop() {
        for tile in tiles { tile.player.stop() }
        if !tiles.isEmpty { SpikeLog.write("VIDEO", "stop \(tiles.count) tiles") }
        tiles = []
    }

    // MARK: metrics

    private func logMetrics() {
        let cpu = CPUSample.now()
        let cpuPct = cpu.percent(since: lastCPU)
        lastCPU = cpu
        var parts = [
            "cpu=\(Int(cpuPct))%",
            "mem=\(spike_phys_footprint() / 1_048_576)MB",
            "therm=\(ProcessInfo.processInfo.thermalState.rawValue)",
            "tiles=\(tiles.count)",
        ]
        for (i, tile) in tiles.enumerated() {
            guard let stats = tile.player.media?.statistics else { continue }
            let decoded = stats.decodedVideo - tile.lastStats.decoded
            let shown = stats.displayedPictures - tile.lastStats.displayed
            let lost = stats.lostPictures - tile.lastStats.lost
            let late = stats.latePictures - tile.lastStats.late
            tile.lastStats = (stats.decodedVideo, stats.displayedPictures, stats.lostPictures, stats.latePictures)
            parts.append(
                "t\(i)[\(Self.name(tile.player.state)) fps=\(String(format: "%.1f", Double(shown) / 5)) dec=\(decoded) lost=\(lost) late=\(late) \(Int(stats.inputBitrate * 8000))kb/s]"
            )
        }
        lastMetrics = parts.joined(separator: " ")
        SpikeLog.write("METRICS", lastMetrics)
    }

    static func name(_ state: VLCMediaPlayerState) -> String {
        switch state {
        case .nothingSpecial: "idle"
        case .opening: "opening"
        case .playing: "playing"
        case .paused: "paused"
        case .stopped: "stopped"
        case .stopping: "stopping"
        case .error: "ERROR"
        @unknown default: "?"
        }
    }

    // MARK: latency

    /// Snapshots tile 0 `rounds` times, reads the clock the testbed burned
    /// into the frame, and compares it with this device's clock corrected by
    /// the Mac's offset (from the spike's time server).
    func measureLatency(host: String, streamSize: CGSize, rounds: Int = 10) async {
        guard let tile = tiles.first, !measuring else { return }
        measuring = true
        defer { measuring = false }
        let offset = await ClockOffset.measure(host: host)
        SpikeLog.write("LATENCY", "clock offset mac−device=\(offset.map { String(format: "%.1fms", $0 * 1000) } ?? "unknown")")
        var results: [Double] = []
        for round in 0..<rounds {
            let path = NSTemporaryDirectory() + "snap-\(round).png"
            try? FileManager.default.removeItem(atPath: path)
            let requested = Date()
            tile.player.saveVideoSnapshot(at: path, withWidth: 480, andHeight: 270)
            var waited = 0
            while !FileManager.default.fileExists(atPath: path) && waited < 100 {
                try? await Task.sleep(for: .milliseconds(10))
                waited += 1
            }
            let written = Date()
            try? await Task.sleep(for: .milliseconds(50)) // let the PNG finish writing
            guard let frameClock = SevenSegment.readClock(path: path, streamSize: streamSize, on: requested) else {
                SpikeLog.write("LATENCY", "round \(round): could not read the clock (waited \(waited * 10)ms)")
                continue
            }
            let deviceNow = requested.addingTimeInterval(offset ?? 0)
            let latency = deviceNow.timeIntervalSince(frameClock)
            results.append(latency)
            SpikeLog.write(
                "LATENCY",
                "round \(round): frame=\(Self.clockFormatter.string(from: frameClock)) latency=\(Int(latency * 1000))ms (snapshot took \(Int(written.timeIntervalSince(requested) * 1000))ms)"
            )
            try? await Task.sleep(for: .milliseconds(700))
        }
        if !results.isEmpty {
            let sorted = results.sorted()
            lastLatency =
                "median \(Int(sorted[sorted.count / 2] * 1000))ms, min \(Int(sorted.first! * 1000)), max \(Int(sorted.last! * 1000)) (n=\(sorted.count))"
            SpikeLog.write("LATENCY", "\(tile.url.absoluteString): \(lastLatency)")
        }
    }

    static let clockFormatter: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "HH:mm:ss.SSS"
        return f
    }()
}

struct CPUSample {
    let cpu: Double
    let wall: Double

    static func now() -> CPUSample {
        var usage = rusage()
        getrusage(RUSAGE_SELF, &usage)
        let cpu =
            Double(usage.ru_utime.tv_sec) + Double(usage.ru_utime.tv_usec) / 1e6 + Double(usage.ru_stime.tv_sec)
            + Double(usage.ru_stime.tv_usec) / 1e6
        return CPUSample(cpu: cpu, wall: ProcessInfo.processInfo.systemUptime)
    }

    /// Percent of one core (so 250% = 2.5 cores busy).
    func percent(since earlier: CPUSample) -> Double {
        let wall = self.wall - earlier.wall
        return wall > 0 ? (cpu - earlier.cpu) / wall * 100 : 0
    }
}

enum ClockOffset {
    /// Mac clock minus device clock, NTP-style over one HTTP round trip to
    /// the time server in tools/streams.sh. Best of five.
    static func measure(host: String) async -> Double? {
        guard let url = URL(string: "http://\(host):18580/") else { return nil }
        var best: (rtt: Double, offset: Double)?
        for _ in 0..<5 {
            let t0 = Date().timeIntervalSince1970
            guard let (data, _) = try? await URLSession.shared.data(from: url),
                let server = Double(String(decoding: data, as: UTF8.self).trimmingCharacters(in: .whitespacesAndNewlines))
            else { continue }
            let t1 = Date().timeIntervalSince1970
            let rtt = t1 - t0
            let offset = server - (t0 + t1) / 2
            if best == nil || rtt < best!.rtt { best = (rtt, offset) }
        }
        if let best { SpikeLog.write("LATENCY", "time server rtt=\(Int(best.rtt * 1000))ms") }
        return best?.offset
    }
}

/// Reads the seven-segment clock that tools/clock.py draws.
enum SevenSegment {
    static let segments: [Character: (Double, Double, Double, Double)] = [
        "a": (1, 0, 4, 1), "b": (5, 1, 1, 4), "c": (5, 5, 1, 4), "d": (1, 9, 4, 1),
        "e": (0, 5, 1, 4), "f": (0, 1, 1, 4), "g": (1, 4.5, 4, 1),
    ]
    static let digits: [String: Character] = [
        "abcdef": "0", "bc": "1", "abdeg": "2", "abcdg": "3", "bcfg": "4",
        "acdfg": "5", "acdefg": "6", "abc": "7", "abcdefg": "8", "abcdfg": "9",
    ]

    static func readClock(path: String, streamSize: CGSize, on day: Date) -> Date? {
        guard let source = CGImageSourceCreateWithURL(URL(fileURLWithPath: path) as CFURL, nil),
            let image = CGImageSourceCreateImageAtIndex(source, 0, nil)
        else { return nil }
        let w = image.width, h = image.height
        var pixels = [UInt8](repeating: 0, count: w * h)
        guard
            let ctx = CGContext(
                data: &pixels, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w,
                space: CGColorSpaceCreateDeviceGray(), bitmapInfo: CGImageAlphaInfo.none.rawValue)
        else { return nil }
        ctx.draw(image, in: CGRect(x: 0, y: 0, width: w, height: h))

        // Layout from clock.py.
        let W = Int(streamSize.width), H = Int(streamSize.height)
        let unit = Double(max(2, min(W / (12 * 8), H / 14)))
        let x0 = Double(W - 12 * 8 * Int(unit)) / 2
        let y0 = Double((H - 10 * Int(unit)) / 2)
        let sx = Double(w) / Double(W), sy = Double(h) / Double(H)

        // Bitmap rows are top-down; fall back to bottom-up if that misreads.
        func read(flipped: Bool) -> String? {
            var text = ""
            for i in [0, 1, 3, 4, 6, 7, 9, 10, 11] {
                let cx = x0 + Double(i * 8) * unit
                var lit = ""
                for seg in "abcdefg" {
                    let (rx, ry, rw, rh) = segments[seg]!
                    let px = Int((cx + (rx + rw / 2) * unit) * sx)
                    let py = Int((y0 + (ry + rh / 2) * unit) * sy)
                    guard px >= 0, px < w, py >= 0, py < h else { return nil }
                    let row = flipped ? h - 1 - py : py
                    if pixels[row * w + px] > 128 { lit.append(seg) }
                }
                guard let digit = digits[lit] else { return nil }
                text.append(digit)
            }
            return text
        }
        guard let text = read(flipped: false) ?? read(flipped: true) else { return nil }
        let chars = Array(text)
        guard let hh = Int(String(chars[0...1])), let mm = Int(String(chars[2...3])), let ss = Int(String(chars[4...5])),
            let ms = Int(String(chars[6...8]))
        else { return nil }
        var parts = Calendar.current.dateComponents([.year, .month, .day], from: day)
        parts.hour = hh
        parts.minute = mm
        parts.second = ss
        parts.nanosecond = ms * 1_000_000
        return Calendar.current.date(from: parts)
    }
}

struct TileView: UIViewRepresentable {
    let tile: Tile
    func makeUIView(context: Context) -> UIView { tile.view }
    func updateUIView(_ uiView: UIView, context: Context) {}
}

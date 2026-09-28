import Foundation

/// Append-only log in Documents/spike.log (visible in the Files app and
/// retrievable with `xcrun devicectl device copy from`). Every line is written
/// and flushed immediately so a killed process loses nothing.
final class SpikeLog: @unchecked Sendable {
    static let shared = SpikeLog()

    let url: URL
    private let lock = NSLock()
    private let handle: FileHandle?
    private let formatter: DateFormatter

    private init() {
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        url = docs.appendingPathComponent("spike.log")
        if !FileManager.default.fileExists(atPath: url.path) {
            FileManager.default.createFile(atPath: url.path, contents: nil)
        }
        handle = try? FileHandle(forWritingTo: url)
        _ = try? handle?.seekToEnd()
        formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyy-MM-dd HH:mm:ss.SSS"
    }

    static func write(_ tag: String, _ message: String) {
        shared.append(tag, message)
    }

    private func append(_ tag: String, _ message: String) {
        lock.lock()
        defer { lock.unlock() }
        let line = "\(formatter.string(from: Date())) [\(tag)] \(message)\n"
        try? handle?.write(contentsOf: Data(line.utf8))
        try? handle?.synchronize()
    }

    func tail(_ lines: Int) -> [String] {
        lock.lock()
        defer { lock.unlock() }
        guard let text = try? String(contentsOf: url, encoding: .utf8) else { return [] }
        return Array(text.split(separator: "\n").suffix(lines).map(String.init).reversed())
    }

    func clear() {
        lock.lock()
        defer { lock.unlock() }
        try? handle?.truncate(atOffset: 0)
    }
}

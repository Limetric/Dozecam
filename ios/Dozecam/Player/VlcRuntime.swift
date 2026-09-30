import Foundation
import VLCKit
import os

/// The process-wide libVLC instance, the counterpart of Android's
/// `VlcRuntime`. A grid plays several cameras at once and each needs its own
/// `VLCMediaPlayer`, but the library is expensive to build and is designed to
/// be shared (VLCKit supports only one per process anyway). Built lazily on
/// first use, so a launch that never plays a camera never loads libVLC.
@MainActor
final class VlcRuntime {
    static let shared = VlcRuntime()

    /// The Android flags: sub-second latency on a trusted LAN (#59).
    /// RTSP over TCP avoids UDP reordering artifacts on busy Wi-Fi.
    nonisolated static let options = [
        "--network-caching=\(networkCachingMs)",
        "--rtsp-tcp",
        "--drop-late-frames",
        "--skip-frames",
    ]
    nonisolated static let networkCachingMs = 150

    let library: VLCLibrary
    private let dialogs: VLCDialogProvider?
    /// The provider holds its renderer weakly.
    private let renderer: VlcDialogAnswerer
    private let undecodable: UndecodableCodecs
    private let logger = VlcLogger()

    private init() {
        library = VLCLibrary(options: Self.options)
        library.loggers = [logger]
        let undecodable = UndecodableCodecs()
        self.undecodable = undecodable
        renderer = VlcDialogAnswerer(onError: Self.errorSink(undecodable))
        dialogs = VLCDialogProvider(library: library, customUI: true)
        dialogs?.customRenderer = renderer
        renderer.provider = dialogs
    }

    /// The codecs this device has been seen to have no decoder for. The
    /// answer is the device's, not the stream's, so one camera's failure
    /// holds for every camera sending the same codec.
    var undecodableCodecs: UndecodableCodecs { undecodable }

    /// Built in a nonisolated function: the renderer runs it wherever VLCKit
    /// calls it from, and a closure created in MainActor code would trap
    /// there (#58).
    private nonisolated static func errorSink(_ undecodable: UndecodableCodecs) -> @Sendable (String, String) -> Void {
        { title, message in
            VlcRuntime.log.notice("libVLC error dialog: \(title, privacy: .public): \(message, privacy: .public)")
            guard let codec = UndecodableCodecs.codec(inDialogTitle: title, message: message) else { return }
            undecodable.insert(codec)
        }
    }

    nonisolated static let log = Logger(subsystem: "app.dozecam", category: "player")
}

/// libVLC's own log, into the unified log (category `libvlc`): warnings and
/// errors, or everything when a debug build is launched with
/// `-vlcLogLevel 0`. Called on libVLC's threads.
final class VlcLogger: NSObject, VLCLogging, @unchecked Sendable {
    private static let log = Logger(subsystem: "app.dozecam", category: "libvlc")

    var level: VLCLogLevel

    override init() {
        #if DEBUG
            level = UserDefaults.standard.object(forKey: "vlcLogLevel") as? Int == 0 ? .debug : .warning
        #else
            level = .warning
        #endif
    }

    func handleMessage(_ message: String, logLevel level: VLCLogLevel, context: VLCLogContext?) {
        let module = context?.module ?? "?"
        switch level {
        case .error: Self.log.error("\(module, privacy: .public): \(message, privacy: .public)")
        case .warning: Self.log.warning("\(module, privacy: .public): \(message, privacy: .public)")
        default: Self.log.debug("\(module, privacy: .public): \(message, privacy: .public)")
        }
    }
}

/// Codecs libVLC could not find a decoder for, reported through its error
/// dialogs. libVLC raises those on the shared instance, not on a player, so
/// a player learns of it by matching its own video track's codec against
/// this set (see `VlcPlayerCore`).
final class UndecodableCodecs: Sendable {
    /// A codec VLC could decode nothing of, by FourCC (as VLC prints it, for
    /// example `av01`), or `unidentified` when VLC could not even name it.
    private let codecs = OSAllocatedUnfairLock<Set<String>>(initialState: [])

    static let unidentified = "unidentified"

    func contains(_ fourcc: String) -> Bool { codecs.withLock { $0.contains(fourcc) } }

    func insert(_ codec: String) {
        codecs.withLock { _ = $0.insert(codec) }
    }

    /// libVLC's decoder raises "Codec not supported" with the message
    /// `VLC could not decode the format "av01" (AOMedia's AV1 Video)`, and
    /// "Unidentified codec" when it cannot tell what the stream is.
    static func codec(inDialogTitle title: String, message: String) -> String? {
        if title == "Unidentified codec" { return unidentified }
        guard title == "Codec not supported", let open = message.firstIndex(of: "\"") else { return nil }
        let rest = message[message.index(after: open)...]
        guard let close = rest.firstIndex(of: "\"") else { return nil }
        let fourcc = rest[..<close].trimmingCharacters(in: .whitespaces)
        return fourcc.isEmpty ? nil : fourcc
    }
}

/// Answers libVLC's dialogs the way Android's `VlcRuntime` does: question
/// dialogs (an `rtsps://` server's self-signed certificate) take the
/// affirmative chain, and logins are dismissed because credentials ride the
/// stream URL. VLCKit 4.0.0a24's live555 has no TLS, so no certificate
/// question is raised today (#59); the answer is kept for parity, and for the
/// day it does.
///
/// Nonisolated: VLCKit calls it on the main thread today, but nothing in its
/// contract says so.
final class VlcDialogAnswerer: NSObject, VLCCustomDialogRendererProtocol, @unchecked Sendable {
    /// Weak, as the provider is weak to us; both are owned by `VlcRuntime`.
    weak var provider: VLCDialogProvider?
    private let onError: @Sendable (String, String) -> Void

    init(onError: @escaping @Sendable (String, String) -> Void) {
        self.onError = onError
    }

    /// The button a question dialog is answered with: the second when there
    /// is one ("View certificate", then "Accept permanently"), else the first.
    static func answer(action2: String?) -> Int32 { (action2 ?? "").isEmpty ? 1 : 2 }

    func showError(withTitle error: String, message: String) {
        onError(error, message)
    }

    func showLogin(
        withTitle title: String, message: String, defaultUsername username: String?, askingForStorage: Bool,
        withReference reference: NSValue
    ) {
        provider?.dismissDialog(withReference: reference)
    }

    func showQuestion(
        withTitle title: String, message: String, type questionType: VLCDialogQuestionType,
        cancel cancelString: String?,
        action1String: String?, action2String: String?, withReference reference: NSValue
    ) {
        provider?.postAction(Self.answer(action2: action2String), forDialogReference: reference)
    }

    func showProgress(
        withTitle title: String, message: String, isIndeterminate: Bool, position: Float, cancel cancelString: String?,
        withReference reference: NSValue
    ) {}

    func updateProgress(withReference reference: NSValue, message: String?, position: Float) {}

    func cancelDialog(withReference reference: NSValue) {}
}

import AVFAudio

/// The microphone, wanted for one thing: talking back to a camera. The
/// counterpart of Android's `MicrophonePermission`.
///
/// It gates a feature rather than the app, so it is asked for at the moment
/// somebody first reaches for talk-back and never at startup. A baby monitor
/// that opens by asking for the microphone has some explaining to do; one that
/// asks when you hold a button marked "talk" does not.
enum MicrophonePermission {
    enum Status: Equatable, Sendable {
        /// Never asked: `request()` will show the system prompt.
        case undetermined
        case granted
        /// Refused; only the Settings app can grant it now.
        case denied
    }

    static var status: Status { Status(AVAudioApplication.shared.recordPermission) }

    static var isGranted: Bool { status == .granted }

    /// Shows the system prompt if the user has not answered it yet, and
    /// returns whether the microphone may be used. The async form, so no
    /// completion handler is written here to inherit a caller's MainActor
    /// isolation and trap on the queue the system calls it on (#58).
    static func request() async -> Bool {
        await AVAudioApplication.requestRecordPermission()
    }
}

extension MicrophonePermission.Status {
    init(_ permission: AVAudioApplication.recordPermission) {
        switch permission {
        case .granted: self = .granted
        case .denied: self = .denied
        case .undetermined: self = .undetermined
        @unknown default: self = .undetermined
        }
    }
}

/// One monitored room as the pure rules see it: the counterpart of Android's
/// `CameraMonitorState`, the input `FailureLedger` judges cameras by and
/// `MonitoringStatus` words the status line from. A plain value, so the rules
/// can be driven from tests and fixtures; `MonitoringService` builds it from
/// its `CameraAudioMonitor`s, the detectors' phases and the current names.
///
/// Android's `lastAudioAtMs` is left out: it feeds the bedtime readiness check,
/// which none of the rules that read this type need.
struct CameraMonitorState: Equatable, Sendable {
    let cameraId: String
    /// The camera's current name, so a failure or an alert follows a rename.
    var name: String
    /// The latest level decoded on the *current* connection, or nil before any
    /// has been. Nil and 0 mean opposite things: 0 is a measured silence, nil
    /// is "nobody has heard this stream yet", and a meter shown for the latter
    /// would be lying (shared/spec/alerts-and-sound-modes.md#the-detector).
    var level: Float?
    var phase: SoundDetector.Phase = .armed
    var connection: ConnectionState = .connecting

    var isLive: Bool { connection == .live }
}

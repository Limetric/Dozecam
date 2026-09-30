/// One line for the whole nursery: the port of Android's `MonitoringStatus`
/// (shared/spec/monitoring-lifecycle.md#staying-alive). On Android it is the
/// ongoing notification's text; iOS has no ongoing card, so the viewer shows
/// it. A failure past its grace period outranks everything, then a triggered
/// camera, then the worst connection state wins: a status that said
/// "listening" while a camera was actually offline would be a lie in the one
/// direction that matters.
///
/// Pure: facts in, text and level out. The English is Android's
/// (`monitoring_status_*` in its `strings.xml`), word for word.
enum MonitoringStatus {
    /// The status line, plus, on the healthy listening branch only, the live
    /// level that proves it.
    struct Status: Equatable, Sendable {
        var text: String
        var level: Float?
    }

    static func of(
        anyMonitors: Bool,
        states: [CameraMonitorState],
        /// The cameras that should be heard: enabled, and not paused.
        enabledCount: Int,
        /// Enabled cameras paused from the viewer, and so heard by nobody.
        pausedCount: Int = 0,
        /// The cameras listen mode is playing out of the speaker, if any.
        aloudCameraIds: Set<String> = [],
        /// Whether a loud room reaches anyone at all.
        alertsEnabled: Bool = true,
        /// Every way the monitor is currently failing, oldest first.
        failures: [MonitoringFailure] = [],
        /// The last failure to have cleared, for the note that it happened.
        recovered: RecoveredFailure? = nil,
        wording: FailureWording = .system
    ) -> Status {
        var status: Status
        if let failing = failing(failures, wording: wording) {
            status = disclosePaused(pausedCount, failing)
        } else {
            status = listening(
                anyMonitors: anyMonitors, states: states, enabledCount: enabledCount, pausedCount: pausedCount)
            // Every room paused is already the whole of the listening line;
            // saying it twice says nothing new.
            if anyMonitors { status = disclosePaused(pausedCount, status) }
        }
        status = noteRecovered(recovered, status, wording: wording)
        status = disclose(states: states, aloudCameraIds: aloudCameraIds, status)
        return discloseAlertsOff(alertsEnabled, status)
    }

    /// A failure past its grace period outranks every other line: the
    /// "reconnecting" and "offline" lines describe a monitor that expects to
    /// be back, and this is the line for one that has been gone too long to
    /// say so. It takes precedence over a triggered camera too: that room has
    /// its own card and its own alarm, and the ongoing line is where a failure
    /// is kept for as long as it lasts.
    private static func failing(_ failures: [MonitoringFailure], wording: FailureWording) -> Status? {
        guard let first = failures.first else { return nil }
        let line = "\(wording.title(first.reason)) since \(wording.time(first.sinceMs))"
        return Status(text: failures.count > 1 ? "\(line) · \(failures.count - 1) more" : line, level: nil)
    }

    /// A paused room is one nobody is listening to, and the line that says
    /// "listening to one camera" has to own up to the other: at a glance, one
    /// room heard reads as the whole house heard. Counted rather than named.
    private static func disclosePaused(_ pausedCount: Int, _ status: Status) -> Status {
        guard pausedCount > 0 else { return status }
        return Status(text: "\(status.text) · \(pausedCount) paused", level: status.level)
    }

    /// A failure that has cleared leaves a note behind it. Honesty is only
    /// useful to someone looking, and nobody was at 3 am, so the morning's
    /// glance has to be able to learn that the nursery was unreachable for
    /// twenty minutes, even though it is back.
    private static func noteRecovered(_ recovered: RecoveredFailure?, _ status: Status, wording: FailureWording)
        -> Status
    {
        guard let recovered else { return status }
        let note = "Earlier: \(wording.title(recovered.reason)), cleared \(wording.time(recovered.clearedAtMs))"
        return Status(text: "\(status.text) · \(note)", level: status.level)
    }

    /// A monitor that will not wake anyone has to say so where it is seen:
    /// "watching for sound" over a phone that has been asked to keep quiet
    /// about it is the sort of reassurance this app exists to refuse.
    private static func discloseAlertsOff(_ alertsEnabled: Bool, _ status: Status) -> Status {
        alertsEnabled ? status : Status(text: "Alerts off · \(status.text)", level: status.level)
    }

    /// A phone broadcasting a bedroom says so, in front of whatever else it has
    /// to report. Not instead of it: an offline camera is still the more urgent
    /// half of the line.
    ///
    /// Reads the cameras that are *actually* audible rather than the switch, so
    /// this can only ever understate what the phone is doing. One room is
    /// named; more than one is counted, because a list of bedrooms cut off
    /// mid-word would say less than either.
    private static func disclose(states: [CameraMonitorState], aloudCameraIds: Set<String>, _ status: Status)
        -> Status
    {
        let aloud = states.filter { aloudCameraIds.contains($0.cameraId) }
        let what: String
        switch aloud.count {
        case 0: return status
        case 1: what = aloud[0].name
        default: what = "\(aloud.count) rooms"
        }
        return Status(text: "\(what) aloud · \(status.text)", level: status.level)
    }

    private static func listening(anyMonitors: Bool, states: [CameraMonitorState], enabledCount: Int, pausedCount: Int)
        -> Status
    {
        guard anyMonitors else {
            // Every room set aside is a choice, not a camera missing, and says
            // so: "no camera is switched on" would send someone to settings to
            // switch on a camera that is on.
            let allPaused = pausedCount > 0 && enabledCount == 0
            return Status(text: allPaused ? "Every camera is paused" : "No camera is switched on")
        }
        if states.isEmpty { return Status(text: "Connecting to the cameras…") }
        if let triggered = states.first(where: { $0.phase == .triggered }) {
            return Status(text: "Sound detected — \(triggered.name)")
        }
        if states.contains(where: { $0.connection == .offline }) {
            return Status(text: "Offline — waiting for network")
        }
        let reconnecting = states.filter {
            if case .reconnecting = $0.connection { true } else { false }
        }
        if !reconnecting.isEmpty {
            return Status(text: "Reconnecting to \(cameras(reconnecting.count))…")
        }
        if states.contains(where: { $0.connection == .connecting }) {
            return Status(text: "Connecting to the cameras…")
        }
        var text = "Monitoring \(cameras(states.count))"
        // A camera that is enabled but not monitorable is silently absent from
        // the listening count; say so rather than overstate coverage.
        if enabledCount > states.count { text += " · \(enabledCount - states.count) not monitorable" }
        // Only the healthy listening line carries a level: the loudest camera,
        // decoded moments ago, the one state whose steadiness could otherwise
        // be mistaken for staleness.
        return Status(text: text, level: states.compactMap(\.level).max())
    }

    private static func cameras(_ count: Int) -> String {
        count == 1 ? "1 camera" : "\(count) cameras"
    }
}

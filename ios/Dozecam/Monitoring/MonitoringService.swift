import Foundation
import Observation
import Synchronization
import os

/// The monitor that keeps listening with the screen off: the counterpart of
/// Android's `MonitoringService` and the writer half of its
/// `MonitoringState`. One per app, owned by `AppModel`, so it outlives the
/// viewer that arms it (shared/spec/monitoring-lifecycle.md).
///
/// It runs one `CameraAudioMonitor` per monitored camera, feeds each room's
/// levels to that room's `SoundDetector`, and decides what comes out of the
/// one speaker: the rooms listen mode plays, or the viewer's audible
/// cameras while the viewer is making noise itself. The viewer plays its
/// sound through this mix rather than its own video players: libVLC's iOS
/// audio output takes the app's audio session over without mixing, which
/// would end monitoring the first time the app went to the background (#58).
///
/// Alerts are #68's: until then a trigger is logged, with the listen-mode
/// decisions it would be raised with.
@MainActor
@Observable
final class MonitoringService {
    /// Whether the monitor is armed. There is no switch: the viewer arms it,
    /// and only Exit ends it (shared/spec/monitoring-lifecycle.md).
    private(set) var isRunning = false
    /// Set by Exit until the viewer next opens, so nothing re-arms on the
    /// way out.
    private(set) var exitRequested = false
    /// Rooms set aside for tonight: no picture, no sound, no detector. In
    /// memory only, so exiting (or the process dying) brings every room back.
    private(set) var pausedIds: Set<String> = []
    /// The monitor of every camera listened to, by camera id.
    private(set) var monitors: [String: CameraAudioMonitor] = [:]
    /// Each monitored room's detector phase.
    private(set) var phases: [String: SoundDetector.Phase] = [:]
    /// The rooms listen mode is playing: `ListenTarget.of`. Everything that
    /// says a room is aloud reads this, never the setting.
    private(set) var listeningCameraIds: Set<String> = []
    /// The most recent trigger, until #68 turns it into an alert.
    private(set) var lastTrigger: Trigger?

    struct Trigger: Equatable {
        let cameraId: String
        let name: String
        let at: Date
    }

    let speaker: Speaker

    @ObservationIgnored private let dependencies: AppDependencies
    @ObservationIgnored private let makePlayer: @MainActor (_ cameraId: String, _ sink: SpeakerSink) -> any AudioPlayer
    @ObservationIgnored private let scheduler: any MonotonicScheduler
    @ObservationIgnored private let watchdogConfig: PlaybackWatchdog.Config
    @ObservationIgnored private let wallClock: () -> Date
    @ObservationIgnored private var cameras: [Camera]
    @ObservationIgnored private var names: [String: String] = [:]
    @ObservationIgnored private var runningCameras: [String: Camera] = [:]
    @ObservationIgnored private var runningTransports: [String: [StreamSource]] = [:]
    @ObservationIgnored private var detectors: [String: SoundDetector] = [:]
    @ObservationIgnored private var detectorSettings: DetectorSettings
    @ObservationIgnored private var settings: AppSettings
    @ObservationIgnored private var online = true
    /// The viewer's audible cameras while it is making noise; nil when it is
    /// not (off screen, sound off, or nothing unpaused).
    @ObservationIgnored private var viewerAloud: Set<String>?
    /// The rooms heard at the last look, to find the ones that dropped out.
    @ObservationIgnored private var heardBefore: Set<String> = []
    @ObservationIgnored private var following: Task<Void, Never>?

    private static let log = Logger(subsystem: "app.dozecam", category: "monitor")

    init(
        dependencies: AppDependencies,
        speaker: Speaker,
        makePlayer: @escaping @MainActor (_ cameraId: String, _ sink: SpeakerSink) -> any AudioPlayer,
        scheduler: any MonotonicScheduler = ContinuousScheduler.shared,
        watchdogConfig: PlaybackWatchdog.Config = .init(),
        wallClock: @escaping () -> Date = Date.init
    ) {
        self.dependencies = dependencies
        self.speaker = speaker
        self.makePlayer = makePlayer
        self.scheduler = scheduler
        self.watchdogConfig = watchdogConfig
        self.wallClock = wallClock
        cameras = dependencies.cameras.enabledCameras
        settings = dependencies.appSettings.settings
        detectorSettings = dependencies.detectorSettings.settings
    }

    // MARK: - What the screens read

    /// Enabled, unpaused cameras with a way to hear them.
    var monitorableCount: Int {
        MonitorTransports.monitorable(cameras, pausedIds: pausedIds, consoleHost: consoleHost).count
    }

    /// Rooms live with a decoded buffer on their current connection.
    var audibleCameraIds: Set<String> { Set(monitors.filter { $0.value.isAudible }.keys) }

    /// The loudest monitored room's level, or nil while no room's is known:
    /// the detection settings' meter.
    var peakLevel: Float? { monitors.values.compactMap(\.level).max() }

    /// What actually comes out of the speaker: the viewer's rooms while it
    /// plays, otherwise listen mode's.
    var aloudCameraIds: Set<String> { speaker.aloudCameraIds }

    // MARK: - Lifecycle

    /// Arms unless the rules say not to: nothing to listen to, already
    /// running, an exit in progress, or local-network access denied
    /// (shared/spec/monitoring-lifecycle.md, "Always on"). No prompts.
    ///
    /// Local-network access that was never recorded counts as granted: iOS
    /// cannot be asked, only probed, and the monitor connecting is the probe.
    @discardableResult
    func arm() -> Bool {
        cameras = dependencies.cameras.enabledCameras
        guard
            Arming.shouldArmMonitoring(
                monitorableCount: monitorableCount, running: isRunning, exitRequested: exitRequested,
                localNetworkGranted: dependencies.localNetwork.status != .denied)
        else { return false }
        isRunning = true
        settings = dependencies.appSettings.settings
        detectorSettings = dependencies.detectorSettings.settings
        online = dependencies.network.reach != .offline
        if !speaker.start() {
            // Refused: monitoring still listens (the detectors need no
            // speaker), but nothing can play aloud; `applySpeaker` turns a
            // listen mode that asked for it back off.
            Self.log.error("speaker refused at arm")
        }
        following = Task { [weak self] in await self?.follow() }
        reconcile()
        applySpeaker()
        Self.log.notice("monitoring armed: \(self.monitors.count, privacy: .public) rooms")
        return true
    }

    /// Exit: stop everything monitoring started and release the speaker. The
    /// sound mode and every other setting are left as they were; pauses are
    /// cleared so the next open watches every room.
    func exit() {
        exitRequested = true
        stop()
        pausedIds = []
    }

    /// The viewer opened again after an exit: the next arm goes ahead.
    func clearExit() {
        exitRequested = false
    }

    /// Settings emptied the set: nothing enabled can be listened to.
    func stop() {
        guard isRunning else { return }
        isRunning = false
        following?.cancel()
        following = nil
        for id in Array(monitors.keys) { stopMonitor(id) }
        listeningCameraIds = []
        heardBefore = []
        speaker.stop()
        Self.log.notice("monitoring stopped")
    }

    /// The signed-in console may have changed, which changes which cameras
    /// have a livestream to fall back on.
    func refreshConsole() {
        guard isRunning else { return }
        reconcile()
    }

    /// Tries a speaker that was refused or never came back again: when sound
    /// is switched on, and when the app comes back. Returns whether the
    /// speaker is ours now.
    @discardableResult
    func retrySpeaker() -> Bool {
        guard isRunning else { return false }
        guard !speaker.isGranted else { return true }
        let granted = speaker.start()
        applySpeaker()
        return granted
    }

    /// The app went to the background: the speaker must be ours, mixing, for
    /// the night (#58).
    func enteredBackground() {
        guard isRunning else { return }
        speaker.reassert()
    }

    // MARK: - Pauses

    func pause(_ cameraId: String) {
        guard pausedIds.insert(cameraId).inserted else { return }
        changedSet()
    }

    func resume(_ cameraId: String) {
        guard pausedIds.remove(cameraId) != nil else { return }
        changedSet()
    }

    // MARK: - The viewer's sound

    /// The viewer's audible cameras while it makes noise, or nil. Listen mode
    /// stands down while it plays (shared/spec/alerts-and-sound-modes.md).
    func setViewerAloud(_ cameraIds: Set<String>?) {
        guard viewerAloud != cameraIds else { return }
        viewerAloud = cameraIds
        applySpeaker()
    }

    // MARK: - Following the stores

    private func follow() async {
        async let cameras: Void = followCameras()
        async let settings: Void = followSettings()
        async let detector: Void = followDetectorSettings()
        async let reach: Void = followReach()
        async let speaker: Void = followSpeaker()
        async let rooms: Void = followRooms()
        _ = await (cameras, settings, detector, reach, speaker, rooms)
    }

    private func followCameras() async {
        for await next in dependencies.cameras.enabledCameraUpdates() {
            cameras = next
            pausedIds.formIntersection(next.map(\.id))
            if Arming.shouldStopMonitoring(
                running: isRunning,
                enabledMonitorableCount: MonitorTransports.monitorable(next, consoleHost: consoleHost).count)
            {
                stop()
                return
            }
            reconcile()
            applySpeaker()
        }
    }

    private func followSettings() async {
        for await next in dependencies.appSettings.settingsUpdates() {
            settings = next
            applySpeaker()
        }
    }

    private func followDetectorSettings() async {
        for await next in dependencies.detectorSettings.settingsUpdates() {
            detectorSettings = next
            for id in Array(detectors.keys) { detectors[id]?.updateSettings(next) }
        }
    }

    private func followReach() async {
        for await next in dependencies.network.reachUpdates() {
            let nowOnline = next != .offline
            guard nowOnline != online else { continue }
            online = nowOnline
            for monitor in monitors.values {
                if nowOnline { monitor.onNetworkAvailable() } else { monitor.onNetworkLost() }
            }
        }
    }

    private func followSpeaker() async {
        for await event in speaker.updates() {
            switch event {
            case .lost(.routeLost):
                // Lost for good while listen mode holds it: the switch goes
                // back to off rather than standing on beside a silent phone.
                // The viewer answers for its own sound.
                if listenRequested { stopListening() }
            case .lost(.refused):
                break  // `applySpeaker` below
            case .lost(.resumeFailed):
                // A monitor that can no longer hold its session is a failure
                // for #68 to announce.
                Self.log.error("speaker did not come back after an interruption")
            case .interrupted, .resumed, .outputVolumeChanged:
                break
            }
            applySpeaker()
        }
    }

    /// Re-decides the speaker whenever a room starts or stops being audible:
    /// the monitors say so through observation, not an event.
    private func followRooms() async {
        while !Task.isCancelled {
            await withCheckedContinuation { (resume: CheckedContinuation<Void, Never>) in
                let once = OnceFlag()
                withObservationTracking {
                    _ = audibleCameraIds
                    _ = speaker.status
                    _ = speaker.outputVolume
                } onChange: {
                    if once.claim() { resume.resume() }
                }
            }
            guard !Task.isCancelled else { return }
            // onChange runs before the value changes; decide after it has.
            await Task.yield()
            applySpeaker()
        }
    }

    // MARK: - Reconciling

    private var consoleHost: String? { (try? dependencies.credentials.load())?.host }

    private func changedSet() {
        guard isRunning else { return }
        reconcile()
        applySpeaker()
    }

    /// Brings the running monitors in line with the monitored cameras,
    /// without disturbing a room that is still wanted as it was.
    private func reconcile() {
        guard isRunning else { return }
        let host = consoleHost
        let transports = MonitorTransports.transportsFor(cameras, pausedIds: pausedIds, consoleHost: host)
        let wanted = MonitorTransports.monitorable(cameras, pausedIds: pausedIds, consoleHost: host)
        let plan = MonitorPlan.of(
            running: runningCameras, runningTransports: runningTransports, wanted: wanted, transports: transports)
        for id in plan.stop { stopMonitor(id) }
        for camera in plan.start { startMonitor(camera, transports: transports[camera.id] ?? []) }
        for camera in wanted { names[camera.id] = camera.name }
    }

    private func startMonitor(_ camera: Camera, transports: [StreamSource]) {
        let id = camera.id
        let sink = speaker.sink(for: id)
        let makePlayer = self.makePlayer
        let monitor = CameraAudioMonitor(
            cameraId: id, transports: transports, scheduler: scheduler, watchdogConfig: watchdogConfig
        ) { makePlayer(id, sink) }
        detectors[id] = SoundDetector(settings: detectorSettings)
        phases[id] = .armed
        monitor.onLevels = { [weak self] levels in self?.detect(id, levels) }
        monitors[id] = monitor
        runningCameras[id] = camera
        runningTransports[id] = transports
        monitor.start()
        if !online { monitor.onNetworkLost() }
    }

    private func stopMonitor(_ id: String) {
        monitors.removeValue(forKey: id)?.stop()
        runningCameras[id] = nil
        runningTransports[id] = nil
        detectors[id] = nil
        phases[id] = nil
        names[id] = nil
        speaker.removeSink(for: id)
    }

    // MARK: - Detecting

    private func detect(_ id: String, _ levels: [LevelSample]) {
        guard var detector = detectors[id] else { return }
        var fired = false
        for sample in levels where detector.onLevel(sample.rms, nowMs: sample.atMs) { fired = true }
        detectors[id] = detector
        if phases[id] != detector.phase { phases[id] = detector.phase }
        if fired { trigger(id) }
    }

    /// Until #68 delivers alerts, the decision an alert would be raised with.
    private func trigger(_ id: String) {
        let name = names[id] ?? id
        lastTrigger = Trigger(cameraId: id, name: name, at: wallClock())
        let heard = heardAloud()
        Self.log.notice(
            """
            \(name, privacy: .private) is loud: alerts \(self.settings.alertsEnabled ? "on" : "off", privacy: .public), \
            sounds \(!heard.contains(id), privacy: .public), \
            wakes screen \(ListenTarget.alertWakesScreen(cameraId: id, aloud: heard), privacy: .public)
            """)
    }

    // MARK: - The speaker

    /// Listen mode's switch: all aloud, with a room left unpaused.
    private var listenRequested: Bool {
        settings.soundMode == .allAloud && !cameras.isEmpty && !cameras.allSatisfy { pausedIds.contains($0.id) }
    }

    /// Decides, over the whole set at once, what comes out of the speaker.
    private func applySpeaker() {
        guard isRunning else { return }
        if listenRequested, case .failed = speaker.status {
            // Refused, or never came back: as good as lost.
            stopListening()
        }
        let audible = audibleCameraIds
        let target = ListenTarget.of(
            requested: listenRequested, speakerGranted: speaker.isGranted,
            viewerAudible: viewerAloud != nil, monitored: audible)
        if listeningCameraIds != target { listeningCameraIds = target }
        speaker.setAloud(viewerAloud.map { $0.intersection(audible) } ?? target)
        escalateUnheard()
    }

    /// The rooms somebody is hearing: listen mode's, unless the media volume
    /// is at zero.
    private func heardAloud() -> Set<String> {
        ListenTarget.heard(aloud: listeningCameraIds, mediaSilenced: speaker.isMediaSilenced)
    }

    /// A room heard when its cry began had its alarm withheld; one that has
    /// dropped out of the mix while still triggered is raised now, or it
    /// could cry unheard until its detector re-arms
    /// (shared/spec/alerts-and-sound-modes.md, "Escalating a room no longer
    /// heard").
    private func escalateUnheard() {
        let heard = heardAloud()
        let lost = heardBefore.subtracting(heard)
        heardBefore = heard
        for id in lost where phases[id] == .triggered { trigger(id) }
    }

    /// The speaker was refused or lost for good: the sound mode goes back to
    /// off, for the viewer and the monitor alike, and nothing is claimed as
    /// aloud in between.
    private func stopListening() {
        listeningCameraIds = []
        guard settings.soundMode != .off else { return }
        settings.soundMode = .off
        let store = dependencies.appSettings
        Task {
            await store.update { current in
                var next = current
                next.soundMode = .off
                return next
            }
        }
    }
}

/// Lets exactly one of several callers through: an observation's onChange can
/// fire from any thread, and a continuation must be resumed once.
private final class OnceFlag: Sendable {
    private let claimed = Mutex(false)

    func claim() -> Bool {
        claimed.withLock { claimed in
            defer { claimed = true }
            return !claimed
        }
    }
}

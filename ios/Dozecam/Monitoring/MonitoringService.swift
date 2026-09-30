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
/// A trigger raises the room's alert through `AlertCenter`, weighed by the
/// listen-mode rules; every way the monitor can stop doing its job is judged
/// by the `FailureLedger` each second and announced once, after its grace
/// period; and a dead-man alarm, pushed back every heartbeat, rings if the
/// app dies (shared/spec/failure-alerts.md).
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
    /// The most recent trigger, alerted or not.
    private(set) var lastTrigger: Trigger?
    /// Every failure past its grace period, oldest first: the viewer's notice
    /// and the status line, whatever the alerts switch says.
    private(set) var failures: [MonitoringFailure] = []
    /// The most recent announced failure to have cleared.
    private(set) var recovered: RecoveredFailure?
    /// The status line and its proof of life (shared/spec/monitoring-lifecycle.md,
    /// "Staying alive"); on iOS it lives in the viewer.
    private(set) var status: StatusHeartbeat.Display?

    struct Trigger: Equatable {
        let cameraId: String
        let name: String
        let at: Date
    }

    let speaker: Speaker
    let alerts: AlertCenter
    let wording = FailureWording.system

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
    @ObservationIgnored private let battery: BatteryMonitor
    @ObservationIgnored private var ledger = FailureLedger()
    @ObservationIgnored private var announcer = FailureAnnouncer()
    @ObservationIgnored private var statusHeartbeat = StatusHeartbeat()
    @ObservationIgnored private var judging: ScheduledAction?
    @ObservationIgnored private var beating: ScheduledAction?
    /// The last answer on notifications, refreshed on every judgement: iOS
    /// only answers asynchronously, and grants can go without a word.
    @ObservationIgnored private var notificationsAllowed = true

    /// How often the failures are judged: grace periods are whole seconds.
    static let judgeIntervalMs: Int64 = 1_000
    /// How often the dead-man is pushed back: well inside its 3 min lead
    /// (#58 never saw a heartbeat slip past 45 s).
    static let heartbeatIntervalMs: Int64 = 30_000

    private static let log = Logger(subsystem: "app.dozecam", category: "monitor")

    init(
        dependencies: AppDependencies,
        speaker: Speaker,
        makePlayer: @escaping @MainActor (_ cameraId: String, _ sink: SpeakerSink) -> any AudioPlayer,
        alerts: AlertCenter? = nil,
        battery: BatteryMonitor = BatteryMonitor(),
        scheduler: any MonotonicScheduler = ContinuousScheduler.shared,
        watchdogConfig: PlaybackWatchdog.Config = .init(),
        wallClock: @escaping () -> Date = Date.init
    ) {
        self.dependencies = dependencies
        self.speaker = speaker
        self.makePlayer = makePlayer
        self.alerts = alerts ?? AlertCenter(delivery: .inert(), scheduler: scheduler)
        self.battery = battery
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
        battery.start()
        ledger = FailureLedger()
        announcer = FailureAnnouncer()
        statusHeartbeat = StatusHeartbeat()
        judge()
        heartbeat()
        Self.log.notice("monitoring armed: \(self.monitors.count, privacy: .public) rooms")
        return true
    }

    /// Exit: stop everything monitoring started and release the speaker. The
    /// sound mode and every other setting are left as they were; pauses are
    /// cleared so the next open watches every room.
    func exit() {
        exitRequested = true
        if isRunning { stop() } else { alerts.exit() }
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
        judging?.cancel()
        judging = nil
        beating?.cancel()
        beating = nil
        battery.stop()
        failures = []
        recovered = nil
        status = nil
        // Nothing monitoring posted outlives it, and the dead-man must not
        // ring for a monitor that stopped on purpose.
        alerts.exit()
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

    // MARK: - Alerts

    /// A person is here: a touch on the viewer, or a card opened or
    /// dismissed. The alarm stops.
    func acknowledge() {
        alerts.acknowledge()
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
            let alertsWere = settings.alertsEnabled
            settings = next
            if next.alertsEnabled != alertsWere {
                // Off: nothing may reach anyone, so what is up comes down.
                // On: a failure owed its announcement gets it now.
                if !next.alertsEnabled { alerts.dropAll() }
                alerts.apply(
                    announcer.alertsChanged(enabled: next.alertsEnabled, active: failures), wording: wording,
                    settings: next)
            }
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
                // Judged as a failure (`audioSessionLost`) from here on.
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
        // A room leaving the set takes its alert with it.
        alerts.withdraw(cameraId: id)
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

    /// A room's alert (Android's `raiseAlert`), weighed against what is
    /// heard through listen mode (shared/spec/alerts-and-sound-modes.md).
    private func trigger(_ id: String) {
        let name = names[id] ?? id
        lastTrigger = Trigger(cameraId: id, name: name, at: wallClock())
        let heard = heardAloud()
        let sounds = ListenTarget.alertSounds(cameraId: id, aloud: heard)
        let prominent = ListenTarget.alertWakesScreen(cameraId: id, aloud: heard)
        Self.log.notice(
            """
            \(name, privacy: .private) is loud: alerts \(self.settings.alertsEnabled ? "on" : "off", privacy: .public), \
            sounds \(sounds, privacy: .public), wakes screen \(prominent, privacy: .public)
            """)
        // The detector still ran, and the meters say so; nothing reaches anyone.
        guard settings.alertsEnabled else { return }
        // One card: a heard room must not replace the card of a room nobody
        // can hear while that one's alarm sounds.
        guard !ListenTarget.alertYields(cameraId: id, aloud: heard, alarmingCameraId: alerts.alarmingCameraId)
        else { return }
        alerts.raiseRoom(cameraId: id, name: name, sounds: sounds, prominent: prominent, settings: settings)
    }

    // MARK: - Failures

    /// Every way the monitor could be failing, judged together, every second
    /// while armed: time is an input, since a failure crosses its grace
    /// period with no event of its own (shared/spec/failure-alerts.md).
    private func judge() {
        judging = nil
        guard isRunning else { return }
        let reading = battery.reading
        let health = MonitoringHealth(
            cameras: monitors.map { id, monitor in
                CameraMonitorState(
                    cameraId: id, name: names[id] ?? id, level: monitor.level, phase: phases[id] ?? .armed,
                    connection: monitor.connection)
            }.sorted { $0.cameraId < $1.cameraId },
            networkOnline: online,
            battery: reading.level.map {
                BatteryStatus(percent: Int(($0 * 100).rounded()), plugged: reading.isPluggedIn)
            },
            notificationsAllowed: notificationsAllowed,
            screenWakeAllowed: alerts.delivery.access.alarms == .authorized,
            audioSessionLost: speakerLost)
        let wallNowMs = Int64(wallClock().timeIntervalSince1970 * 1_000)
        let update = ledger.evaluate(
            health, graceMs: Int64(settings.failureGraceMs), nowMs: scheduler.nowMs, wallNowMs: wallNowMs)
        if failures != update.active { failures = update.active }
        if let note = update.recoveryNote(monitoredCameraIds: Set(monitors.keys)) { recovered = note }
        if update.unplugged, let percent = health.battery?.percent { alerts.unplugged(percent: percent) }
        alerts.apply(
            announcer.judge(update, alertsEnabled: settings.alertsEnabled), wording: wording, settings: settings)
        offerStatus(health.cameras, wallNowMs: wallNowMs)

        let access = alerts.delivery.access
        Task { [weak self] in
            let grant = await access.notifications()
            self?.notificationsAllowed = grant.canPost
        }
        judging = scheduler.schedule(after: Self.judgeIntervalMs) { [weak self] in self?.judge() }
    }

    /// The speaker refused, or gone for good after an interruption: with the
    /// screen locked nothing keeps the app listening.
    private var speakerLost: Bool {
        switch speaker.status {
        case .failed(.refused), .failed(.resumeFailed): true
        default: false
        }
    }

    private func offerStatus(_ states: [CameraMonitorState], wallNowMs: Int64) {
        let enabled = cameras.count
        let paused = cameras.filter { pausedIds.contains($0.id) }.count
        let line = MonitoringStatus.of(
            anyMonitors: !monitors.isEmpty, states: states, enabledCount: enabled - paused, pausedCount: paused,
            aloudCameraIds: listeningCameraIds, alertsEnabled: settings.alertsEnabled, failures: failures,
            recovered: recovered, wording: wording)
        if let display = statusHeartbeat.offer(
            line.text, level: line.level, wallMs: wallNowMs, monotonicMs: scheduler.nowMs)
        {
            status = display
        }
    }

    /// Pushes the dead-man back; it rings only if these stop.
    private func heartbeat() {
        beating = nil
        guard isRunning else { return }
        alerts.heartbeat()
        beating = scheduler.schedule(after: Self.heartbeatIntervalMs) { [weak self] in self?.heartbeat() }
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

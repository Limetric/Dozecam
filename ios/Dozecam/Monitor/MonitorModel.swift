import Foundation
import Observation
import UIKit

/// The viewer's state, the counterpart of Android's `MonitorViewModel` plus
/// the state `MonitorScreen` keeps: every enabled camera, live, in a grid or
/// one at a time, with honest connection state, the sound mode and the
/// controls around them.
///
/// Players come from `makePlayer`, the seam the real players (VLCKit for RTSP,
/// the livestream pipeline for Protect) are wired in through; tests and the
/// debug build pass fakes. Sessions exist only while the viewer is on screen
/// with its scene in the foreground (`CameraSessions`).
@MainActor
@Observable
final class MonitorModel {
    struct Announcement: Equatable {
        let id: Int
        let text: String
    }

    /// The enabled cameras, in the order the user keeps them, paused or not:
    /// a paused room keeps its place, so the way back is where it was left.
    private(set) var cameras: [Camera]
    /// At least one camera exists, but none is switched on.
    private(set) var hasDisabledOnly: Bool
    private(set) var settings: AppSettings
    private(set) var reach: NetworkReach
    /// Rooms set aside for tonight ("this child is still up"). In memory only,
    /// so exiting brings every room back (shared/spec/monitoring-lifecycle.md).
    /// The monitor (#67) will read the same set; until it exists it lives here.
    private(set) var pausedIds: Set<String> = []
    /// The camera that has the screen to itself, if any.
    private(set) var fullscreenId: String?
    /// The wait before a single camera hands the screen back to the grid.
    private(set) var countdown: InactivityCountdown?
    /// The single camera's pinch; every camera opens whole.
    var zoom = PinchZoom()
    /// The cameras whose sound is playing, as the screen should mark them.
    private(set) var audibleIds: Set<String> = []
    /// A short confirmation of what a button just did.
    private(set) var announcement: Announcement?
    var isConfirmingExit = false
    private(set) var isOnScreen = false
    private(set) var isInForeground = true

    let sessions: CameraSessions

    @ObservationIgnored let dependencies: AppDependencies
    @ObservationIgnored private let scheduler: any MonotonicScheduler
    @ObservationIgnored private let inactivityTimeoutMs: Int64
    @ObservationIgnored private let setIdleTimerDisabled: @MainActor (Bool) -> Void
    @ObservationIgnored private var rotation: SoundRotation?
    @ObservationIgnored private var sources: [String: StreamSource] = [:]
    @ObservationIgnored private var visibleIds: Set<String> = []
    @ObservationIgnored private var warmIds: Set<String> = []
    @ObservationIgnored private var idleTimerDisabled: Bool?
    @ObservationIgnored private var announcementTimer: ScheduledAction?
    @ObservationIgnored private var announcementCount = 0
    @ObservationIgnored private var lastWrite: Task<Void, Never>?

    /// How long a confirmation stays up: long enough to read a sentence.
    static let announcementMs: Int64 = 4_000

    init(
        dependencies: AppDependencies,
        makePlayer: @escaping CameraSessions.MakePlayer,
        scheduler: any MonotonicScheduler = ContinuousScheduler.shared,
        wallClock: @escaping () -> Date = Date.init,
        watchdogConfig: PlaybackWatchdog.Config = .init(),
        soundRotationIntervalMs: Int64 = SoundRotation.intervalMs,
        inactivityTimeoutMs: Int64 = InactivityCountdown.timeoutMs,
        setIdleTimerDisabled: @escaping @MainActor (Bool) -> Void = { UIApplication.shared.isIdleTimerDisabled = $0 }
    ) {
        self.dependencies = dependencies
        self.scheduler = scheduler
        self.inactivityTimeoutMs = inactivityTimeoutMs
        self.setIdleTimerDisabled = setIdleTimerDisabled
        sessions = CameraSessions(
            makePlayer: makePlayer, scheduler: scheduler, wallClock: wallClock, config: watchdogConfig)
        cameras = dependencies.cameras.enabledCameras
        hasDisabledOnly = !dependencies.cameras.cameras.isEmpty && dependencies.cameras.enabledCameras.isEmpty
        settings = dependencies.appSettings.settings
        reach = dependencies.network.reach
        rotation = SoundRotation(intervalMs: soundRotationIntervalMs, scheduler: scheduler) { [weak self] in
            self?.sync()
        }
    }

    // MARK: - What the screen reads

    /// The cameras actually being watched: everything that plays, opens, is
    /// kept warm or takes a turn at the speaker works from these.
    var activeCameras: [Camera] { cameras.filter { !pausedIds.contains($0.id) } }

    var fullscreenCamera: Camera? {
        fullscreenId.flatMap { id in activeCameras.first { $0.id == id } }
    }

    var palette: ViewerPalette { .of(nightTheme: settings.nightTheme) }

    /// Watching a camera holds the display unless the user switched that off;
    /// an empty viewer, or a grid of nothing but paused rooms, never does.
    var keepsScreenAwake: Bool {
        Self.keepsScreenAwake(
            keepScreenOn: settings.keepScreenOn, watchedCameras: activeCameras.count,
            viewerShowing: isOnScreen && isInForeground)
    }

    nonisolated static func keepsScreenAwake(keepScreenOn: Bool, watchedCameras: Int, viewerShowing: Bool) -> Bool {
        keepScreenOn && watchedCameras > 0 && viewerShowing
    }

    func session(for cameraId: String) -> CameraSession? { sessions[cameraId] }

    /// What the sound button's next press does, as a VoiceOver label.
    var soundModeActionLabel: String {
        switch Self.nextSoundMode(after: settings.soundMode) {
        case .rotating: "Rotate sound between cameras"
        case .allAloud: "Play every camera aloud"
        case .off: "Turn sound off"
        }
    }

    nonisolated static func nextSoundMode(after mode: SoundMode) -> SoundMode {
        switch mode {
        case .off: .rotating
        case .rotating: .allAloud
        case .allAloud: .off
        }
    }

    // MARK: - Lifecycle

    /// Follows the stores and the network for as long as the viewer is on
    /// screen: run it from the view's `.task`, so it ends (and every session
    /// with it) when the viewer goes.
    func observe() async {
        isOnScreen = true
        refreshSources()
        sync()
        defer {
            isOnScreen = false
            sync()
        }
        async let cameras: Void = followEnabledCameras()
        async let all: Void = followAllCameras()
        async let settings: Void = followSettings()
        async let reach: Void = followReach()
        _ = await (cameras, all, settings, reach)
    }

    /// The scene went to the background or came back. Backgrounding tears
    /// every player down; coming back rebuilds them for whatever is on screen,
    /// and re-reads the console, which may have changed meanwhile. Inactive
    /// (the app switcher, a banner) is still showing, and keeps playing.
    func sceneChanged(inForeground: Bool) {
        guard inForeground != isInForeground else { return }
        isInForeground = inForeground
        if inForeground { refreshSources() }
        sync()
    }

    /// A grid slot scrolled into or out of view. Only tiles on screen hold a
    /// session and take a turn at the speaker: a turn for a camera nobody can
    /// see is ten seconds of silence next to a badge nobody can see.
    func tileVisibilityChanged(_ cameraId: String, visible: Bool) {
        let changed = visible ? visibleIds.insert(cameraId).inserted : visibleIds.remove(cameraId) != nil
        if changed { sync() }
    }

    // MARK: - Fullscreen

    /// Opens one camera, and keeps the rest of the grid connected behind it.
    /// The warm set is named here, in the same act that empties the grid.
    func open(_ cameraId: String) {
        guard activeCameras.contains(where: { $0.id == cameraId }) else { return }
        let warm: Set<String> =
            if let previous = fullscreenId {
                // Swapping the camera on screen: the one left joins the rest.
                warmIds.union([previous])
            } else {
                visibleIds
            }
        warmIds = warm.subtracting([cameraId]).intersection(activeCameras.map(\.id))
        fullscreenId = cameraId
        zoom = PinchZoom()
        countdown?.stop()
        countdown = InactivityCountdown(timeoutMs: inactivityTimeoutMs, scheduler: scheduler) { [weak self] in
            self?.closeFullscreen()
        }
        sync()
    }

    func closeFullscreen() {
        guard fullscreenId != nil else { return }
        fullscreenId = nil
        warmIds = []
        countdown?.stop()
        countdown = nil
        sync()
    }

    /// A hand on the screen: somebody is looking, so the minute starts over.
    func userInteracted() {
        countdown?.reset()
    }

    // MARK: - Pause

    func pause(_ cameraId: String, announcing: Bool = true) {
        guard let camera = cameras.first(where: { $0.id == cameraId }), !pausedIds.contains(cameraId) else { return }
        pausedIds.insert(cameraId)
        // Said in words: the tile changing is easy to miss from across a room,
        // and pausing is the one press here that stops watching somebody.
        if announcing { announce("\(camera.name) paused. Nothing there raises an alert until you resume it.") }
        // No picture left to show: the grid comes back, with the room's
        // placeholder in its slot.
        if fullscreenId == cameraId { closeFullscreen() }
        sync()
    }

    func resume(_ cameraId: String) {
        guard let camera = cameras.first(where: { $0.id == cameraId }), pausedIds.contains(cameraId) else { return }
        pausedIds.remove(cameraId)
        announce("\(camera.name) resumed")
        sync()
    }

    // MARK: - Controls

    /// Steps off → one room at a time → every room aloud → off. One stored
    /// setting for the one speaker, shared with the monitor.
    func cycleSoundMode() {
        userInteracted()
        let next = Self.nextSoundMode(after: settings.soundMode)
        settings.soundMode = next
        write { $0.soundMode = next }
        switch next {
        case .off:
            announce("Sound off")
        case .rotating:
            announce("Sound on, one camera at a time")
        case .allAloud:
            // Nothing carries the sound past this screen until the monitor
            // does (#67), so the confirmation promises only the screen.
            let rooms = activeCameras
            announce(
                rooms.count == 1
                    ? "\(rooms[0].name) is playing aloud while this screen is on"
                    : "\(rooms.count) cameras are playing aloud while this screen is on")
        }
        sync()
    }

    /// The viewer's alerts button and the Alerts settings' master switch are
    /// the same stored setting (shared/spec/alerts-and-sound-modes.md).
    func toggleAlerts() {
        let enabled = !settings.alertsEnabled
        settings.alertsEnabled = enabled
        write { $0.alertsEnabled = enabled }
        announce(
            enabled
                ? "Alerts on — a loud room will wake you" : "Alerts off — nothing will wake you if a room gets loud")
    }

    func toggleKeepScreenOn() {
        let enabled = !settings.keepScreenOn
        settings.keepScreenOn = enabled
        write { $0.keepScreenOn = enabled }
        announce(enabled ? "The screen will stay awake while cameras are showing" : "The screen will sleep as usual")
        sync()
    }

    /// Exiting is the one control that can undo the whole point of the app,
    /// so it asks first.
    func requestExit() {
        isConfirmingExit = true
    }

    /// Set by the monitor (#67), whose exit stops monitoring and takes down
    /// everything it posted. Until then exit does what this screen owns.
    @ObservationIgnored var exitHandler: (@MainActor () -> Void)?

    func confirmExit() {
        isConfirmingExit = false
        // Pauses are cleared, so the next open watches every room.
        pausedIds = []
        closeFullscreen()
        exitHandler?()
        sync()
    }

    /// Waits until every setting written so far has reached the store.
    func flush() async {
        await lastWrite?.value
    }

    // MARK: - Internals

    private func followEnabledCameras() async {
        for await next in dependencies.cameras.enabledCameraUpdates() {
            cameras = next
            hasDisabledOnly = !dependencies.cameras.cameras.isEmpty && next.isEmpty
            pausedIds.formIntersection(next.map(\.id))
            refreshSources()
            sync()
        }
    }

    private func followAllCameras() async {
        for await all in dependencies.cameras.cameraUpdates() {
            hasDisabledOnly = !all.isEmpty && !all.contains(where: \.enabled)
        }
    }

    private func followSettings() async {
        for await next in dependencies.appSettings.settingsUpdates() {
            guard next != settings else { continue }
            settings = next
            sync()
        }
    }

    private func followReach() async {
        for await next in dependencies.network.reachUpdates() {
            reach = next
            sessions.setOnline(next != .offline)
        }
    }

    /// How each camera's video is fetched. A camera issued by a console other
    /// than the one signed in plays its own RTSP URL, so this is re-read when
    /// the viewer comes back: signing in elsewhere replaces the credentials
    /// without touching the camera list.
    private func refreshSources() {
        let host = (try? dependencies.credentials.load())?.host
        sources = Dictionary(
            cameras.map { ($0.id, StreamSource.of($0, consoleHost: host)) }, uniquingKeysWith: { first, _ in first })
    }

    /// Brings every session, the speaker, the countdown and the display in
    /// line with the state, in one step.
    private func sync() {
        let activeIds = activeCameras.map(\.id)
        if let id = fullscreenId, !activeIds.contains(id) {
            // A camera that went away (switched off, paused, deleted) must not
            // strand the viewer on a blank screen.
            fullscreenId = nil
            countdown?.stop()
            countdown = nil
        }
        // Nor go on being kept warm.
        warmIds.formIntersection(activeIds)
        if fullscreenId == nil { warmIds = [] }

        let showing = isOnScreen && isInForeground
        let onScreen = activeIds.filter(visibleIds.contains)
        let soundOn = settings.soundMode != .off
        // Rotation is a grid matter: one camera alone has the whole attention
        // and keeps the sound for as long as it is up.
        rotation?.update(
            cameraIds: onScreen, enabled: showing && settings.soundMode == .rotating && fullscreenId == nil)
        let audible: Set<String> =
            if !soundOn || !showing {
                []
            } else if let id = fullscreenId {
                [id]
            } else if settings.soundMode == .allAloud {
                Set(onScreen)
            } else {
                Set([rotation?.current].compactMap { $0 })
            }
        if audibleIds != audible { audibleIds = audible }

        var wanted: [String: StreamSource] = [:]
        for id in fullscreenId.map({ [$0] }) ?? onScreen {
            if let source = sources[id] { wanted[id] = source }
        }
        sessions.update(active: showing, wanted: wanted, warm: warmIds, audible: audible)

        if let countdown {
            // Time in the background does not count against the viewer.
            if showing, !countdown.isRunning {
                countdown.start()
            } else if !showing {
                countdown.stop()
            }
        }

        let awake = keepsScreenAwake
        if idleTimerDisabled != awake {
            idleTimerDisabled = awake
            setIdleTimerDisabled(awake)
        }
    }

    private func write(_ change: @escaping @Sendable (inout AppSettings) -> Void) {
        let store = dependencies.appSettings
        let previous = lastWrite
        lastWrite = Task {
            await previous?.value
            await store.update { current in
                var next = current
                change(&next)
                return next
            }
        }
    }

    /// Each new confirmation replaces the last: someone tapping twice to undo
    /// a slip should read the outcome, not a queue.
    private func announce(_ text: String) {
        announcementCount += 1
        announcement = Announcement(id: announcementCount, text: text)
        announcementTimer?.cancel()
        let id = announcementCount
        announcementTimer = scheduler.schedule(after: Self.announcementMs) { [weak self] in
            guard let self, announcement?.id == id else { return }
            announcement = nil
        }
    }
}

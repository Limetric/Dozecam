@preconcurrency import ActivityKit
@preconcurrency import AlarmKit
import AudioToolbox
import AVFoundation
import Network
import Observation
import Synchronization
import UIKit
import UserNotifications
import os

/// Renders Dozecam's normal "sound off" state (decoded but silent) and, when
/// asked, an alarm tone through the same already-running engine.
final class ToneRenderer: @unchecked Sendable {
    let alarming = Atomic<Bool>(false)
    let sampleRate: Double
    // Touched only on the render thread.
    private var phase: Double = 0
    private var frame: Int64 = 0

    init(sampleRate: Double) { self.sampleRate = sampleRate }

    func render(frames: AVAudioFrameCount, buffers abl: UnsafeMutablePointer<AudioBufferList>) -> OSStatus {
        let buffers = UnsafeMutableAudioBufferListPointer(abl)
        let on = alarming.load(ordering: .relaxed)
        for i in 0..<Int(frames) {
            var sample: Float = 0
            if on {
                let t = Double(frame) / sampleRate
                if t.truncatingRemainder(dividingBy: 0.4) < 0.25 { sample = Float(sin(phase)) * 0.9 }
                phase += 2 * .pi * 880 / sampleRate
                if phase > 2 * .pi { phase -= 2 * .pi }
            }
            frame += 1
            for buffer in buffers { buffer.mData?.assumingMemoryBound(to: Float.self)[i] = sample }
        }
        return noErr
    }
}

private final class Once: @unchecked Sendable {
    private let lock = NSLock()
    private var done = false
    func claim() -> Bool {
        lock.lock()
        defer { lock.unlock() }
        if done { return false }
        done = true
        return true
    }
}

final class NotificationLogger: NSObject, UNUserNotificationCenterDelegate, Sendable {
    func userNotificationCenter(
        _ center: UNUserNotificationCenter, willPresent notification: UNNotification
    ) async -> UNNotificationPresentationOptions {
        SpikeLog.write("NOTIF", "willPresent id=\(notification.request.identifier) date=\(notification.date)")
        return [.banner, .sound, .list]
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse) async {
        SpikeLog.write(
            "NOTIF",
            "didReceive id=\(response.notification.request.identifier) delivered=\(response.notification.date) action=\(response.actionIdentifier)"
        )
    }
}

@MainActor
@Observable
final class Monitor {
    // MARK: settings (persisted)
    var mixWithOthers: Bool { didSet { defaults.set(mixWithOthers, forKey: "mixWithOthers") } }
    var deadManMinutes: Int { didSet { defaults.set(deadManMinutes, forKey: "deadManMinutes") } }
    var alarmDeadMan: Bool { didSet { defaults.set(alarmDeadMan, forKey: "alarmDeadMan") } }
    var beatSeconds: Int { didSet { defaults.set(beatSeconds, forKey: "beatSeconds") } }
    var probeHost: String { didSet { defaults.set(probeHost, forKey: "probeHost") } }
    var liveActivityEnabled: Bool { didSet { defaults.set(liveActivityEnabled, forKey: "liveActivity") } }
    var triggerDelay: Int { didSet { defaults.set(triggerDelay, forKey: "triggerDelay") } }
    var useAlarmKit: Bool { didSet { defaults.set(useAlarmKit, forKey: "useAlarmKit") } }
    var useTimeSensitive: Bool { didSet { defaults.set(useTimeSensitive, forKey: "useTimeSensitive") } }
    var useOwnSound: Bool { didSet { defaults.set(useOwnSound, forKey: "useOwnSound") } }
    var useVibrate: Bool { didSet { defaults.set(useVibrate, forKey: "useVibrate") } }

    // MARK: state
    private(set) var running = false
    private(set) var engineRunning = false
    private(set) var beats = 0
    private(set) var lastBeat: Date?
    private(set) var lastLine = ""
    private(set) var activityState = "none"
    private(set) var pendingTrigger: Date?

    private let defaults = UserDefaults.standard
    private let session = AVAudioSession.sharedInstance()
    private var engine = AVAudioEngine()
    private let renderer = ToneRenderer(sampleRate: 48_000)
    private var heartbeat: Task<Void, Never>?
    private var activity: Activity<MonitoringAttributes>?
    private var ownSoundStop: Task<Void, Never>?
    private let notificationLogger = NotificationLogger()
    private var observers: [NSObjectProtocol] = []

    init() {
        defaults.register(defaults: [
            "mixWithOthers": true, "deadManMinutes": 3, "alarmDeadMan": true, "beatSeconds": 30, "probeHost": "192.168.0.158", "liveActivity": true,
            "triggerDelay": 30, "useAlarmKit": true, "useTimeSensitive": true, "useOwnSound": true,
            "useVibrate": true,
        ])
        mixWithOthers = defaults.bool(forKey: "mixWithOthers")
        deadManMinutes = defaults.integer(forKey: "deadManMinutes")
        alarmDeadMan = defaults.bool(forKey: "alarmDeadMan")
        beatSeconds = defaults.integer(forKey: "beatSeconds")
        probeHost = defaults.string(forKey: "probeHost") ?? ""
        liveActivityEnabled = defaults.bool(forKey: "liveActivity")
        triggerDelay = defaults.integer(forKey: "triggerDelay")
        useAlarmKit = defaults.bool(forKey: "useAlarmKit")
        useTimeSensitive = defaults.bool(forKey: "useTimeSensitive")
        useOwnSound = defaults.bool(forKey: "useOwnSound")
        useVibrate = defaults.bool(forKey: "useVibrate")

        UNUserNotificationCenter.current().delegate = notificationLogger
        UIDevice.current.isBatteryMonitoringEnabled = true
        logLaunch()
        observe()
        observeAlarms()
        if defaults.bool(forKey: "monitoring") {
            SpikeLog.write("LAUNCH", "previous run was monitoring → auto-arming (Dozecam arms on every resume)")
            start()
        }
        // `spike.sh launch -armTrigger 45` arms the alert lab from the Mac;
        // any setting can be overridden the same way (`-mixWithOthers YES`).
        let armIn = defaults.integer(forKey: "armTrigger")
        if armIn > 0 {
            triggerDelay = armIn
            armTrigger()
        }
    }

    // MARK: launch forensics

    private func logLaunch() {
        let state = UIApplication.shared.applicationState
        var line = "app launched, state=\(Self.name(state)) pid=\(ProcessInfo.processInfo.processIdentifier)"
        if let last = defaults.object(forKey: "lastBeat") as? Date {
            let gap = Int(Date().timeIntervalSince(last))
            line += "; previous run: monitoring=\(defaults.bool(forKey: "monitoring")) lastBeat=\(last) (\(gap)s ago) cleanStop=\(defaults.bool(forKey: "cleanStop")) beats=\(defaults.integer(forKey: "beats"))"
        }
        SpikeLog.write("LAUNCH", line)
        // Snapshot before auto-arm starts this run's activity.
        let leftovers = Activity<MonitoringAttributes>.activities
        Task {
            let delivered = await UNUserNotificationCenter.current().deliveredNotifications()
            for n in delivered {
                SpikeLog.write("LAUNCH", "delivered while away: id=\(n.request.identifier) at \(n.date)")
            }
            let settings = await UNUserNotificationCenter.current().notificationSettings()
            SpikeLog.write(
                "LAUNCH",
                "notif auth=\(settings.authorizationStatus.rawValue) timeSensitive=\(settings.timeSensitiveSetting.rawValue) sound=\(settings.soundSetting.rawValue) lock=\(settings.lockScreenSetting.rawValue) scheduledDelivery=\(settings.scheduledDeliverySetting.rawValue) alarmKit=\(AlarmManager.shared.authorizationState) activities=\(ActivityAuthorizationInfo().areActivitiesEnabled) frequent=\(ActivityAuthorizationInfo().frequentPushesEnabled)"
            )
            let alarms = (try? AlarmManager.shared.alarms) ?? []
            SpikeLog.write("LAUNCH", "alarmKit alarms pending=\(alarms.count) states=\(alarms.map { "\($0.state)" })")
            for old in leftovers {
                SpikeLog.write("LAUNCH", "ending leftover activity \(old.id) state=\(old.activityState)")
                await old.end(nil, dismissalPolicy: .immediate)
            }
        }
    }

    // MARK: monitoring

    func start() {
        guard !running else { return }
        running = true
        defaults.set(true, forKey: "monitoring")
        defaults.set(false, forKey: "cleanStop")
        SpikeLog.write("MON", "start mixWithOthers=\(mixWithOthers) deadMan=\(deadManMinutes)m alarmDeadMan=\(alarmDeadMan) beat=\(beatSeconds)s")
        startAudio(reason: "start")
        if liveActivityEnabled { startActivity(reason: "start") }
        heartbeat = Task { [weak self] in
            while !Task.isCancelled {
                await self?.beat()
                let seconds = self?.beatSeconds ?? 30
                try? await Task.sleep(for: .seconds(seconds))
            }
        }
    }

    func stop() {
        guard running else { return }
        running = false
        heartbeat?.cancel()
        heartbeat = nil
        engine.stop()
        engineRunning = false
        try? session.setActive(false, options: .notifyOthersOnDeactivation)
        UNUserNotificationCenter.current().removePendingNotificationRequests(withIdentifiers: ["deadman"])
        cancelAlarmDeadMan()
        if let activity {
            Task { await activity.end(nil, dismissalPolicy: .immediate) }
        }
        activity = nil
        activityState = "none"
        defaults.set(false, forKey: "monitoring")
        defaults.set(true, forKey: "cleanStop")
        SpikeLog.write("MON", "stop (clean)")
    }

    private func configureSession() throws {
        try session.setCategory(.playback, mode: .default, options: mixWithOthers ? [.mixWithOthers] : [])
        try session.setActive(true)
    }

    private func buildEngine() {
        engine.stop()
        engine = AVAudioEngine()
        let format = AVAudioFormat(standardFormatWithSampleRate: renderer.sampleRate, channels: 1)!
        let source = Self.makeSource(format: format, renderer: renderer)
        engine.attach(source)
        engine.connect(source, to: engine.mainMixerNode, format: format)
    }

    /// Nonisolated on purpose: a render block formed inside a @MainActor method
    /// inherits MainActor isolation, and Swift 6's runtime check then traps on
    /// the realtime audio thread.
    private nonisolated static func makeSource(format: AVAudioFormat, renderer: ToneRenderer) -> AVAudioSourceNode {
        AVAudioSourceNode(format: format) { _, _, frames, abl in
            renderer.render(frames: frames, buffers: abl)
        }
    }

    @discardableResult
    private func startAudio(reason: String) -> Bool {
        do {
            try configureSession()
            buildEngine()
            try engine.start()
            engineRunning = true
            SpikeLog.write("AUDIO", "started (\(reason)) category=\(session.category.rawValue) options=\(session.categoryOptions.rawValue)")
            return true
        } catch {
            engineRunning = false
            SpikeLog.write("AUDIO", "start FAILED (\(reason)): \(error)")
            return false
        }
    }

    private func beat() async {
        guard running else { return }
        beats += 1
        let now = Date()
        lastBeat = now
        defaults.set(now, forKey: "lastBeat")
        defaults.set(beats, forKey: "beats")

        engineRunning = engine.isRunning
        if !engineRunning {
            SpikeLog.write("AUDIO", "engine not running at beat \(beats); retrying from \(Self.name(UIApplication.shared.applicationState))")
            startAudio(reason: "beat-retry")
        }

        scheduleDeadMan()
        if liveActivityEnabled { await updateActivity() }

        let lan = probeHost.isEmpty ? "off" : await Self.probe(host: probeHost, port: 18554)
        let device = UIDevice.current
        let battery = device.batteryLevel < 0 ? "?" : "\(Int(device.batteryLevel * 100))%"
        let app = UIApplication.shared
        let line = [
            "#\(beats)",
            "app=\(Self.name(app.applicationState))",
            "locked=\(app.isProtectedDataAvailable ? 0 : 1)",
            "eng=\(engineRunning ? "on" : "OFF")",
            "otherAudio=\(session.isOtherAudioPlaying ? 1 : 0)",
            "vol=\(String(format: "%.2f", session.outputVolume))",
            "route=\(session.currentRoute.outputs.map(\.portType.rawValue).joined(separator: "+"))",
            "bat=\(battery)/\(Self.name(device.batteryState))",
            "lpm=\(ProcessInfo.processInfo.isLowPowerModeEnabled ? 1 : 0)",
            "therm=\(ProcessInfo.processInfo.thermalState.rawValue)",
            "memLeft=\(os_proc_available_memory() / 1_048_576)MB",
            "lan=\(lan)",
            "la=\(activityState)",
        ].joined(separator: " ")
        lastLine = line
        SpikeLog.write("BEAT", line)
    }

    // MARK: dead-man switch

    private func scheduleDeadMan() {
        let content = UNMutableNotificationContent()
        content.title = "Night Spike is not monitoring"
        content.body = "No heartbeat since \(Date().formatted(date: .omitted, time: .standard)). The app was stopped or suspended."
        content.sound = UNNotificationSound(named: UNNotificationSoundName("alarm.caf"))
        content.interruptionLevel = .timeSensitive
        let trigger = UNTimeIntervalNotificationTrigger(timeInterval: TimeInterval(deadManMinutes * 60), repeats: false)
        let request = UNNotificationRequest(identifier: "deadman", content: content, trigger: trigger)
        Task {
            do { try await UNUserNotificationCenter.current().add(request) }
            catch { SpikeLog.write("DEADMAN", "schedule failed: \(error)") }
        }
        if alarmDeadMan { Task { await rescheduleAlarmDeadMan() } }
    }

    /// The dead-man as an AlarmKit alarm, so it rings through silent mode and
    /// Sleep Focus. Schedule the new one before cancelling the old one, so
    /// there is never a moment without an armed alarm.
    private func rescheduleAlarmDeadMan() async {
        let attributes = AlarmAttributes<SpikeAlarmMetadata>(
            presentation: AlarmPresentation(alert: .init(title: "Night Spike is not monitoring")),
            metadata: SpikeAlarmMetadata(room: "dead-man"),
            tintColor: .red
        )
        let config = AlarmManager.AlarmConfiguration<SpikeAlarmMetadata>.alarm(
            schedule: .fixed(Date().addingTimeInterval(TimeInterval(deadManMinutes * 60))), attributes: attributes)
        let id = UUID()
        do {
            _ = try await AlarmManager.shared.schedule(id: id, configuration: config)
        } catch {
            SpikeLog.write("DEADMAN", "alarm schedule FAILED: \(error)")
            return
        }
        if let old = defaults.string(forKey: "alarmDeadManID").flatMap(UUID.init(uuidString:)) {
            do { try AlarmManager.shared.cancel(id: old) }
            catch { SpikeLog.write("DEADMAN", "alarm cancel of \(old) FAILED: \(error)") }
        }
        defaults.set(id.uuidString, forKey: "alarmDeadManID")
    }

    private func cancelAlarmDeadMan() {
        if let old = defaults.string(forKey: "alarmDeadManID").flatMap(UUID.init(uuidString:)) {
            try? AlarmManager.shared.cancel(id: old)
        }
        defaults.removeObject(forKey: "alarmDeadManID")
    }

    // MARK: Live Activity

    private func startActivity(reason: String) {
        let info = ActivityAuthorizationInfo()
        guard info.areActivitiesEnabled else {
            activityState = "disabled"
            SpikeLog.write("LA", "not started (\(reason)): activities disabled on this device")
            return
        }
        do {
            let state = MonitoringAttributes.ContentState(rooms: 2, lastBeat: .now, status: "Quiet", beats: beats)
            let started = try Activity.request(
                attributes: MonitoringAttributes(startedAt: .now),
                content: ActivityContent(state: state, staleDate: .now.addingTimeInterval(TimeInterval(beatSeconds * 4))),
                pushType: nil
            )
            activity = started
            activityState = "active"
            SpikeLog.write("LA", "started (\(reason)) from \(Self.name(UIApplication.shared.applicationState)) id=\(started.id)")
            Task { [weak self] in
                for await state in started.activityStateUpdates {
                    SpikeLog.write("LA", "state → \(state) id=\(started.id)")
                    self?.activityState = "\(state)"
                }
            }
        } catch {
            activityState = "startFailed"
            SpikeLog.write("LA", "start FAILED (\(reason)) from \(Self.name(UIApplication.shared.applicationState)): \(error)")
        }
    }

    private func updateActivity() async {
        guard let activity, activity.activityState == .active || activity.activityState == .stale else {
            startActivity(reason: "beat-restart")
            return
        }
        let state = MonitoringAttributes.ContentState(
            rooms: 2, lastBeat: .now, status: renderer.alarming.load(ordering: .relaxed) ? "ALARM" : "Quiet", beats: beats)
        await activity.update(
            ActivityContent(state: state, staleDate: .now.addingTimeInterval(TimeInterval(beatSeconds * 4))))
    }

    // MARK: alert lab

    func armTrigger() {
        let fireAt = Date().addingTimeInterval(TimeInterval(triggerDelay))
        pendingTrigger = fireAt
        SpikeLog.write(
            "ALERT",
            "armed: fires in \(triggerDelay)s (alarmKit=\(useAlarmKit) timeSensitive=\(useTimeSensitive) ownSound=\(useOwnSound) vibrate=\(useVibrate))"
        )
        Task { [weak self] in
            try? await Task.sleep(until: .now + .seconds(self?.triggerDelay ?? 30))
            await self?.fire()
        }
    }

    private func fire() async {
        pendingTrigger = nil
        let app = UIApplication.shared
        SpikeLog.write(
            "ALERT",
            "FIRE from app=\(Self.name(app.applicationState)) locked=\(app.isProtectedDataAvailable ? 0 : 1) eng=\(engine.isRunning ? "on" : "OFF") vol=\(String(format: "%.2f", session.outputVolume)) otherAudio=\(session.isOtherAudioPlaying ? 1 : 0)"
        )
        if useAlarmKit { await fireAlarmKit() }
        if useTimeSensitive { postTimeSensitive() }
        if useOwnSound { playOwnSound() }
        if useVibrate { vibrate() }
    }

    private func fireAlarmKit() async {
        let alert = AlarmPresentation.Alert(
            title: "Nursery is loud",
            secondaryButton: AlarmButton(text: "Snooze", textColor: .white, systemImageName: "zzz"),
            secondaryButtonBehavior: .countdown
        )
        let attributes = AlarmAttributes<SpikeAlarmMetadata>(
            presentation: AlarmPresentation(alert: alert, countdown: .init(title: "Snoozed")),
            metadata: SpikeAlarmMetadata(room: "Nursery"),
            tintColor: .orange
        )
        func config(at date: Date) -> AlarmManager.AlarmConfiguration<SpikeAlarmMetadata> {
            AlarmManager.AlarmConfiguration<SpikeAlarmMetadata>(
                countdownDuration: .init(preAlert: nil, postAlert: 60),
                schedule: .fixed(date),
                attributes: attributes
            )
        }
        let start = Date()
        do {
            let alarm = try await AlarmManager.shared.schedule(id: UUID(), configuration: config(at: Date()))
            SpikeLog.write("ALARMKIT", "scheduled fixed(now) in \(Int(Date().timeIntervalSince(start) * 1000))ms → state=\(alarm.state) id=\(alarm.id)")
        } catch {
            SpikeLog.write("ALARMKIT", "schedule fixed(now) FAILED: \(error); retrying at now+1s")
            do {
                let alarm = try await AlarmManager.shared.schedule(id: UUID(), configuration: config(at: Date().addingTimeInterval(1)))
                SpikeLog.write("ALARMKIT", "scheduled fixed(now+1s) → state=\(alarm.state) id=\(alarm.id)")
            } catch {
                SpikeLog.write("ALARMKIT", "schedule fixed(now+1s) FAILED: \(error)")
            }
        }
    }

    private func observeAlarms() {
        Task {
            var known: [UUID: Alarm.State] = [:]
            for await alarms in AlarmManager.shared.alarmUpdates {
                let now = Dictionary(uniqueKeysWithValues: alarms.map { ($0.id, $0.state) })
                // The dead-man is rescheduled every beat; only its firing is news.
                let deadMan = UserDefaults.standard.string(forKey: "alarmDeadManID")
                for (id, state) in now where known[id] != state && !(state == .scheduled && known[id] == nil) {
                    SpikeLog.write("ALARMKIT", "update id=\(id) → \(state)\(id.uuidString == deadMan ? " (DEAD-MAN)" : "")")
                }
                for (id, state) in known where now[id] == nil && state != .scheduled {
                    SpikeLog.write("ALARMKIT", "update id=\(id) → removed (stopped or dismissed)")
                }
                known = now
            }
        }
    }

    /// Finds the per-app alarm limit by scheduling far-future alarms until
    /// AlarmKit refuses, then cancels them all.
    func probeAlarmLimit() async {
        var ids: [UUID] = []
        let attributes = AlarmAttributes<SpikeAlarmMetadata>(
            presentation: AlarmPresentation(alert: .init(title: "Limit probe")), metadata: nil, tintColor: .gray)
        for i in 0..<200 {
            let id = UUID()
            let config = AlarmManager.AlarmConfiguration<SpikeAlarmMetadata>.alarm(
                schedule: .fixed(Date().addingTimeInterval(TimeInterval(86_400 * 30 + i * 60))), attributes: attributes)
            do {
                _ = try await AlarmManager.shared.schedule(id: id, configuration: config)
                ids.append(id)
            } catch {
                SpikeLog.write("ALARMKIT", "limit probe: refused after \(ids.count) alarms: \(error)")
                break
            }
        }
        if ids.count == 200 { SpikeLog.write("ALARMKIT", "limit probe: 200 alarms accepted, no limit hit") }
        for id in ids { try? AlarmManager.shared.cancel(id: id) }
    }

    private func postTimeSensitive() {
        let content = UNMutableNotificationContent()
        content.title = "Nursery is loud"
        content.body = "Sound above the threshold for 3 s."
        content.sound = UNNotificationSound(named: UNNotificationSoundName("alarm.caf"))
        content.interruptionLevel = .timeSensitive
        content.relevanceScore = 1
        let request = UNNotificationRequest(identifier: "alert-\(Int(Date().timeIntervalSince1970))", content: content, trigger: nil)
        Task {
            do {
                try await UNUserNotificationCenter.current().add(request)
                SpikeLog.write("NOTIF", "time-sensitive posted")
            } catch {
                SpikeLog.write("NOTIF", "time-sensitive post FAILED: \(error)")
            }
        }
    }

    private func playOwnSound() {
        if !engine.isRunning {
            SpikeLog.write("AUDIO", "own sound: engine was off, starting")
            startAudio(reason: "alarm")
        }
        renderer.alarming.store(true, ordering: .relaxed)
        SpikeLog.write("AUDIO", "own alarm tone ON at media volume \(String(format: "%.2f", session.outputVolume)) (60 s)")
        ownSoundStop?.cancel()
        ownSoundStop = Task { [weak self] in
            try? await Task.sleep(for: .seconds(60))
            guard !Task.isCancelled else { return }
            self?.renderer.alarming.store(false, ordering: .relaxed)
            SpikeLog.write("AUDIO", "own alarm tone OFF (timeout)")
        }
    }

    private func vibrate() {
        SpikeLog.write("VIBRATE", "start (10 pulses)")
        Task {
            for _ in 0..<10 {
                AudioServicesPlayAlertSound(kSystemSoundID_Vibrate)
                try? await Task.sleep(for: .seconds(1))
            }
        }
    }

    func stopAlarms() {
        renderer.alarming.store(false, ordering: .relaxed)
        ownSoundStop?.cancel()
        if let alarms = try? AlarmManager.shared.alarms {
            for alarm in alarms { try? AlarmManager.shared.cancel(id: alarm.id) }
        }
        UNUserNotificationCenter.current().removeAllDeliveredNotifications()
        SpikeLog.write("ALERT", "stopped by user in app")
    }

    func requestPermissions() async {
        do {
            let granted = try await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge])
            SpikeLog.write("PERM", "notifications granted=\(granted)")
        } catch {
            SpikeLog.write("PERM", "notifications error: \(error)")
        }
        do {
            let state = try await AlarmManager.shared.requestAuthorization()
            SpikeLog.write("PERM", "alarmKit → \(state)")
        } catch {
            SpikeLog.write("PERM", "alarmKit error: \(error)")
        }
    }

    // MARK: system observers

    private func observe() {
        let center = NotificationCenter.default
        func on(_ name: Notification.Name, _ handler: @escaping @MainActor ([AnyHashable: Any]?) -> Void) {
            observers.append(center.addObserver(forName: name, object: nil, queue: .main) { note in
                nonisolated(unsafe) let info = note.userInfo
                MainActor.assumeIsolated { handler(info) }
            })
        }
        on(AVAudioSession.interruptionNotification) { [weak self] info in
            let type = (info?[AVAudioSessionInterruptionTypeKey] as? UInt).flatMap(AVAudioSession.InterruptionType.init)
            let reason = (info?[AVAudioSessionInterruptionReasonKey] as? UInt) ?? 99
            let options = AVAudioSession.InterruptionOptions(rawValue: (info?[AVAudioSessionInterruptionOptionKey] as? UInt) ?? 0)
            switch type {
            case .began:
                SpikeLog.write("AUDIO", "interruption BEGAN reason=\(reason)")
                self?.engineRunning = false
            case .ended:
                SpikeLog.write("AUDIO", "interruption ENDED shouldResume=\(options.contains(.shouldResume))")
                if self?.running == true { self?.startAudio(reason: "interruption-ended") }
            default:
                SpikeLog.write("AUDIO", "interruption unknown type")
            }
        }
        on(AVAudioSession.routeChangeNotification) { [weak self] info in
            let reason = (info?[AVAudioSessionRouteChangeReasonKey] as? UInt) ?? 99
            SpikeLog.write("AUDIO", "route change reason=\(reason) outputs=\(self?.session.currentRoute.outputs.map(\.portType.rawValue) ?? [])")
        }
        on(AVAudioSession.mediaServicesWereResetNotification) { [weak self] _ in
            SpikeLog.write("AUDIO", "media services were RESET")
            if self?.running == true { self?.startAudio(reason: "media-reset") }
        }
        on(AVAudioSession.silenceSecondaryAudioHintNotification) { info in
            SpikeLog.write("AUDIO", "silence secondary hint type=\((info?[AVAudioSessionSilenceSecondaryAudioHintTypeKey] as? UInt) ?? 99)")
        }
        on(.AVAudioEngineConfigurationChange) { [weak self] _ in
            SpikeLog.write("AUDIO", "engine configuration change")
            if self?.running == true { self?.startAudio(reason: "config-change") }
        }
        on(UIApplication.didEnterBackgroundNotification) { _ in SpikeLog.write("APP", "did enter background") }
        on(UIApplication.willEnterForegroundNotification) { _ in SpikeLog.write("APP", "will enter foreground") }
        on(UIApplication.didReceiveMemoryWarningNotification) { _ in
            SpikeLog.write("APP", "MEMORY WARNING memLeft=\(os_proc_available_memory() / 1_048_576)MB")
        }
        on(UIApplication.willTerminateNotification) { _ in SpikeLog.write("APP", "will terminate") }
        on(UIApplication.protectedDataWillBecomeUnavailableNotification) { _ in SpikeLog.write("APP", "device LOCKED (protected data unavailable)") }
        on(UIApplication.protectedDataDidBecomeAvailableNotification) { _ in SpikeLog.write("APP", "device UNLOCKED") }
        on(.NSProcessInfoPowerStateDidChange) { _ in
            SpikeLog.write("POWER", "low power mode=\(ProcessInfo.processInfo.isLowPowerModeEnabled)")
        }
        on(ProcessInfo.thermalStateDidChangeNotification) { _ in
            SpikeLog.write("POWER", "thermal state=\(ProcessInfo.processInfo.thermalState.rawValue)")
        }
        on(UIDevice.batteryStateDidChangeNotification) { _ in
            SpikeLog.write("POWER", "battery state=\(Self.name(UIDevice.current.batteryState)) level=\(UIDevice.current.batteryLevel)")
        }
    }

    // MARK: helpers

    nonisolated static func probe(host: String, port: UInt16) async -> String {
        let start = Date()
        let connection = NWConnection(host: NWEndpoint.Host(host), port: NWEndpoint.Port(rawValue: port)!, using: .tcp)
        let once = Once()
        return await withCheckedContinuation { continuation in
            connection.stateUpdateHandler = { state in
                let result: String? =
                    switch state {
                    case .ready: "ok(\(Int(Date().timeIntervalSince(start) * 1000))ms)"
                    case .failed(let error): "failed(\(error))"
                    case .waiting(let error): "waiting(\(error))"
                    default: nil
                    }
                if let result, once.claim() {
                    connection.cancel()
                    continuation.resume(returning: result)
                }
            }
            connection.start(queue: .global())
            DispatchQueue.global().asyncAfter(deadline: .now() + 3) {
                if once.claim() {
                    connection.cancel()
                    continuation.resume(returning: "timeout")
                }
            }
        }
    }

    static func name(_ state: UIApplication.State) -> String {
        switch state {
        case .active: "fg"
        case .inactive: "inactive"
        case .background: "bg"
        @unknown default: "?"
        }
    }

    static func name(_ state: UIDevice.BatteryState) -> String {
        switch state {
        case .charging: "charging"
        case .full: "full"
        case .unplugged: "unplugged"
        default: "unknown"
        }
    }
}

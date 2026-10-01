import Foundation
import Observation
import os

/// Rings the alarm and posts the cards: the counterpart of Android's
/// `AlertSignaler` and of the alert half of its `MonitoringService`
/// (`raiseAlert`, `withdrawAlert`, `dropAlert`, the failure card).
///
/// `AlertSignalerState` decides what the alarm is doing (which room, the
/// latch, the ramp and bursts, the 5-minute give-up); this carries it out.
/// With AlarmKit authorised the alarm is an AlarmKit alarm, which rings
/// through silent mode and Sleep Focus but at the system alarm's volume and
/// without bursts, so only the signaler's room, latch and give-up apply to
/// it. Otherwise it is the fallback: the app's own tone through the running
/// engine, following every rule, next to a time-sensitive card
/// (shared/spec/alerts-and-sound-modes.md, "The sound alert").
@MainActor
@Observable
final class AlertCenter {
    /// What rings and posts, behind seams the tests fake.
    struct Delivery {
        var alarms: any AlarmAlerting
        var access: any AlertAccess
        var tone: any AlarmTonePlayer
        var vibrator: any AlarmVibrator
        var notices: MonitoringNotices
        var deadMan: any DeadManArming
    }

    /// The room (or the monitor's failure) the alarm is for, while it sounds.
    var alarmingCameraId: String? { signaler.alarmingCameraId }
    var isAlarming: Bool { signaler.isAlarming }
    /// The room whose card is up, if any.
    private(set) var cardCameraId: String?

    @ObservationIgnored let delivery: Delivery
    @ObservationIgnored private let scheduler: any MonotonicScheduler
    @ObservationIgnored private var signaler = AlertSignalerState()
    @ObservationIgnored private var ticker: ScheduledAction?
    @ObservationIgnored private var names: [String: String] = [:]
    @ObservationIgnored private var failureTitle = ""
    /// Whether the current alarm rings through AlarmKit rather than the tone.
    @ObservationIgnored private var viaAlarmKit = false
    @ObservationIgnored private var following: Task<Void, Never>?
    /// Bumped whenever the alarm stops, so a raise queued before the stop
    /// cannot ring after it.
    @ObservationIgnored private var generation = 0
    @ObservationIgnored private var lastNotice: Task<Void, Never>?
    /// Counts every AlarmKit raise: only the latest may change delivery.
    @ObservationIgnored private var raises = 0
    /// The failures the card last said, so a change in their details (the
    /// battery lower, a camera renamed) updates it quietly.
    @ObservationIgnored private var cardFailures: [FailureReason]?

    private static let log = Logger(subsystem: "app.dozecam", category: "alerts")

    init(delivery: Delivery, scheduler: any MonotonicScheduler = ContinuousScheduler.shared) {
        self.delivery = delivery
        self.scheduler = scheduler
        following = Task { [weak self] in await self?.followAcknowledgements() }
    }

    // MARK: - Rooms

    /// A room's alert, down the one path, once its listen-mode rules have
    /// been weighed by the caller: the card first, then the alarm when it
    /// `sounds`. `prominent` is `alertWakesScreen`: on iOS a card that may
    /// not wake the screen is posted without interrupting.
    func raiseRoom(cameraId: String, name: String, sounds: Bool, prominent: Bool, settings: AppSettings) {
        names[cameraId] = name
        cardCameraId = cameraId
        let notices = delivery.notices
        enqueue { _ = await notices.postSoundAlert(cameraId: cameraId, roomName: name, prominent: prominent) }
        guard sounds else { return }
        signal(cameraId, settings: settings)
    }

    /// A room left the monitored set: its alarm stops and its card goes. No
    /// other room's alert is touched.
    func withdraw(cameraId: String) {
        perform(signaler.stop(ifAlarming: cameraId))
        if cardCameraId == cameraId {
            cardCameraId = nil
            let notices = delivery.notices
            enqueue { notices.removeSoundAlert() }
        }
    }

    /// A person is here: a touch on the viewer, a card opened or dismissed,
    /// or the alarm stopped from the lock screen. The alarm stops; the card
    /// stays until it is dealt with.
    func acknowledge() {
        guard signaler.isAlarming else { return }
        perform(signaler.acknowledge())
    }

    // MARK: - Failures

    /// The failure ledger's verdict, carried out.
    func apply(_ action: FailureAnnouncer.Action, wording: FailureWording, settings: AppSettings) {
        switch action {
        case .none:
            break
        case .raise(let failures):
            cardFailures = failures.map(\.reason)
            failureTitle = wording.cardTitle(failures)
            post(failures, wording: wording, announce: true)
            signal(AlertSignalerState.monitoringFailure, settings: settings)
        case .refresh(let failures):
            cardFailures = failures.map(\.reason)
            failureTitle = wording.cardTitle(failures)
            post(failures, wording: wording, announce: false)
        case .clear:
            cardFailures = nil
            let notices = delivery.notices
            enqueue { notices.removeFailure() }
            perform(signaler.stopFailure())
        }
    }

    /// The card, updated without sounding or waking anyone, when what it
    /// lists has changed in its details (shared/spec/failure-alerts.md: "its
    /// details follow along").
    func follow(_ failures: [MonitoringFailure], wording: FailureWording, settings: AppSettings) {
        guard let cardFailures, !failures.isEmpty, failures.map(\.reason) != cardFailures else { return }
        apply(.refresh(failures), wording: wording, settings: settings)
    }

    private func post(_ failures: [MonitoringFailure], wording: FailureWording, announce: Bool) {
        let notices = delivery.notices
        let title = wording.cardTitle(failures)
        let lines = failures.map(wording.detail)
        enqueue { _ = await notices.postFailure(title: title, lines: lines, announce: announce) }
    }

    /// Charger pulled while armed: a quiet notice, never an alarm.
    func unplugged(percent: Int) {
        let notices = delivery.notices
        enqueue { _ = await notices.postUnplugged(percent: percent) }
    }

    // MARK: - Everything

    /// Alerts switched off: nothing may reach anyone, so the alarm stops and
    /// the room's and the failure's cards go.
    func dropAll() {
        perform(signaler.stop())
        cardCameraId = nil
        cardFailures = nil
        let notices = delivery.notices
        enqueue {
            notices.removeSoundAlert()
            notices.removeFailure()
        }
        // The dead-man is an announcement too: nothing may wake anyone.
        disarmDeadMan()
    }

    /// Exit: nothing monitoring posted may outlive it, the dead-man included.
    func exit() {
        perform(signaler.stop())
        cardCameraId = nil
        cardFailures = nil
        let notices = delivery.notices
        enqueue { notices.removeAll() }
        disarmDeadMan()
    }

    /// Pushes the dead-man back, only while alerts may reach anyone: with
    /// them off, the app dying must not wake anyone either.
    func heartbeat(alertsEnabled: Bool) {
        guard alertsEnabled else { return disarmDeadMan() }
        let deadMan = delivery.deadMan
        Task { await deadMan.heartbeat() }
    }

    private func disarmDeadMan() {
        let deadMan = delivery.deadMan
        Task { await deadMan.disarm() }
    }

    /// Posts and removals, one after another in the order asked: a card
    /// withdrawn while its post is still on its way must not appear after.
    private func enqueue(_ operation: @escaping @MainActor () async -> Void) {
        let previous = lastNotice
        lastNotice = Task {
            await previous?.value
            await operation()
        }
    }

    /// Waits for every card operation asked so far; tests read the result.
    func flushNotices() async {
        await lastNotice?.value
    }

    // MARK: - The alarm

    private func signal(_ cameraId: String, settings: AppSettings) {
        // Delivery is chosen when an alarm starts and kept for its run: an
        // alarm that fell back to the tone stays on it, rather than a later
        // trigger scheduling AlarmKit beside a tone still sounding.
        if !signaler.isAlarming { viaAlarmKit = delivery.access.alarms == .authorized }
        perform(signaler.signal(cameraId: cameraId, settings: settings, nowMs: scheduler.nowMs))
        ringAlarmKit()
        startTicking()
    }

    /// Points AlarmKit at whatever the signaler says is alarming: a room's
    /// trigger over another's re-points it, a failure during a cry leaves it.
    private func ringAlarmKit() {
        guard viaAlarmKit, let id = signaler.alarmingCameraId else { return }
        let subject: AlertSubject =
            switch id {
            case AlertSignalerState.monitoringFailure: .failure(title: failureTitle)
            case AlertSignalerState.testCameraId: .test
            default: .room(cameraId: id, name: names[id] ?? id)
            }
        guard delivery.alarms.ringing != subject else { return }
        let alarms = delivery.alarms
        let raisedIn = generation
        raises += 1
        let raise = raises
        Task { [weak self] in
            // Answered, withdrawn or exited before this ran: nothing to ring.
            guard let self, generation == raisedIn, signaler.isAlarming else { return }
            do {
                try await alarms.raise(subject)
            } catch {
                // An alarm answered, or re-pointed at another room, meanwhile
                // is not this one's to change.
                guard generation == raisedIn, raises == raise else { return }
                // AlarmKit refused: the tone takes over, so the room is
                // still heard.
                Self.log.error("AlarmKit refused an alarm; falling back to the tone")
                fallBack()
            }
        }
    }

    private func fallBack() {
        guard viaAlarmKit, signaler.isAlarming else { return }
        viaAlarmKit = false
        // AlarmKit keeps an alarm it failed to replace; two alarms for two
        // different things must never sound at once.
        delivery.alarms.stop()
        perform(signaler.burstNow(nowMs: scheduler.nowMs))
    }

    private func startTicking() {
        guard ticker == nil, signaler.isAlarming else { return }
        tick()
    }

    private func tick() {
        ticker = nil
        perform(signaler.tick(nowMs: scheduler.nowMs))
        guard signaler.isAlarming else { return }
        ticker = scheduler.schedule(after: AlertSignalerState.tickMs) { [weak self] in self?.tick() }
    }

    private func perform(_ actions: [AlertSignalerState.Action]) {
        for action in actions {
            switch action {
            case .burst(let tone, let volume):
                // AlarmKit rings on its own; the tone is the fallback's.
                guard !viaAlarmKit else { continue }
                _ = delivery.tone.start(tone == .failure ? .failure : .room, volume: volume)
            case .setVolume(let volume):
                if !viaAlarmKit { delivery.tone.setVolume(volume) }
            case .vibrate:
                if !viaAlarmKit { delivery.vibrator.pulse() }
            case .stop:
                generation += 1
                ticker?.cancel()
                ticker = nil
                delivery.tone.stop()
                delivery.vibrator.cancel()
                delivery.alarms.stop()
            }
        }
    }

    /// The system's Stop on the lock screen is a person answering.
    private func followAcknowledgements() async {
        for await _ in delivery.alarms.acknowledgements {
            guard signaler.isAlarming else { continue }
            perform(signaler.acknowledge())
        }
    }
}

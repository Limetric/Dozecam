/// Alert delivery that rings and posts nothing: the monitor's default where
/// nothing real is wired in (tests of other parts, previews, the fake-camera
/// debug launch). The app wires the real one in `DozecamApp`.
extension AlertCenter.Delivery {
    @MainActor static func inert() -> AlertCenter.Delivery {
        let notices = InertNoticeCenter()
        return AlertCenter.Delivery(
            alarms: InertAlarms(), access: InertAccess(), tone: InertTone(), vibrator: InertVibrator(),
            notices: MonitoringNotices(center: notices), deadMan: InertDeadMan())
    }

    /// The real delivery: AlarmKit, notifications and the fallback tone
    /// through `speaker`.
    @MainActor static func live(speaker: Speaker) -> AlertCenter.Delivery {
        let scheduler = SystemAlarmScheduler()
        let access = SystemAlertAccess()
        let center = SystemNoticeCenter()
        let tone = SpeakerAlarmPlayer(speaker: speaker)
        tone.preload()
        return AlertCenter.Delivery(
            alarms: AlarmKitAlerts(scheduler: scheduler), access: access, tone: tone, vibrator: SystemAlarmVibrator(),
            notices: MonitoringNotices(center: center),
            deadMan: DeadManSwitch(alarms: scheduler, notices: center, access: access))
    }
}

@MainActor
private final class InertAlarms: AlarmAlerting {
    var ringing: AlertSubject? { nil }
    func raise(_ subject: AlertSubject) async throws {}
    func stop() {}
    let acknowledgements = AsyncStream<AlertSubject> { _ in }
}

@MainActor
private final class InertAccess: AlertAccess {
    var alarms: AlarmAuthorization { .notDetermined }
    func requestAlarms() async -> AlarmAuthorization { .notDetermined }
    func notifications() async -> NotificationGrant { .allowed }
    func requestNotifications() async -> NotificationGrant { .allowed }
}

@MainActor
private final class InertTone: AlarmTonePlayer {
    func start(_ tone: AlarmTone, volume: Float) -> Bool { false }
    func setVolume(_ volume: Float) {}
    func stop() {}
    var isPlaying: Bool { false }
}

@MainActor
private final class InertVibrator: AlarmVibrator {
    func pulse() {}
    func cancel() {}
}

@MainActor
private final class InertNoticeCenter: NoticeCenter {
    func post(_ notice: LocalNotice) async throws {}
    func remove(ids: [String]) {}
}

@MainActor
private final class InertDeadMan: DeadManArming {
    func heartbeat() async {}
    func disarm() async {}
}

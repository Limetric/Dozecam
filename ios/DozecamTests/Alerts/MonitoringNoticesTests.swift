import Testing
import UserNotifications

@testable import Dozecam

@MainActor
struct MonitoringNoticesTests {
    let center = FakeNoticeCenter()
    let notices: MonitoringNotices

    init() {
        notices = MonitoringNotices(center: center)
    }

    // MARK: - What each card says

    @Test func theSoundAlertNamesTheRoomOpensItAndIsSilent() async throws {
        #expect(await notices.postSoundAlert(cameraId: "cam-1", roomName: "Nursery"))

        let notice = try #require(center.showing[MonitoringNotices.alertId])
        #expect(notice.title == "Sound detected — Nursery")
        #expect(notice.level == .timeSensitive)
        #expect(notice.sound == .none)
        #expect(notice.route == .room(cameraId: "cam-1"))
        #expect(notice.category == NotificationRouter.alertCategory)
        #expect(notice.delay == nil)
    }

    @Test func theTestAlertIsTheSameCardInOtherWords() {
        let real = MonitoringNotices.soundAlert(cameraId: "cam-1", roomName: "Nursery")
        let test = MonitoringNotices.soundAlert(cameraId: "cam-1", roomName: "Nursery", test: true)

        #expect(test.title == "Dozecam test alert")
        #expect(!test.body.contains("Nursery"))
        #expect(test.id == real.id && test.level == real.level && test.route == real.route)
    }

    @Test func aFailureIsAnnouncedTimeSensitiveAndUpdatedQuietly() async throws {
        await notices.postFailure(title: "Can't reach Nursery", lines: ["a", "b"], announce: true)
        let announced = try #require(center.showing[MonitoringNotices.failureId])
        #expect(announced.level == .timeSensitive)
        #expect(announced.body == "a\nb")
        #expect(announced.route == .failure)
        #expect(announced.sound == .none)

        await notices.postFailure(title: "Can't reach Nursery", lines: ["b"], announce: false)
        let updated = try #require(center.showing[MonitoringNotices.failureId])
        #expect(updated.level == .passive)
        #expect(updated.body == "b")
        #expect(center.showing.count == 1)
    }

    @Test func theUnpluggedNoticeIsQuietAndNamesBothLevels() async throws {
        await notices.postUnplugged(percent: 80)
        let notice = try #require(center.showing[MonitoringNotices.unpluggedId])
        #expect(notice.level == .passive)
        #expect(notice.sound == .none)
        #expect(notice.body.contains("80%") && notice.body.contains("25%"))
        #expect(notice.category == nil)
    }

    @Test func exitTakesEveryCardDown() async {
        await notices.postSoundAlert(cameraId: "cam-1", roomName: "Nursery")
        await notices.postFailure(title: "t", lines: [], announce: true)
        await notices.postUnplugged(percent: 50)

        notices.removeAll()

        #expect(center.showing.isEmpty)
    }

    @Test func eachCardComesDownOnItsOwn() async {
        await notices.postSoundAlert(cameraId: "cam-1", roomName: "Nursery")
        await notices.postFailure(title: "t", lines: [], announce: true)

        notices.removeSoundAlert()
        #expect(center.showing.keys.sorted() == [MonitoringNotices.failureId])
        notices.removeFailure()
        #expect(center.showing.isEmpty)
    }

    @Test func aRefusedPostSaysSo() async {
        center.refuse = true
        #expect(!(await notices.postSoundAlert(cameraId: "cam-1", roomName: "Nursery")))
    }

    // MARK: - The system's request

    @Test func theRequestCarriesEverything() throws {
        let notice = LocalNotice(
            id: "x", title: "T", body: "B", level: .timeSensitive, sound: .named("monitoring_failure.caf"),
            route: .room(cameraId: "cam-1"), delay: 180, category: "cat")

        let request = notice.request()

        #expect(request.identifier == "x")
        #expect(request.content.title == "T" && request.content.body == "B")
        #expect(request.content.interruptionLevel == .timeSensitive)
        #expect(request.content.sound != nil)
        #expect(request.content.categoryIdentifier == "cat")
        #expect(AlertRoute(userInfo: request.content.userInfo) == .room(cameraId: "cam-1"))
        let trigger = try #require(request.trigger as? UNTimeIntervalNotificationTrigger)
        #expect(trigger.timeInterval == 180 && !trigger.repeats)
    }

    @Test func aPassiveSilentNoticeHasNoSoundAndNoTrigger() {
        let request = MonitoringNotices.unplugged(percent: 40).request()
        #expect(request.content.interruptionLevel == .passive)
        #expect(request.content.sound == nil)
        #expect(request.trigger == nil)
    }

    // MARK: - Routing

    @Test(arguments: [AlertRoute.room(cameraId: "cam-1"), .failure, .viewer])
    func routesSurviveUserInfo(route: AlertRoute) {
        let userInfo: [AnyHashable: Any] = route.userInfo
        #expect(AlertRoute(userInfo: userInfo) == route)
    }

    @Test func notOursHasNoRoute() {
        #expect(AlertRoute(userInfo: [:]) == nil)
        #expect(AlertRoute(userInfo: ["dozecam.route": "room"]) == nil)
    }

    @Test func tapsOpenAndSwipesDismiss() {
        let userInfo: [AnyHashable: Any] = AlertRoute.room(cameraId: "cam-1").userInfo
        #expect(
            NoticeResponse(actionIdentifier: UNNotificationDefaultActionIdentifier, userInfo: userInfo)
                == .opened(.room(cameraId: "cam-1")))
        #expect(
            NoticeResponse(actionIdentifier: UNNotificationDismissActionIdentifier, userInfo: userInfo)
                == .dismissed(.room(cameraId: "cam-1")))
        #expect(NoticeResponse(actionIdentifier: "other", userInfo: userInfo) == nil)
        #expect(NoticeResponse(actionIdentifier: UNNotificationDefaultActionIdentifier, userInfo: [:]) == nil)
    }

    @Test func inFrontAPassiveNoticeGoesToTheListOnly() {
        #expect(NotificationRouter.presentation(for: .passive) == [.list])
        #expect(NotificationRouter.presentation(for: .timeSensitive).contains(.banner))
    }
}

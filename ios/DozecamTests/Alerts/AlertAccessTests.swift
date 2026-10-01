import Testing

@testable import Dozecam

@MainActor
struct AlertAccessTests {
    @Test func notificationsAreBlockedUnlessAllowed() {
        #expect(NotificationGrant.allowed.canPost)
        #expect(!NotificationGrant.denied.canPost)
        #expect(!NotificationGrant(status: .notDetermined, timeSensitive: false, sound: false).canPost)
    }

    /// Asking is the user's to start: the fake answers only what was never
    /// asked, as the system does.
    @Test func askingAnswersOnlyTheUndecided() async {
        let access = FakeAlertAccess()
        access.alarms = .denied
        #expect(await access.requestAlarms() == .denied)
        access.alarms = .notDetermined
        #expect(await access.requestAlarms() == .authorized)
    }

    /// The real grants can be read in the simulator; what they say depends on
    /// the simulator, so only that reading them does not hang or crash.
    @Test func theSystemGrantsCanBeRead() async {
        let access = SystemAlertAccess()
        _ = access.alarms
        _ = await access.notifications()
    }
}

import Testing

@testable import Dozecam

@MainActor
struct InactivityReturnTests {
    let scheduler = ManualScheduler()

    @Test func aSingleCameraHandsBackTheScreenAfterAMinute() {
        #expect(InactivityCountdown.timeoutMs == 60_000)
        var expired = 0
        let countdown = InactivityCountdown(scheduler: scheduler) { expired += 1 }
        countdown.start()
        #expect(countdown.remainingSeconds == 60)
        #expect(countdown.fraction == 1)

        scheduler.advance(by: 30_000)
        #expect(countdown.remainingSeconds == 30)
        #expect(countdown.fraction == 0.5)
        scheduler.advance(by: 29_000)
        #expect(countdown.remainingSeconds == 1)
        #expect(expired == 0)
        scheduler.advance(by: 1_000)
        #expect(expired == 1)
        #expect(countdown.remainingSeconds == 0)
        #expect(!countdown.isRunning)
    }

    @Test func aTouchGivesTheWholeMinuteAgain() {
        var expired = 0
        let countdown = InactivityCountdown(scheduler: scheduler) { expired += 1 }
        countdown.start()
        scheduler.advance(by: 50_000)
        countdown.reset()
        #expect(countdown.remainingSeconds == 60)
        scheduler.advance(by: 59_000)
        #expect(expired == 0)
        scheduler.advance(by: 1_000)
        #expect(expired == 1)
    }

    @Test func timeStoppedDoesNotCountAndComingBackIsAFreshMinute() {
        var expired = 0
        let countdown = InactivityCountdown(scheduler: scheduler) { expired += 1 }
        countdown.start()
        scheduler.advance(by: 45_000)
        countdown.stop()  // the app went to the background
        scheduler.advance(by: 600_000)
        #expect(expired == 0)
        countdown.reset()  // a touch while stopped starts nothing
        #expect(!countdown.isRunning)

        countdown.start()  // back in the foreground
        #expect(countdown.remainingSeconds == 60)
        scheduler.advance(by: 60_000)
        #expect(expired == 1)
    }

    @Test func theReadoutRoundsUp() {
        let countdown = InactivityCountdown(timeoutMs: 1_500, scheduler: scheduler) {}
        countdown.start()
        #expect(countdown.remainingSeconds == 2)
        scheduler.advance(by: 1_000)
        #expect(countdown.remainingSeconds == 1)
    }
}

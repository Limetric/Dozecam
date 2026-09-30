import Testing

@testable import Dozecam

@MainActor
struct SoundRotationTests {
    let scheduler = ManualScheduler()

    @Test func nextGoesRoundInGridOrder() {
        let ids = ["a", "b", "c"]
        #expect(SoundRotation.next(after: "a", in: ids) == "b")
        #expect(SoundRotation.next(after: "c", in: ids) == "a")
    }

    @Test func aCameraThatWentAwayHandsTheTurnToTheFirst() {
        #expect(SoundRotation.next(after: "gone", in: ["a", "b"]) == "a")
        #expect(SoundRotation.next(after: nil, in: ["a", "b"]) == "a")
        #expect(SoundRotation.next(after: "a", in: []) == nil)
    }

    @Test func eachCameraHasTenSecondsInTurn() {
        #expect(SoundRotation.intervalMs == 10_000)
        var changes = 0
        let rotation = SoundRotation(scheduler: scheduler) { changes += 1 }
        rotation.update(cameraIds: ["a", "b", "c"], enabled: true)
        #expect(rotation.current == "a")

        scheduler.advance(by: 9_999)
        #expect(rotation.current == "a")
        scheduler.advance(by: 1)
        #expect(rotation.current == "b")
        scheduler.advance(by: 10_000)
        #expect(rotation.current == "c")
        scheduler.advance(by: 10_000)
        #expect(rotation.current == "a")
        #expect(changes == 3)
    }

    @Test func offMeansSilence() {
        let rotation = SoundRotation(scheduler: scheduler) {}
        rotation.update(cameraIds: ["a", "b"], enabled: false)
        #expect(rotation.current == nil)
        rotation.update(cameraIds: ["a", "b"], enabled: true)
        #expect(rotation.current == "a")
        rotation.update(cameraIds: ["a", "b"], enabled: false)
        #expect(rotation.current == nil)
        scheduler.advance(by: 60_000)
        #expect(rotation.current == nil)
    }

    @Test func theCurrentCameraKeepsItsTurnAcrossAnUnrelatedUpdate() {
        let rotation = SoundRotation(scheduler: scheduler) {}
        rotation.update(cameraIds: ["a", "b", "c"], enabled: true)
        scheduler.advance(by: 10_000)
        #expect(rotation.current == "b")

        // Another camera scrolls into view: "b" keeps the sound, and its turn
        // starts over rather than being cut short.
        scheduler.advance(by: 5_000)
        rotation.update(cameraIds: ["a", "b", "c", "d"], enabled: true)
        #expect(rotation.current == "b")
        scheduler.advance(by: 9_999)
        #expect(rotation.current == "b")
        scheduler.advance(by: 1)
        #expect(rotation.current == "c")
    }

    @Test func theAudibleCameraGoingAwayStartsTheRoundAgain() {
        let rotation = SoundRotation(scheduler: scheduler) {}
        rotation.update(cameraIds: ["a", "b", "c"], enabled: true)
        scheduler.advance(by: 10_000)
        #expect(rotation.current == "b")
        rotation.update(cameraIds: ["a", "c"], enabled: true)
        #expect(rotation.current == "a")
    }

    @Test func noCamerasIsSilence() {
        let rotation = SoundRotation(scheduler: scheduler) {}
        rotation.update(cameraIds: [], enabled: true)
        #expect(rotation.current == nil)
        #expect(scheduler.pendingCount == 0)
    }
}

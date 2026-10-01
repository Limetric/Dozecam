import Foundation

@testable import Dozecam

/// The fallback's speaker, as a record.
@MainActor
final class FakeAlarmTonePlayer: AlarmTonePlayer {
    enum Call: Equatable {
        case start(AlarmTone, Float)
        case volume(Float)
        case stop
    }

    private(set) var calls: [Call] = []
    private(set) var isPlaying = false
    var audible = true

    func start(_ tone: AlarmTone, volume: Float) -> Bool {
        calls.append(.start(tone, volume))
        isPlaying = true
        return audible
    }

    func setVolume(_ volume: Float) {
        calls.append(.volume(volume))
    }

    func stop() {
        calls.append(.stop)
        isPlaying = false
    }
}

@MainActor
final class FakeAlarmVibrator: AlarmVibrator {
    private(set) var pulses = 0
    private(set) var cancels = 0
    func pulse() { pulses += 1 }
    func cancel() { cancels += 1 }
}

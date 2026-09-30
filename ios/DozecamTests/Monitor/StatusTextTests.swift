import Foundation
import Testing

@testable import Dozecam

struct StatusTextTests {
    let now = Date(timeIntervalSince1970: 100_000)

    @Test(arguments: [
        (StatusText.TileState.connection(.live), "LIVE"),
        (.connection(.connecting), "CONNECTING"),
        (.connection(.reconnecting(attempt: 3)), "RECONNECTING (attempt 3)"),
        (.connection(.offline), "OFFLINE"),
        (.unsupported(codec: "AV1"), "CAN'T PLAY"),
    ])
    func labels(state: StatusText.TileState, label: String) {
        #expect(StatusText.label(state) == label)
    }

    @Test func aFrozenPictureCarriesItsAge() {
        let frame = now.addingTimeInterval(-12)
        #expect(
            StatusText.text(.connection(.reconnecting(attempt: 2)), lastFrameAt: frame, now: now)
                == "RECONNECTING (attempt 2) · last frame 12 seconds ago")
        #expect(
            StatusText.text(.connection(.offline), lastFrameAt: frame, now: now)
                == "OFFLINE · last frame 12 seconds ago")
        #expect(
            StatusText.spokenText(.connection(.offline), lastFrameAt: frame, now: now)
                == "Offline, last frame 12 seconds ago")
    }

    @Test func liveHidesTheAgeAndNoFrameHasNone() {
        #expect(StatusText.text(.connection(.live), lastFrameAt: now, now: now) == "LIVE")
        #expect(StatusText.text(.connection(.connecting), lastFrameAt: nil, now: now) == "CONNECTING")
    }

    @Test(arguments: [
        (0.0, "0 seconds ago"), (1, "1 second ago"), (59.9, "59 seconds ago"), (60, "1 minute ago"),
        (3_599, "59 minutes ago"), (3_600, "1 hour ago"), (7_200, "2 hours ago"), (86_400, "1 day ago"),
        (-30, "0 seconds ago"),
    ])
    func agesRoundDown(seconds: Double, text: String) {
        #expect(StatusText.age(from: now.addingTimeInterval(-seconds), to: now) == text)
    }
}

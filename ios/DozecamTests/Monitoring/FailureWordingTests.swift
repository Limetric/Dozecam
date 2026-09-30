import Foundation
import Testing

@testable import Dozecam

/// One set of words for a failure, wherever it is named. The English is
/// Android's (`failure_*` in its `strings.xml`) except where it names
/// something only Android has.
struct FailureWordingTests {
    /// Times as "t<ms>", so every sentence is checked whole.
    private let wording = FailureWording { "t\($0)" }

    private let nursery = FailureReason.cameraUnreachable(cameraId: "a", name: "Nursery", networkDown: false)

    @Test func titlesNameWhatIsWrongAndWhere() {
        #expect(wording.title(nursery) == "Can't reach Nursery")
        #expect(
            wording.title(.cameraUnreachable(cameraId: "a", name: "Nursery", networkDown: true))
                == "No network — can't reach Nursery"
        )
        #expect(wording.title(.lowBattery(percent: 22)) == "Battery low — 22%")
        #expect(wording.title(.notificationsBlocked) == "Alerts can't be shown")
        #expect(wording.title(.screenWakeBlocked) == "Alerts can't ring on silent")
        #expect(wording.title(.audioSessionLost) == "Can't listen with the screen locked")
    }

    @Test func detailsSayWhyItMattersAndSince() {
        #expect(
            wording.detail(MonitoringFailure(reason: nursery, sinceMs: 5))
                == "The camera has not answered since t5. Nobody will be told if that room gets loud."
        )
        #expect(
            wording.detail(
                MonitoringFailure(
                    reason: .cameraUnreachable(cameraId: "a", name: "Nursery", networkDown: true), sinceMs: 5
                )
            ) == "This phone has had no network since t5. Nobody will be told if a room gets loud."
        )
        #expect(
            wording.detail(MonitoringFailure(reason: .lowBattery(percent: 22), sinceMs: 5))
                == "Plug the phone in. Dozecam may not last the night on what is left."
        )
    }

    @Test func theViewerNoticeAndTheCardListEveryFailure() {
        let failures = [
            MonitoringFailure(reason: nursery, sinceMs: 5),
            MonitoringFailure(reason: .lowBattery(percent: 22), sinceMs: 7),
        ]
        #expect(wording.viewerNotice(failures[0]) == "Can't reach Nursery · since t5")
        #expect(wording.cardTitle(failures) == "Can't reach Nursery · Battery low — 22%")
        #expect(wording.cardBody(failures).split(separator: "\n").count == 2)
    }

    @Test func theUnpluggedNoticeNamesTheLevelAndTheAlarmLine() {
        #expect(
            wording.unpluggedText(percent: 80) == "Dozecam is running on battery (80%). It will sound an alarm at 25%.")
    }

    @Test func theSystemFormatIsATimeOfDay() {
        let text = FailureWording.system.time(1_700_000_000_000)
        #expect(!text.isEmpty)
        #expect(text.contains { $0.isNumber })
    }
}

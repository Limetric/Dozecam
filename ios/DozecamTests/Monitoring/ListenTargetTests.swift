import Foundation
import Testing

@testable import Dozecam

/// The port of Android's `ListenTargetTest`, test for test. The truth tables
/// live in `shared/fixtures/listen-target/`, one file per rule.
struct ListenTargetTests {
    private struct AloudCase: Codable {
        let name: String
        let requested: Bool
        let speakerGranted: Bool
        let viewerAudible: Bool
        let monitored: [String]
        let expected: Set<String>
    }

    private struct AlertCase: Codable {
        let name: String
        let cameraId: String
        let aloud: Set<String>
        let expected: Bool
    }

    private struct HeardCase: Codable {
        let name: String
        let aloud: Set<String>
        let mediaSilenced: Bool
        let expected: Set<String>
    }

    private struct YieldsCase: Codable {
        let name: String
        let cameraId: String
        let aloud: Set<String>
        let alarmingCameraId: String?
        let expected: Bool
    }

    private struct Table<Case: Codable>: Codable {
        let cases: [Case]
    }

    private func cases<Case: Codable>(
        _ names: [String], of type: Case.Type, in path: String, name: (Case) -> String
    ) throws -> [Case] {
        let all = try Fixtures.decode(Table<Case>.self, from: path).cases
        return names.compactMap { wanted in
            let matches = all.filter { name($0) == wanted }
            guard matches.count == 1 else {
                Issue.record("no single fixture case \"\(wanted)\" in \(path)")
                return nil
            }
            return matches[0]
        }
    }

    private func checkAloud(_ names: String...) throws {
        for fixture in try cases(names, of: AloudCase.self, in: "listen-target/aloud.json", name: \.name) {
            let aloud = ListenTarget.of(
                requested: fixture.requested, speakerGranted: fixture.speakerGranted,
                viewerAudible: fixture.viewerAudible, monitored: fixture.monitored)
            #expect(aloud == fixture.expected, "\(fixture.name)")
        }
    }

    private func checkWakesScreen(_ names: String...) throws {
        for fixture in try cases(names, of: AlertCase.self, in: "listen-target/alert-wakes-screen.json", name: \.name) {
            let wakes = ListenTarget.alertWakesScreen(cameraId: fixture.cameraId, aloud: fixture.aloud)
            #expect(wakes == fixture.expected, "\(fixture.name)")
        }
    }

    private func checkSounds(_ names: String...) throws {
        for fixture in try cases(names, of: AlertCase.self, in: "listen-target/alert-sounds.json", name: \.name) {
            let sounds = ListenTarget.alertSounds(cameraId: fixture.cameraId, aloud: fixture.aloud)
            #expect(sounds == fixture.expected, "\(fixture.name)")
        }
    }

    private func checkHeard(_ names: String...) throws {
        for fixture in try cases(names, of: HeardCase.self, in: "listen-target/heard.json", name: \.name) {
            let heard = ListenTarget.heard(aloud: fixture.aloud, mediaSilenced: fixture.mediaSilenced)
            #expect(heard == fixture.expected, "\(fixture.name)")
        }
    }

    private func checkYields(_ names: String...) throws {
        for fixture in try cases(names, of: YieldsCase.self, in: "listen-target/alert-yields.json", name: \.name) {
            let yields = ListenTarget.alertYields(
                cameraId: fixture.cameraId, aloud: fixture.aloud, alarmingCameraId: fixture.alarmingCameraId)
            #expect(yields == fixture.expected, "\(fixture.name)")
        }
    }

    @Test func everyRoomTheMonitorCanHearPlaysTogether() throws {
        try checkAloud("every room the monitor can hear plays, together")
    }

    @Test func nothingAskedForIsNothingPlayed() throws {
        try checkAloud("nothing asked for is nothing played")
    }

    // Every camera switched off, or gone with the console that issued it.
    @Test func aHouseWithNothingMonitoredHasNothingToPlay() throws {
        try checkAloud("a house with nothing monitored has nothing to play")
    }

    // The ask stands: a call has the speaker, not the user's mind.
    @Test func losingTheSpeakerSilencesItWithoutWaitingForTheSwitch() throws {
        try checkAloud("losing the speaker silences it without waiting for the switch")
    }

    // Otherwise the same nursery comes out of one speaker twice, a second or
    // so apart.
    @Test func listenModeStandsDownWhileTheViewerIsMakingNoise() throws {
        try checkAloud("listen mode stands down while the viewer is making noise")
    }

    // Whoever switched listen mode on is being told about that room
    // continuously; lighting a bedroom at 3am on top of it wakes the parent
    // who is already listening, and the one beside them.
    @Test func theOnlyRoomPlayingAloudNeedsNoNaming() throws {
        try checkWakesScreen("the only room playing aloud needs no naming")
    }

    // A cry out of a mix of rooms does not say whose it was, and the name is
    // the one thing the speaker cannot supply.
    @Test func oneRoomAmongSeveralIsNamedOnScreen() throws {
        try checkWakesScreen("one room among several is named on screen")
    }

    @Test func aRoomNobodyCanHearAlwaysWakesTheScreen() throws {
        try checkWakesScreen(
            "a room nobody can hear wakes the screen while another plays",
            "a room nobody can hear wakes the screen with nothing aloud")
    }

    // Whoever switched the speaker on is awake and hearing the cry itself;
    // the alarm is for a person whose eyes are shut.
    @Test func aRoomPlayingAloudDoesNotSoundTheAlarm() throws {
        try checkSounds(
            "the only room playing aloud does not sound the alarm",
            "a room playing aloud among several does not sound the alarm")
    }

    // Decoding is not hearing: at volume zero the speaker is saying nothing,
    // so nothing may be withheld on its account.
    @Test func aMixPlayingIntoASilencedMediaStreamIsHeardByNobody() throws {
        try checkHeard(
            "a mix playing into a silenced media stream is heard by nobody",
            "a mix playing into an audible media stream is heard")
    }

    // One alert card; clearing it acknowledges the alarm. B, being heard, must
    // not paper over A, which is not.
    @Test func aWithheldAlertDoesNotDisplaceASoundingAlarmForAnotherRoom() throws {
        try checkYields("a withheld alert does not displace a sounding alarm for another room")
    }

    @Test func anAlertThatSoundsOrNamesTheAlarmsOwnRoomIsNeverWithheld() throws {
        try checkYields(
            "an alert that sounds is never withheld",
            "an alert for the alarm's own room is never withheld",
            "with no alarm sounding nothing is withheld")
    }

    // With nothing aloud, or this room dropped from the mix by a lost speaker
    // or a downed stream, nobody is hearing it, so it must wake.
    @Test func aRoomNobodyCanHearSoundsTheAlarm() throws {
        try checkSounds(
            "a room nobody can hear sounds the alarm with nothing aloud",
            "a room dropped from the mix sounds the alarm")
    }
}

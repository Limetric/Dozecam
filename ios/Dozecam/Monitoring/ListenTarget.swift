/// Which cameras listen mode plays aloud, and how an alert behaves while it
/// does: the port of Android's `ListenTarget`
/// (shared/spec/alerts-and-sound-modes.md#listen-mode-aloud-and-heard;
/// truth tables in `shared/fixtures/listen-target/`).
///
/// Every room the monitor can hear, together, out of the one speaker. A quiet
/// room adds nothing to the mix, so what comes out follows whoever is making
/// noise without any timer or hand-off to get wrong. Whole house or nothing:
/// the question a mix leaves open, *which* room that was, is answered by the
/// alert, which lights the screen with the name whenever more than one room is
/// heard (`alertWakesScreen`).
///
/// Listen mode assumes the listener is awake: the alarm is for a person whose
/// eyes are shut, and a room already coming out of the speaker is being heard.
/// So while a room is heard its alerts do not sound (`alertSounds`).
enum ListenTarget {
    /// The whole decision in one place, so every reason the speaker could go
    /// quiet is visible together.
    ///
    /// `requested` is the switch. `speakerGranted` rather than the ask alone:
    /// playing on without the audio session is not ours to do. Not while
    /// `viewerAudible`, because that is the same nursery a second apart out of
    /// one speaker, and the room somebody is looking at is the better of the
    /// two to hear. And only ever `monitored` cameras, the ones with a live
    /// stream to turn up (the spec's *audible* rooms: the caller narrows them).
    static func of(
        requested: Bool, speakerGranted: Bool, viewerAudible: Bool, monitored: some Sequence<String>
    ) -> Set<String> {
        requested && speakerGranted && !viewerAudible ? Set(monitored) : []
    }

    /// Whether an alert for `cameraId` should light the screen while `aloud`
    /// is what the speaker is playing. A room that is the only one coming out
    /// of the speaker needs no naming, and lighting a bedroom at 3am on top of
    /// it wakes the parent already listening. With two or more rooms in the
    /// mix a cry no longer says whose it was, so the screen names it. A room
    /// nobody can hear always wakes the screen.
    static func alertWakesScreen(cameraId: String, aloud: Set<String>) -> Bool {
        aloud != [cameraId]
    }

    /// Whether an alert for `cameraId` should sound the alarm while `aloud` is
    /// what the speaker is playing. Not while the room is aloud: whoever
    /// switched the speaker on is awake and hearing the cry itself. The moment
    /// the room drops out of `aloud` (a call, the viewer, a stream going down)
    /// its alerts sound again, because then nobody is hearing it.
    static func alertSounds(cameraId: String, aloud: Set<String>) -> Bool {
        !aloud.contains(cameraId)
    }

    /// What of `aloud` is actually reaching anyone. Decoding a room is not
    /// hearing it: with the output volume at zero or muted the mix plays into
    /// nothing, and an alert withheld on its strength would be withheld from an
    /// empty room. The route (speaker, Bluetooth, headphones) is not
    /// second-guessed.
    static func heard(aloud: Set<String>, mediaSilenced: Bool) -> Set<String> {
        mediaSilenced ? [] : aloud
    }

    /// Whether an alert for `cameraId` should be withheld altogether because an
    /// alarm is already sounding for `alarmingCameraId`, a room nobody can
    /// hear. There is one alert card, and dismissing it acknowledges the
    /// alarm: a withheld alert must not replace the card of a sounding one.
    /// The alarm's own room may still refresh its card; it says the same thing.
    static func alertYields(cameraId: String, aloud: Set<String>, alarmingCameraId: String?) -> Bool {
        guard let alarmingCameraId else { return false }
        return !alertSounds(cameraId: cameraId, aloud: aloud) && alarmingCameraId != cameraId
    }
}

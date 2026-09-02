package app.dozecam.monitoring

/**
 * Which cameras listen mode plays aloud.
 *
 * Every room the monitor can hear, together, out of the one speaker. A quiet
 * room adds nothing to the mix, so what comes out follows whoever is making
 * noise without any timer or hand-off to get wrong — and nobody has to decide
 * in advance which child might need them tonight.
 *
 * Whole house or nothing. A rotation would be a voice from an unnamed room
 * with the display off, and a chosen subset would be a picker to explain at
 * bedtime; the question a mix leaves open — *which* room that was — is
 * answered by the alert, which lights the screen with the name whenever more
 * than one room is audible (see [alertWakesScreen]).
 *
 * Listen mode assumes the listener is awake: the alarm is for a person whose
 * eyes are shut, and a room already coming out of the speaker is being heard.
 * So while a room plays aloud its alerts do not sound (see [alertSounds]); the
 * screen is the only thing they may touch, and only to name a room the mix
 * cannot.
 */
object ListenTarget {

    /**
     * The whole decision in one place, so every reason the speaker could go
     * quiet is visible together rather than spread across the flows that feed
     * it.
     *
     * [requested] is the switch. [speakerGranted] rather than the ask alone,
     * because playing on without audio focus is not ours to do. Not while
     * [viewerAudible], because that is the same nursery a second apart out of
     * one speaker, and the room somebody is looking at is the better of the two
     * to hear. And only ever [monitored] cameras — the ones with a live stream
     * to turn up: a camera switched off, gone with the console that issued it,
     * or waiting on its network has no audio to play, and claiming it would
     * overstate what the speaker is doing.
     */
    fun of(
        requested: Boolean,
        speakerGranted: Boolean,
        viewerAudible: Boolean,
        monitored: Collection<String>,
    ): Set<String> =
        if (requested && speakerGranted && !viewerAudible) monitored.toSet() else emptySet()

    /**
     * Whether an alert for [cameraId] should light the screen while [aloud] is
     * what the speaker is playing.
     *
     * A room that is the only one coming out of the speaker needs no naming:
     * whoever switched listen mode on is being told about it continuously, and
     * lighting a bedroom at 3am on top of that wakes the parent who is already
     * listening, and the one beside them. With two or more rooms in the mix,
     * hearing a cry no longer says whose it was — so the screen comes on with
     * the name, which is the one thing the mix cannot say for itself. A room
     * nobody can hear at all always wakes the screen; that is what the
     * full-screen view is for.
     */
    fun alertWakesScreen(cameraId: String, aloud: Set<String>): Boolean =
        aloud != setOf(cameraId)

    /**
     * Whether an alert for [cameraId] should sound the alarm — chime, ramp,
     * vibration — while [aloud] is what the speaker is playing.
     *
     * Not while the room is already aloud. The alarm exists to wake someone
     * asleep, and whoever switched the speaker on is awake and hearing the cry
     * itself; a ramp on top of it is noise, not urgency. The moment the speaker
     * is lost — a call, the viewer, a stream going down — the room drops out of
     * [aloud] and its alerts sound again, because then nobody is hearing it.
     */
    fun alertSounds(cameraId: String, aloud: Set<String>): Boolean =
        cameraId !in aloud

    /**
     * What of [aloud] is actually reaching anyone. Decoding a room is not the
     * same as someone hearing it: with the media stream at zero or muted the
     * mix is playing into nothing, and an alert withheld on the strength of it
     * would be withheld from an empty room. The route is not second-guessed —
     * a Bluetooth speaker or a pair of headphones is a legitimate way to
     * listen, and which side of the door it is on is not knowable from here.
     */
    fun heard(aloud: Set<String>, mediaSilenced: Boolean): Set<String> =
        if (mediaSilenced) emptySet() else aloud

    /**
     * Whether an alert for [cameraId] should be withheld altogether because an
     * alarm is already sounding for [alarmingCameraId], a room nobody can hear.
     *
     * There is one alert notification, and dismissing it acknowledges the
     * alarm. A withheld alert — one whose room is being heard — must not
     * replace the notification of a sounding one: the parent would be sent to
     * the room they can already hear, and clearing that card would silence an
     * alarm for the room they cannot. The alarm's own room may still refresh
     * its notification; it says the same thing.
     */
    fun alertYields(cameraId: String, aloud: Set<String>, alarmingCameraId: String?): Boolean =
        !alertSounds(cameraId, aloud) && alarmingCameraId != null && alarmingCameraId != cameraId
}

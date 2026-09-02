package app.dozecam.monitoring

/**
 * Which one camera listen mode plays aloud.
 *
 * One, deliberately. The grid rotates the sound a camera at a time because the
 * highlighted tile says whose turn it is; with the display off there is no tile
 * and no highlight, so a rotation would be a voice from an unnamed room. Two
 * rooms at once is worse still — the whole question a parent asks a monitor is
 * *which* child that was.
 *
 * So the target is the camera the user chose, and the only camera there is when
 * they have not been asked. Anything else — no choice made with several to
 * choose from, or a choice that names a camera the monitor is not listening to
 * any more — resolves to nothing, and the viewer asks rather than guessing.
 */
object ListenTarget {

    /**
     * The whole decision in one place, so every reason the speaker could stop
     * being this camera's is visible together rather than spread across the
     * flows that feed it.
     *
     * [speakerGranted] rather than the switch, because playing on without audio
     * focus is not ours to do. Not while [viewerAudible], because that is the
     * same nursery a second apart out of one speaker, and the room somebody is
     * looking at is the better of the two to hear.
     */
    fun of(
        listening: Boolean,
        speakerGranted: Boolean,
        viewerAudible: Boolean,
        chosen: String?,
        monitored: Collection<String>,
    ): String? = if (listening && speakerGranted && !viewerAudible) {
        resolve(chosen, monitored)
    } else {
        null
    }

    /**
     * [chosen] is the remembered camera id, [monitored] the cameras that
     * actually have a monitor decoding audio right now. A remembered choice
     * that is no longer being monitored is not silently replaced by another
     * room: whoever set it asked for that one.
     */
    fun resolve(chosen: String?, monitored: Collection<String>): String? =
        chosen?.takeIf { it in monitored } ?: monitored.singleOrNull()

    /** Whether turning listen mode on still needs an answer from the user. */
    fun needsChoice(chosen: String?, monitored: Collection<String>): Boolean =
        monitored.isNotEmpty() && resolve(chosen, monitored) == null
}

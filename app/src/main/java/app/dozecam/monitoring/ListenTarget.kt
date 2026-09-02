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
 * And always the room that was actually asked for. Nothing here reaches for a
 * remembered choice or a nearest-available camera: a monitor that substitutes
 * one bedroom for another is worse than one that stays quiet, because the
 * silence is at least legible. Whether the user still has to be *asked* is the
 * viewer's question, settled before any of this — see MonitorScreen.
 */
object ListenTarget {

    /**
     * The whole decision in one place, so every reason the speaker could stop
     * being this camera's is visible together rather than spread across the
     * flows that feed it.
     *
     * [request] is the room the user asked for, or null for none.
     * [speakerGranted] rather than the ask alone, because playing on without
     * audio focus is not ours to do. Not while [viewerAudible], because that is
     * the same nursery a second apart out of one speaker, and the room somebody
     * is looking at is the better of the two to hear. And only while the
     * request is still among [monitored] — a camera switched off, or gone with
     * the console that issued it, has no audio to play and no stand-in.
     */
    fun of(
        request: String?,
        speakerGranted: Boolean,
        viewerAudible: Boolean,
        monitored: Collection<String>,
    ): String? = request?.takeIf { speakerGranted && !viewerAudible && it in monitored }
}

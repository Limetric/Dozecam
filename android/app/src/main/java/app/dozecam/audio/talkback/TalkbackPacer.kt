package app.dozecam.audio.talkback

/**
 * When each frame is due to leave the phone.
 *
 * UDP has no flow control, so the camera's buffer is defended by nothing except
 * the sender's restraint: hand it a buffer's worth of frames at once and the
 * audio comes out garbled. Every frame is therefore timed against the moment
 * the press began — `start + n × frame` — rather than by sleeping a frame's
 * worth after the last one. Cumulative sleeps drift by however long encoding
 * and sending actually took, and drift in this direction is a burst.
 *
 * The first frames of a stream arrive before the camera's jitter buffer has
 * anything to work with, and come out as a click. [LEAD_IN_FRAMES] of silence
 * ahead of the microphone puts that click somewhere harmless, so it lands
 * between the press and the first syllable rather than on it.
 */
class TalkbackPacer(
    private val startNanos: Long,
    frameMillis: Int = TalkbackFormat.FRAME_MILLIS,
) {
    private val frameNanos = frameMillis * 1_000_000L

    /** The instant frame [index] should be sent, counting from the press. */
    fun dueAtNanos(index: Long): Long = startNanos + index * frameNanos

    /**
     * How long to wait before sending frame [index], never negative: a frame
     * already late is sent at once and the clock is not moved to accommodate
     * it, so lateness cannot accumulate into a burst.
     */
    fun waitNanos(index: Long, nowNanos: Long): Long =
        (dueAtNanos(index) - nowNanos).coerceAtLeast(0L)

    companion object {
        /**
         * 200 ms of silence before the microphone opens. Long enough for a
         * camera to settle, short enough that nobody notices the button was
         * ahead of them.
         */
        const val LEAD_IN_FRAMES = 10

        /**
         * 300 ms of silence after the microphone closes.
         *
         * A stream that simply stops leaves the camera's decoder asked for
         * audio that never arrives, and Opus answers such questions by
         * extrapolating from the last frame it did receive. On a held button
         * the last frame is the end of a sentence, so the invented audio lands
         * on the final word. Silence gives the buffer something real to drain.
         */
        const val LEAD_OUT_FRAMES = 15
    }
}

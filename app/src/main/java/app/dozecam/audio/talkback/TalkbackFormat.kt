package app.dozecam.audio.talkback

import app.dozecam.protect.TalkbackSession

/**
 * What the phone must produce to speak to a camera, or why it cannot.
 *
 * The console names a codec and a rate per camera, and not every combination
 * is one Android can encode. Deciding that here, off the session alone, means
 * the viewer knows whether a control is worth showing before it opens a socket
 * or asks for a microphone.
 */
sealed interface TalkbackFormat {

    /**
     * A camera that can be spoken to, with the numbers the encoder, the
     * packetiser and the pacer each need.
     */
    data class Speakable(
        val host: String,
        val port: Int,
        /** What the encoder is fed, and what the console asked for. */
        val sampleRate: Int,
        /** Samples per 20 ms frame at [sampleRate]; 480 at the usual 24 kHz. */
        val frameSamples: Int,
        /**
         * How far the RTP timestamp moves per frame.
         *
         * Always the 48 kHz figure regardless of [sampleRate], because that is
         * what ffmpeg stamps and what a camera was observed to accept. Opus is
         * defined against a 48 kHz clock (RFC 7587) whatever rate it was fed,
         * so this is also the correct reading — but it is here because it was
         * proven, not because it is tidy.
         */
        val rtpTimestampIncrement: Int,
    ) : TalkbackFormat

    /** A camera that cannot be spoken to, and the reason a user can be told. */
    data class Refused(val reason: Reason) : TalkbackFormat

    enum class Reason {
        /** vorbis, or anything else MediaCodec has no encoder for. */
        CODEC_NOT_ENCODABLE,

        /** A rate the platform Opus encoder does not accept. */
        RATE_NOT_ENCODABLE,

        /** The console described the camera with a URL we cannot read. */
        NO_ADDRESS,
    }

    companion object {
        /** The only codec proven against a real camera, so the only one built. */
        private const val OPUS = "opus"

        /**
         * What `c2.android.opus.encoder` advertises, and has done since the
         * AOSP software codec list of Android 12 — below our minSdk, so this
         * needs no version check.
         */
        private val ENCODABLE_RATES = setOf(8_000, 12_000, 16_000, 24_000, 48_000)

        /** Short enough to stay responsive, long enough to be one packet. */
        const val FRAME_MILLIS = 20

        /** Opus is clocked at 48 kHz however it was fed. */
        private const val RTP_CLOCK_HZ = 48_000

        fun of(session: TalkbackSession): TalkbackFormat {
            if (!session.codec.equals(OPUS, ignoreCase = true)) {
                return Refused(Reason.CODEC_NOT_ENCODABLE)
            }
            if (session.samplingRate !in ENCODABLE_RATES) {
                return Refused(Reason.RATE_NOT_ENCODABLE)
            }
            val host = session.host ?: return Refused(Reason.NO_ADDRESS)
            return Speakable(
                host = host,
                port = session.port,
                sampleRate = session.samplingRate,
                frameSamples = session.samplingRate * FRAME_MILLIS / 1000,
                rtpTimestampIncrement = RTP_CLOCK_HZ * FRAME_MILLIS / 1000,
            )
        }
    }
}

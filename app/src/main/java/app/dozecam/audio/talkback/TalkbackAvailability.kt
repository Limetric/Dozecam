package app.dozecam.audio.talkback

/**
 * What the talk-back control should say about itself, before anyone holds it.
 *
 * A dead button is the one outcome worth ruling out: talk-back fails in ways
 * that look exactly like a bug — a camera streaming video perfectly while
 * refusing sound — so every reason it cannot work is a reason a user can be
 * shown, and the control is never simply inert.
 */
sealed interface TalkbackAvailability {

    /** Everything checked out; the button can be held. */
    data object Ready : TalkbackAvailability

    /** Still finding out. The control waits rather than guessing. */
    data object Resolving : TalkbackAvailability

    /** The microphone has never been asked for. Holding will ask. */
    data object NeedsPermission : TalkbackAvailability

    /**
     * The microphone is needed and the phone is locked. A permission dialog
     * cannot be answered over a keyguard, so the control says so instead of
     * firing one at a screen nobody can use.
     */
    data object NeedsUnlock : TalkbackAvailability

    /** The camera cannot be reached from this network, whatever its video does. */
    data object Unreachable : TalkbackAvailability

    /** Talk-back will never work for this camera, for a reason worth naming. */
    data class Unsupported(val reason: Reason) : TalkbackAvailability

    enum class Reason {
        /** The camera has no speaker to talk out of. */
        NO_SPEAKER,

        /** No console API key, so there is nothing to ask where to send audio. */
        NO_API_KEY,

        /**
         * A camera added by hand, or one left behind by a console that is no
         * longer the signed-in one. Either way nothing can be asked about it.
         */
        NO_CONSOLE,

        /** vorbis, or anything else the platform cannot encode. */
        CODEC,

        /** A sampling rate the platform Opus encoder will not take. */
        RATE,

        /** The console described the camera with an address we cannot read. */
        ADDRESS,
    }

    companion object {
        /**
         * The order is the point. Reasons a camera can never work come before
         * reasons this network cannot, which come before anything asked of the
         * user — so nobody is prompted for a microphone in aid of a camera that
         * was never going to make a sound.
         */
        fun of(
            hasSpeaker: Boolean,
            hasApiKey: Boolean,
            format: TalkbackFormat?,
            reachable: Boolean?,
            microphoneGranted: Boolean,
            locked: Boolean,
        ): TalkbackAvailability = when {
            !hasSpeaker -> Unsupported(Reason.NO_SPEAKER)
            !hasApiKey -> Unsupported(Reason.NO_API_KEY)
            format == null -> Resolving
            format is TalkbackFormat.Refused -> Unsupported(format.reason.asAvailabilityReason())
            reachable == null -> Resolving
            !reachable -> Unreachable
            microphoneGranted -> Ready
            locked -> NeedsUnlock
            else -> NeedsPermission
        }

        private fun TalkbackFormat.Reason.asAvailabilityReason(): Reason = when (this) {
            TalkbackFormat.Reason.CODEC_NOT_ENCODABLE -> Reason.CODEC
            TalkbackFormat.Reason.RATE_NOT_ENCODABLE -> Reason.RATE
            TalkbackFormat.Reason.NO_ADDRESS -> Reason.ADDRESS
        }
    }
}

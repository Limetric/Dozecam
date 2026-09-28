package app.dozecam.audio.talkback

import kotlin.math.pow

/**
 * How loud the phone's voice arrives in the room.
 *
 * The camera plays whatever it is sent at its own speaker volume — a console
 * setting shared with every other viewer, which this app has no business
 * rewriting. So quieting talk-back for this phone alone means quieting the
 * samples themselves, between the microphone and the encoder.
 *
 * The slider maps to decibels rather than to amplitude because loudness is
 * heard logarithmically: scaled linearly, the top four fifths of the travel
 * would do nothing audible and the bottom fifth would do everything. Full
 * volume is exactly no change, and the bottom of the scale is thirty decibels
 * down — a murmur, never silence, because a slider that could mute would turn
 * the talk button into one that looks broken.
 */
object TalkbackGain {

    /** The quietest the slider goes: about 3% of full amplitude. */
    const val FLOOR_DB = -30f

    /**
     * The factor to scale samples by for a slider position in 0..1, where one
     * is unity and zero is [FLOOR_DB]. Positions outside the range are clamped
     * rather than trusted: the preference outlives whichever version of the
     * app wrote it.
     */
    fun amplitude(volume: Float): Float {
        val position = volume.coerceIn(0f, 1f)
        if (position >= 1f) return 1f
        return 10f.pow((position - 1f) * -FLOOR_DB / 20f)
    }
}

/**
 * A [PcmSource] whose samples come out scaled by a constant factor.
 *
 * Attenuation only: a factor above one is refused outright, because a boost
 * can clip and nothing here has a reason to ask for one. Unity passes frames
 * through untouched.
 */
class AttenuatedPcmSource(
    private val source: PcmSource,
    private val amplitude: Float,
) : PcmSource {

    init {
        require(amplitude in 0f..1f) { "amplitude must be within 0..1, was $amplitude" }
    }

    override fun read(into: ShortArray): Boolean {
        if (!source.read(into)) return false
        if (amplitude < 1f) {
            for (i in into.indices) into[i] = (into[i] * amplitude).toInt().toShort()
        }
        return true
    }

    override fun close() = source.close()
}

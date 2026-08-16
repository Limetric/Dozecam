package app.dozecam.audio.talkback

/**
 * Drops a captured rate down to the one a camera asked for.
 *
 * The voice-communication source is chosen for its echo cancellation, not its
 * flexibility, and not every device offers it at 24 kHz — the rate Protect
 * consoles ask for most. Where it does not, capture happens at a multiple and
 * lands here.
 *
 * Averaging each group rather than taking every nth sample: plain decimation
 * folds everything above the new Nyquist back into the speech as aliasing, and
 * a mean is the cheapest low-pass that avoids it. Crude for music, more than
 * enough for a voice going to a small speaker in a room.
 */
object PcmDownsampler {

    /**
     * Averages each run of [factor] samples. A [factor] of one returns the
     * input untouched; a trailing partial group is dropped rather than averaged
     * against samples that do not exist.
     */
    fun byFactor(input: ShortArray, factor: Int): ShortArray {
        require(factor >= 1) { "factor must be at least 1, was $factor" }
        if (factor == 1) return input

        val output = ShortArray(input.size / factor)
        for (i in output.indices) {
            var sum = 0
            val from = i * factor
            for (j in 0 until factor) sum += input[from + j]
            output[i] = (sum / factor).toShort()
        }
        return output
    }

    /**
     * The whole-number factor between two rates, or null when there is none.
     * A fractional resample would need interpolation and a filter to match, and
     * refusing is honest where guessing is not.
     */
    fun factorBetween(captureRate: Int, targetRate: Int): Int? {
        if (captureRate <= 0 || targetRate <= 0) return null
        if (captureRate % targetRate != 0) return null
        return captureRate / targetRate
    }
}

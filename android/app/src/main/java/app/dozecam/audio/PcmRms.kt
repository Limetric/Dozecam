package app.dozecam.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

object PcmRms {

    /**
     * Normalized RMS (0..1) of a 16-bit little-endian PCM buffer.
     * Does not consume the caller's buffer position.
     */
    fun of(buffer: ByteBuffer): Float {
        val pcm = buffer.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN)
        var sumSquares = 0.0
        var samples = 0
        while (pcm.remaining() >= 2) {
            val s = pcm.short.toInt()
            sumSquares += (s.toDouble() * s)
            samples++
        }
        if (samples == 0) return 0f
        return (sqrt(sumSquares / samples) / 32768.0).toFloat().coerceIn(0f, 1f)
    }
}

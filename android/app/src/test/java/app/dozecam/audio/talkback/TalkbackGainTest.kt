package app.dozecam.audio.talkback

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TalkbackGainTest {

    @Test
    fun `full volume is exactly unity`() {
        assertEquals(1f, TalkbackGain.amplitude(1f), 0f)
    }

    /** The floor is a whisper: a slider that could mute would look broken. */
    @Test
    fun `the bottom of the slider is thirty decibels down, not silence`() {
        assertEquals(0.0316f, TalkbackGain.amplitude(0f), 0.0005f)
        assertTrue(TalkbackGain.amplitude(0f) > 0f)
    }

    /** Halfway in travel is halfway in decibels, not in amplitude. */
    @Test
    fun `halfway down the slider is fifteen decibels down`() {
        assertEquals(0.1778f, TalkbackGain.amplitude(0.5f), 0.0005f)
    }

    /** The preference outlives whichever version of the app wrote it. */
    @Test
    fun `positions outside the slider are clamped rather than trusted`() {
        assertEquals(TalkbackGain.amplitude(1f), TalkbackGain.amplitude(2f), 0f)
        assertEquals(TalkbackGain.amplitude(0f), TalkbackGain.amplitude(-1f), 0f)
    }

    @Test
    fun `attenuation scales every sample`() {
        val attenuated = AttenuatedPcmSource(fixedSource(shortArrayOf(10_000, -10_000, 0, 100)), 0.5f)
        val frame = ShortArray(4)

        assertTrue(attenuated.read(frame))
        assertArrayEquals(shortArrayOf(5_000, -5_000, 0, 50), frame)
    }

    @Test
    fun `unity passes samples through untouched`() {
        val samples = shortArrayOf(Short.MAX_VALUE, Short.MIN_VALUE, 1_234)
        val attenuated = AttenuatedPcmSource(fixedSource(samples), 1f)
        val frame = ShortArray(3)

        assertTrue(attenuated.read(frame))
        assertArrayEquals(samples, frame)
    }

    /** A microphone that has stopped must still end the press through the wrapper. */
    @Test
    fun `a finished source stays finished through the attenuator`() {
        val attenuated = AttenuatedPcmSource(
            object : PcmSource {
                override fun read(into: ShortArray) = false
                override fun close() = Unit
            },
            0.5f,
        )

        assertFalse(attenuated.read(ShortArray(4)))
    }

    @Test
    fun `closing the attenuator closes the microphone behind it`() {
        var closed = false
        val attenuated = AttenuatedPcmSource(
            object : PcmSource {
                override fun read(into: ShortArray) = true
                override fun close() { closed = true }
            },
            0.5f,
        )

        attenuated.close()

        assertTrue(closed)
    }

    /** A boost can clip, and nothing here has a reason to ask for one. */
    @Test
    fun `a factor above one is refused outright`() {
        assertThrows(IllegalArgumentException::class.java) {
            AttenuatedPcmSource(fixedSource(shortArrayOf()), 1.5f)
        }
    }

    private fun fixedSource(samples: ShortArray) = object : PcmSource {
        override fun read(into: ShortArray): Boolean {
            samples.copyInto(into)
            return true
        }

        override fun close() = Unit
    }
}

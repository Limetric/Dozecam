package app.dozecam.audio.talkback

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class PcmDownsamplerTest {

    @Test
    fun `a factor of one is the input itself`() {
        val input = shortArrayOf(1, 2, 3)

        assertSame(input, PcmDownsampler.byFactor(input, 1))
    }

    @Test
    fun `halving averages each pair`() {
        val input = shortArrayOf(10, 20, 30, 40, -100, 100)

        assertArrayEquals(
            shortArrayOf(15, 35, 0),
            PcmDownsampler.byFactor(input, 2),
        )
    }

    @Test
    fun `a trailing partial group is dropped rather than invented`() {
        val input = shortArrayOf(10, 20, 30, 40, 50)

        assertArrayEquals(shortArrayOf(15, 35), PcmDownsampler.byFactor(input, 2))
    }

    @Test
    fun `full scale samples survive the average without wrapping`() {
        val input = ShortArray(4) { Short.MAX_VALUE }

        assertArrayEquals(
            shortArrayOf(Short.MAX_VALUE, Short.MAX_VALUE),
            PcmDownsampler.byFactor(input, 2),
        )
    }

    @Test
    fun `a whole frame of 48k becomes a whole frame of 24k`() {
        val input = ShortArray(960) { (it % 100).toShort() }

        assertEquals(480, PcmDownsampler.byFactor(input, 2).size)
    }

    @Test
    fun `whole-number rate factors are found`() {
        assertEquals(2, PcmDownsampler.factorBetween(48_000, 24_000))
        assertEquals(1, PcmDownsampler.factorBetween(24_000, 24_000))
        assertEquals(3, PcmDownsampler.factorBetween(48_000, 16_000))
    }

    /** A fractional resample needs interpolation; refusing beats guessing. */
    @Test
    fun `rates that do not divide have no factor`() {
        assertNull(PcmDownsampler.factorBetween(44_100, 24_000))
        assertNull(PcmDownsampler.factorBetween(16_000, 24_000))
        assertNull(PcmDownsampler.factorBetween(0, 24_000))
    }
}

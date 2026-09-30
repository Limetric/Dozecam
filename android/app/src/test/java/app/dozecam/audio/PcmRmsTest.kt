package app.dozecam.audio

import app.dozecam.testing.Fixtures
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.serialization.Serializable
import org.junit.Assert.assertEquals
import org.junit.Test

/** The sample vectors live in `shared/fixtures/sound-detector/rms.json`. */
class PcmRmsTest {

    @Serializable
    private data class Case(val name: String, val samples: List<Int>, val expected: Float, val tolerance: Float)

    @Serializable
    private data class Fixture(val cases: List<Case>)

    private val cases = Fixtures.decode<Fixture>("sound-detector/rms.json").cases

    private fun pcmBuffer(samples: List<Int>): ByteBuffer {
        val buffer = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { buffer.putShort(it.toShort()) }
        buffer.flip()
        return buffer
    }

    private fun check(name: String) {
        val case = cases.singleOrNull { it.name == name } ?: error("no fixture case \"$name\"")
        assertEquals(case.name, case.expected, PcmRms.of(pcmBuffer(case.samples)), case.tolerance)
    }

    @Test
    fun `silence is zero`() = check("silence is zero")

    @Test
    fun `empty buffer is zero`() = check("empty buffer is zero")

    @Test
    fun `full-scale square wave is one`() = check("full-scale square wave is one")

    @Test
    fun `half-scale square wave is one half`() = check("half-scale square wave is one half")

    // Buffer mechanics rather than a level rule, so it stays here.
    @Test
    fun `does not consume the caller's buffer`() {
        val buffer = pcmBuffer(listOf(1000, -1000))
        PcmRms.of(buffer)
        assertEquals(4, buffer.remaining())
    }
}

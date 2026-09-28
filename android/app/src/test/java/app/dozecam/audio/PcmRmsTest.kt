package app.dozecam.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Test

class PcmRmsTest {

    private fun pcmBuffer(vararg samples: Int): ByteBuffer {
        val buffer = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { buffer.putShort(it.toShort()) }
        buffer.flip()
        return buffer
    }

    @Test
    fun `silence is zero`() {
        assertEquals(0f, PcmRms.of(pcmBuffer(0, 0, 0, 0)), 0f)
    }

    @Test
    fun `empty buffer is zero`() {
        assertEquals(0f, PcmRms.of(ByteBuffer.allocate(0)), 0f)
    }

    @Test
    fun `full-scale square wave is one`() {
        val level = PcmRms.of(pcmBuffer(32767, -32767, 32767, -32767))
        assertEquals(1f, level, 0.001f)
    }

    @Test
    fun `half-scale square wave is one half`() {
        val level = PcmRms.of(pcmBuffer(16384, -16384, 16384, -16384))
        assertEquals(0.5f, level, 0.001f)
    }

    @Test
    fun `does not consume the caller's buffer`() {
        val buffer = pcmBuffer(1000, -1000)
        PcmRms.of(buffer)
        assertEquals(4, buffer.remaining())
    }
}

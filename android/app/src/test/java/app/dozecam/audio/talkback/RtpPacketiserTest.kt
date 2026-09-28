package app.dozecam.audio.talkback

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RtpPacketiserTest {

    private fun packetiser(
        ssrc: Int = 0x11223344,
        startSequence: Int = 0,
        startTimestamp: Int = 0,
    ) = RtpPacketiser(ssrc, timestampIncrement = 960, startSequence, startTimestamp)

    private fun ByteArray.version() = (this[0].toInt() and 0xC0) ushr 6
    private fun ByteArray.hasMarker() = (this[1].toInt() and 0x80) != 0
    private fun ByteArray.payloadType() = this[1].toInt() and 0x7F
    private fun ByteArray.sequence() =
        ((this[2].toInt() and 0xFF) shl 8) or (this[3].toInt() and 0xFF)
    private fun ByteArray.timestamp() =
        ((this[4].toInt() and 0xFF) shl 24) or ((this[5].toInt() and 0xFF) shl 16) or
            ((this[6].toInt() and 0xFF) shl 8) or (this[7].toInt() and 0xFF)
    private fun ByteArray.ssrc() =
        ((this[8].toInt() and 0xFF) shl 24) or ((this[9].toInt() and 0xFF) shl 16) or
            ((this[10].toInt() and 0xFF) shl 8) or (this[11].toInt() and 0xFF)

    @Test
    fun `a packet is a twelve byte header and the frame, untouched`() {
        val frame = byteArrayOf(1, 2, 3, 4, 5)

        val packet = packetiser().packetise(frame)

        assertEquals(RtpPacketiser.HEADER_BYTES + frame.size, packet.size)
        assertEquals(2, packet.version())
        assertEquals(RtpPacketiser.PAYLOAD_TYPE, packet.payloadType())
        assertArrayEquals(frame, packet.copyOfRange(RtpPacketiser.HEADER_BYTES, packet.size))
    }

    /** The talkspurt opens once; everything after it is a continuation. */
    @Test
    fun `only the first packet carries the marker bit`() {
        val packetiser = packetiser()

        assertTrue(packetiser.packetise(byteArrayOf(0)).hasMarker())
        assertFalse(packetiser.packetise(byteArrayOf(0)).hasMarker())
        assertFalse(packetiser.packetise(byteArrayOf(0)).hasMarker())
    }

    @Test
    fun `sequence and timestamp advance one frame at a time`() {
        val packetiser = packetiser(startSequence = 7, startTimestamp = 1_000)

        val first = packetiser.packetise(byteArrayOf(0))
        val second = packetiser.packetise(byteArrayOf(0))
        val third = packetiser.packetise(byteArrayOf(0))

        assertEquals(listOf(7, 8, 9), listOf(first, second, third).map { it.sequence() })
        assertEquals(
            listOf(1_000, 1_960, 2_920),
            listOf(first, second, third).map { it.timestamp() },
        )
    }

    @Test
    fun `the sequence wraps at sixteen bits rather than overflowing the header`() {
        val packetiser = packetiser(startSequence = 0xFFFF)

        assertEquals(0xFFFF, packetiser.packetise(byteArrayOf(0)).sequence())
        assertEquals(0, packetiser.packetise(byteArrayOf(0)).sequence())
    }

    /**
     * A timestamp is read as a difference, so wrapping past 2^32 is ordinary
     * rather than an error — but it must wrap silently instead of throwing.
     */
    @Test
    fun `the timestamp wraps without complaint`() {
        val packetiser = packetiser(startTimestamp = Int.MAX_VALUE - 100)

        val before = packetiser.packetise(byteArrayOf(0)).timestamp()
        val after = packetiser.packetise(byteArrayOf(0)).timestamp()

        assertEquals(Int.MAX_VALUE - 100, before)
        assertEquals(before + 960, after)
    }

    @Test
    fun `every packet in a press carries the same ssrc`() {
        val packetiser = packetiser(ssrc = 0xDEADBEEF.toInt())

        val ssrcs = (1..3).map { packetiser.packetise(byteArrayOf(0)).ssrc() }

        assertEquals(listOf(0xDEADBEEF.toInt()), ssrcs.distinct())
    }

    @Test
    fun `an empty frame still produces a well formed header`() {
        val packet = packetiser().packetise(ByteArray(0))

        assertEquals(RtpPacketiser.HEADER_BYTES, packet.size)
        assertEquals(2, packet.version())
    }
}

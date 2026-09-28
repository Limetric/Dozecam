package app.dozecam.protect

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LivestreamDecoderTest {

    private fun frame(type: Int, payload: ByteArray): ByteArray =
        byteArrayOf(
            type.toByte(),
            ((payload.size shr 16) and 0xFF).toByte(),
            ((payload.size shr 8) and 0xFF).toByte(),
            (payload.size and 0xFF).toByte(),
        ) + payload

    private fun frame(type: Int, payload: String): ByteArray =
        frame(type, payload.encodeToByteArray())

    @Test
    fun `emits the init segment with the codec announced before it`() {
        val decoder = LivestreamDecoder()

        val segments = decoder.decode(
            frame(LivestreamFrame.CODEC_INFORMATION, "av01.0.05M.08,mp4a.40.2") +
                frame(LivestreamFrame.INIT_SEGMENT, "FTYPMOOV"),
        )

        val init = segments.single() as LivestreamSegment.Init
        assertEquals("av01.0.05M.08,mp4a.40.2", init.codec)
        assertArrayEquals("FTYPMOOV".encodeToByteArray(), init.data)
    }

    @Test
    fun `assembles a fragment in moof mdat video audio order`() {
        val decoder = LivestreamDecoder()

        val segments = decoder.decode(
            frame(LivestreamFrame.BEGIN_SEGMENT, "") +
                // Deliberately out of order on the wire.
                frame(LivestreamFrame.AUDIO, "A") +
                frame(LivestreamFrame.MDAT, "D") +
                frame(LivestreamFrame.VIDEO, "V") +
                frame(LivestreamFrame.MOOF, "M") +
                frame(LivestreamFrame.END_SEGMENT, ""),
        )

        val media = segments.single() as LivestreamSegment.Media
        assertEquals("MDVA", media.data.decodeToString())
    }

    @Test
    fun `carries a frame split across websocket messages`() {
        val decoder = LivestreamDecoder()
        val whole = frame(LivestreamFrame.INIT_SEGMENT, "FTYPMOOV")

        // Split mid-header, then mid-payload: both must survive.
        assertTrue(decoder.decode(whole.copyOfRange(0, 2)).isEmpty())
        assertTrue(decoder.decode(whole.copyOfRange(2, 7)).isEmpty())
        val segments = decoder.decode(whole.copyOfRange(7, whole.size))

        assertArrayEquals(
            "FTYPMOOV".encodeToByteArray(),
            (segments.single() as LivestreamSegment.Init).data,
        )
    }

    @Test
    fun `decodes several fragments arriving in one message`() {
        val decoder = LivestreamDecoder()

        val segments = decoder.decode(
            frame(LivestreamFrame.BEGIN_SEGMENT, "") +
                frame(LivestreamFrame.MOOF, "M1") +
                frame(LivestreamFrame.MDAT, "D1") +
                frame(LivestreamFrame.END_SEGMENT, "") +
                frame(LivestreamFrame.BEGIN_SEGMENT, "") +
                frame(LivestreamFrame.MOOF, "M2") +
                frame(LivestreamFrame.MDAT, "D2") +
                frame(LivestreamFrame.END_SEGMENT, ""),
        )

        assertEquals(
            listOf("M1D1", "M2D2"),
            segments.map { (it as LivestreamSegment.Media).data.decodeToString() },
        )
    }

    @Test
    fun `does not leak boxes from one fragment into the next`() {
        val decoder = LivestreamDecoder()

        decoder.decode(
            frame(LivestreamFrame.BEGIN_SEGMENT, "") +
                frame(LivestreamFrame.MOOF, "M1") +
                frame(LivestreamFrame.AUDIO, "A1") +
                frame(LivestreamFrame.END_SEGMENT, ""),
        )
        // The second fragment carries no audio; the first one's must not ride along.
        val segments = decoder.decode(
            frame(LivestreamFrame.BEGIN_SEGMENT, "") +
                frame(LivestreamFrame.MOOF, "M2") +
                frame(LivestreamFrame.END_SEGMENT, ""),
        )

        assertEquals("M2", (segments.single() as LivestreamSegment.Media).data.decodeToString())
    }

    @Test
    fun `ignores an empty fragment rather than emitting zero bytes`() {
        val decoder = LivestreamDecoder()

        val segments = decoder.decode(
            frame(LivestreamFrame.BEGIN_SEGMENT, "") + frame(LivestreamFrame.END_SEGMENT, ""),
        )

        assertTrue(segments.isEmpty())
    }

    @Test
    fun `ignores timestamp frames`() {
        val decoder = LivestreamDecoder()

        val segments = decoder.decode(
            frame(LivestreamFrame.BEGIN_SEGMENT, "") +
                frame(LivestreamFrame.TIMESTAMP, ByteArray(8)) +
                frame(LivestreamFrame.MOOF, "M") +
                frame(LivestreamFrame.END_SEGMENT, ""),
        )

        assertEquals("M", (segments.single() as LivestreamSegment.Media).data.decodeToString())
    }

    @Test
    fun `reads a payload longer than a 16-bit length`() {
        val decoder = LivestreamDecoder()
        val big = ByteArray(70_000) { (it % 251).toByte() }

        val segments = decoder.decode(frame(LivestreamFrame.INIT_SEGMENT, big))

        assertArrayEquals(big, (segments.single() as LivestreamSegment.Init).data)
    }

    @Test
    fun `rejects an unknown frame type instead of desyncing silently`() {
        val decoder = LivestreamDecoder()

        assertThrows(LivestreamProtocolException::class.java) {
            decoder.decode(frame(42, "nonsense"))
        }
    }

    @Test
    fun `concatenates a box delivered as several chunks`() {
        val decoder = LivestreamDecoder()

        // The negotiated chunk size caps a frame's payload, so a large mdat
        // arrives as a run of MDAT frames. Keeping only the last would hand the
        // demuxer a truncated fragment.
        val segments = decoder.decode(
            frame(LivestreamFrame.BEGIN_SEGMENT, "") +
                frame(LivestreamFrame.MOOF, "MOOF") +
                frame(LivestreamFrame.MDAT, "chunk1") +
                frame(LivestreamFrame.MDAT, "chunk2") +
                frame(LivestreamFrame.MDAT, "chunk3") +
                frame(LivestreamFrame.END_SEGMENT, ""),
        )

        assertEquals(
            "MOOFchunk1chunk2chunk3",
            (segments.single() as LivestreamSegment.Media).data.decodeToString(),
        )
    }

    @Test
    fun `keeps box order when every type is chunked`() {
        val decoder = LivestreamDecoder()

        val segments = decoder.decode(
            frame(LivestreamFrame.BEGIN_SEGMENT, "") +
                frame(LivestreamFrame.MOOF, "m1") +
                frame(LivestreamFrame.MDAT, "d1") +
                frame(LivestreamFrame.VIDEO, "v1") +
                frame(LivestreamFrame.AUDIO, "a1") +
                frame(LivestreamFrame.MOOF, "m2") +
                frame(LivestreamFrame.MDAT, "d2") +
                frame(LivestreamFrame.VIDEO, "v2") +
                frame(LivestreamFrame.AUDIO, "a2") +
                frame(LivestreamFrame.END_SEGMENT, ""),
        )

        // Grouped by box, chunks in arrival order within each box.
        assertEquals(
            "m1m2d1d2v1v2a1a2",
            (segments.single() as LivestreamSegment.Media).data.decodeToString(),
        )
    }

    @Test
    fun `chunks do not survive into the following fragment`() {
        val decoder = LivestreamDecoder()

        decoder.decode(
            frame(LivestreamFrame.BEGIN_SEGMENT, "") +
                frame(LivestreamFrame.MDAT, "old1") +
                frame(LivestreamFrame.MDAT, "old2") +
                frame(LivestreamFrame.END_SEGMENT, ""),
        )
        val segments = decoder.decode(
            frame(LivestreamFrame.BEGIN_SEGMENT, "") +
                frame(LivestreamFrame.MDAT, "new") +
                frame(LivestreamFrame.END_SEGMENT, ""),
        )

        assertEquals("new", (segments.single() as LivestreamSegment.Media).data.decodeToString())
    }
}

package app.dozecam.protect

import android.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Exercised against the real initialization segment a UniFi G6 camera sent
 * over the livestream socket, captured from the device.
 */
@RunWith(RobolectricTestRunner::class)
class Av1ConfigRepairTest {

    private val realInitSegment: ByteArray = Base64.decode(
        "AAAAIGZ0eXBkYXNoAAAAAGlzbzZodmMxYXZjMW1wNDEAAARXbW9vdgAAAGxtdmhkAAAAAOaL953m" +
            "i/edAAAD6AAAAAAAAQAAAQAAAAAAAAAAAAAAAAEAAAAAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAA" +
            "AABAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAwAAAEhtdmV4AAAAIHRyZXgAAAAAAAAA" +
            "AQAAAAEAAAu4AAAAAAABAAAAAAAgdHJleAAAAAAAAAACAAAAAQAABAAAAAAAAAAAAAAAAdt0cmFr" +
            "AAAAXHRraGQAAAAH5ov3neaL950AAAABAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAEAAAAAAAAA" +
            "AAAAAAAAAAABAAAAAAAAAAAAAAAAAABAAAAABQAAAALQAAAAAAAkZWR0cwAAABxlbHN0AAAAAAAA" +
            "AAEAAAAAAAAAAAABAAAAAAFTbWRpYQAAACBtZGhkAAAAAOaL953mi/edAAFfkAAAAABVxAAAAAAA" +
            "LWhkbHIAAAAAAAAAAHZpZGUAAAAAAAAAAAAAAABWaWRlb0hhbmRsZXIAAAAA/m1pbmYAAAAUdm1o" +
            "ZAAAAAEAAAAAAAAAAAAAACRkaW5mAAAAHGRyZWYAAAAAAAAAAQAAAAx1cmwgAAAAAQAAAL5zdGJs" +
            "AAAAcnN0c2QAAAAAAAAAAQAAAGJhdjAxAAAAAAAAAAEAAAAAAAAAAAAAAAAAAAAABQAC0ABIAAAA" +
            "SAAAAAAAAAABFlViaXF1aXRpIE1lZGlhIFNlcnZlciAAAAAAAAAAAAAAGP//AAAADGF2MUOBBSwB" +
            "AAAAEHN0dHMAAAAAAAAAAAAAABBzdHNjAAAAAAAAAAAAAAAUc3RzegAAAAAAAAAAAAAAAAAAABBz" +
            "dGNvAAAAAAAAAAAAAAHAdHJhawAAAFx0a2hkAAAAB+aL953mi/edAAAAAgAAAAAAAAAAAAAAAAAA" +
            "AAAAAAAAAQAAAAABAAAAAAAAAAAAAAAAAAAAAQAAAAAAAAAAAAAAAAAAQAAAAAAAAAAAAAAAAAAA" +
            "JGVkdHMAAAAcZWxzdAAAAAAAAAABAAAAAAAAAAAAAQAAAAABOG1kaWEAAAAgbWRoZAAAAADmi/ed" +
            "5ov3nQAAPoAAAAAAVcQAAAAAAC1oZGxyAAAAAAAAAABzb3VuAAAAAAAAAAAAAAAAU291bmRIYW5k" +
            "bGVyAAAAAONtaW5mAAAAEHNtaGQAAAAAAAAAAAAAACRkaW5mAAAAHGRyZWYAAAAAAAAAAQAAAAx1" +
            "cmwgAAAAAQAAAKdzdGJsAAAAW3N0c2QAAAAAAAAAAQAAAEttcDRhAAAAAAAAAAEAAAAAAAAAAAAC" +
            "ABAAAAAAPoAAAAAAACdlc2RzAAAAAAMZAAIABBFAFQAAAAAAAAAAAAAABQIUCAYBAgAAABBzdHRz" +
            "AAAAAAAAAAAAAAAQc3RzYwAAAAAAAAAAAAAAFHN0c3oAAAAAAAAAAAAAAAAAAAAQc3RjbwAAAAAA" +
            "AAAA",
        Base64.NO_WRAP,
    )

    private fun boxes(buffer: ByteArray): Map<String, Int> {
        // Flat scan for the boxes this repair resizes, with their declared sizes.
        val found = mutableMapOf<String, Int>()
        var offset = 0
        fun walk(from: Int, end: Int) {
            var cursor = from
            while (cursor + 8 <= end) {
                val size = ((buffer[cursor].toInt() and 0xFF) shl 24) or
                    ((buffer[cursor + 1].toInt() and 0xFF) shl 16) or
                    ((buffer[cursor + 2].toInt() and 0xFF) shl 8) or
                    (buffer[cursor + 3].toInt() and 0xFF)
                if (size < 8 || cursor + size > end) return
                val type = buffer.decodeToString(cursor + 4, cursor + 8)
                // First occurrence wins: this segment carries a video trak
                // followed by an audio one, and the assertions below are about
                // the video chain that encloses av1C.
                found.putIfAbsent(type, size)
                val childrenFrom = when (type) {
                    "moov", "trak", "mdia", "minf", "stbl" -> cursor + 8
                    "stsd" -> cursor + 16
                    "av01" -> cursor + 8 + 78
                    else -> null
                }
                if (childrenFrom != null) walk(childrenFrom, cursor + size)
                cursor += size
            }
        }
        walk(offset, buffer.size)
        return found
    }

    @Test
    fun `the captured segment is the shape that breaks Media3`() {
        // Guards the premise: a 12-byte av1C is header plus a bare 4-byte
        // config record, with no configOBUs for the parser to read.
        assertEquals(12, boxes(realInitSegment)["av1C"])
    }

    @Test
    fun `fills in configOBUs so the record is no longer truncated`() {
        val repaired = Av1ConfigRepair.repair(realInitSegment)

        assertEquals(realInitSegment.size + 2, repaired.size)
        assertEquals(14, boxes(repaired)["av1C"])
    }

    @Test
    fun `appends a zero-length temporal delimiter OBU`() {
        val repaired = Av1ConfigRepair.repair(realInitSegment)

        val av1cEnd = repaired.size - (realInitSegment.size - indexOfAv1cEnd(realInitSegment))
        val appended = repaired.copyOfRange(av1cEnd - 2, av1cEnd)
        // obu_type = 2 with a size field, then size 0. Media3 reads the type,
        // finds it is not a sequence header, and returns instead of throwing.
        assertArrayEquals(byteArrayOf(0x12, 0x00), appended)
    }

    @Test
    fun `grows every enclosing box so the tree stays parseable`() {
        val before = boxes(realInitSegment)
        val after = boxes(Av1ConfigRepair.repair(realInitSegment))

        for (type in listOf("moov", "trak", "mdia", "minf", "stbl", "stsd", "av01", "av1C")) {
            assertEquals("$type size", before.getValue(type) + 2, after.getValue(type))
        }
        // The audio track and ftyp are untouched.
        assertEquals(before.getValue("ftyp"), after.getValue("ftyp"))
        assertEquals(before.getValue("mp4a"), after.getValue("mp4a"))
    }

    @Test
    fun `the repaired segment still parses as a complete box tree`() {
        val repaired = Av1ConfigRepair.repair(realInitSegment)

        // A stale ancestor size would desynchronise the scan and lose boxes.
        assertTrue(boxes(repaired).keys.containsAll(boxes(realInitSegment).keys))
    }

    @Test
    fun `leaves a segment that already carries configOBUs alone`() {
        val repaired = Av1ConfigRepair.repair(realInitSegment)

        // Repairing twice must not keep appending.
        assertArrayEquals(repaired, Av1ConfigRepair.repair(repaired))
    }

    @Test
    fun `leaves a segment without an av1C box alone`() {
        val audioOnly = "\u0000\u0000\u0000\u0010ftypisom".encodeToByteArray()

        assertArrayEquals(audioOnly, Av1ConfigRepair.repair(audioOnly))
    }

    @Test
    fun `does not walk off the end of a truncated segment`() {
        val truncated = realInitSegment.copyOfRange(0, 40)

        // Returning it unchanged is correct; throwing would kill playback.
        assertArrayEquals(truncated, Av1ConfigRepair.repair(truncated))
    }

    private fun indexOfAv1cEnd(buffer: ByteArray): Int {
        for (i in 0..buffer.size - 4) {
            if (buffer.decodeToString(i, i + 4) == "av1C") {
                val start = i - 4
                val size = ((buffer[start].toInt() and 0xFF) shl 24) or
                    ((buffer[start + 1].toInt() and 0xFF) shl 16) or
                    ((buffer[start + 2].toInt() and 0xFF) shl 8) or
                    (buffer[start + 3].toInt() and 0xFF)
                return start + size
            }
        }
        error("no av1C")
    }
}

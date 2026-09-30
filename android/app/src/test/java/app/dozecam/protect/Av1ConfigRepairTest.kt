package app.dozecam.protect

import app.dozecam.testing.Fixtures
import kotlinx.serialization.Serializable
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercised against the real initialization segment a UniFi G6 camera sent
 * over the livestream socket, captured from the device. The capture, its
 * repaired form and the sizes the repair must produce are the shared vectors
 * in `shared/fixtures/livestream/av1-config-repair.json`.
 */
class Av1ConfigRepairTest {

    @Serializable
    private data class Table(val repair: Repair, val unchanged: List<Unchanged>)

    @Serializable
    private data class Repair(
        val name: String,
        val input: String,
        val output: String,
        val av1cSizeBefore: Int,
        val av1cSizeAfter: Int,
        val appendedHex: String,
        val grownBoxes: List<String>,
        val untouchedBoxes: List<String>,
    )

    @Serializable
    private data class Unchanged(val name: String, val input: String)

    private val table = Fixtures.decode<Table>("livestream/av1-config-repair.json")
    private val case = table.repair

    private fun bytes(file: String) = Fixtures.bytes("livestream/$file")

    private val realInitSegment: ByteArray = bytes(case.input)

    private val appended: ByteArray =
        case.appendedHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /** Asserts that the fixture's `unchanged` case called [name] comes back byte for byte. */
    private fun assertUnchanged(name: String) {
        val unchanged = table.unchanged.singleOrNull { it.name == name } ?: error("no fixture case \"$name\"")
        val input = bytes(unchanged.input)
        assertArrayEquals(unchanged.name, input, Av1ConfigRepair.repair(input))
    }

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
        assertEquals("${case.name}: av1C before", case.av1cSizeBefore, boxes(realInitSegment)["av1C"])
    }

    @Test
    fun `fills in configOBUs so the record is no longer truncated`() {
        val repaired = Av1ConfigRepair.repair(realInitSegment)

        assertEquals("${case.name}: size", realInitSegment.size + appended.size, repaired.size)
        assertEquals("${case.name}: av1C after", case.av1cSizeAfter, boxes(repaired)["av1C"])
        assertArrayEquals("${case.name}: output", bytes(case.output), repaired)
    }

    @Test
    fun `appends a zero-length temporal delimiter OBU`() {
        val repaired = Av1ConfigRepair.repair(realInitSegment)

        val av1cEnd = repaired.size - (realInitSegment.size - indexOfAv1cEnd(realInitSegment))
        val tail = repaired.copyOfRange(av1cEnd - appended.size, av1cEnd)
        // obu_type = 2 with a size field, then size 0. Media3 reads the type,
        // finds it is not a sequence header, and returns instead of throwing.
        assertArrayEquals("${case.name}: appended", appended, tail)
    }

    @Test
    fun `grows every enclosing box so the tree stays parseable`() {
        val before = boxes(realInitSegment)
        val after = boxes(Av1ConfigRepair.repair(realInitSegment))

        for (type in case.grownBoxes) {
            assertEquals("${case.name}: $type size", before.getValue(type) + appended.size, after.getValue(type))
        }
        // The audio track and ftyp are untouched.
        for (type in case.untouchedBoxes) {
            assertEquals("${case.name}: $type size", before.getValue(type), after.getValue(type))
        }
    }

    @Test
    fun `the repaired segment still parses as a complete box tree`() {
        val repaired = Av1ConfigRepair.repair(realInitSegment)

        // A stale ancestor size would desynchronise the scan and lose boxes.
        assertTrue(case.name, boxes(repaired).keys.containsAll(boxes(realInitSegment).keys))
    }

    @Test
    fun `leaves a segment that already carries configOBUs alone`() {
        // Repairing twice must not keep appending. The fixture's repaired
        // segment is the repair's own output, which the test above holds it to.
        assertUnchanged("a segment that already carries configOBUs is left alone")
    }

    @Test
    fun `leaves a segment without an av1C box alone`() {
        assertUnchanged("a segment without an av1C box is left alone")
    }

    @Test
    fun `does not walk off the end of a truncated segment`() {
        // Returning it unchanged is correct; throwing would kill playback.
        assertUnchanged("a truncated segment is returned unchanged rather than walked off the end of")
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

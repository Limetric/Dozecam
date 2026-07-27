package app.dozecam.protect

/**
 * Makes a Protect AV1 initialization segment survive Media3's `av1C` parser.
 *
 * UniFi's muxer writes the 4-byte `AV1CodecConfigurationRecord` and stops,
 * leaving `configOBUs` empty — legal, since the AV1-in-ISOBMFF spec makes it
 * optional and the sequence header travels in-band. Media3's `BoxParser`
 * reads straight past the record into an OBU header without checking that any
 * bytes remain, so it throws and playback dies before a frame is decoded.
 *
 * The parser does handle an OBU it does not care about: anything other than
 * `OBU_SEQUENCE_HEADER` makes it log and return what it has. So appending one
 * zero-length **temporal delimiter** OBU gives it the bytes it insists on
 * reading, and does so with a real OBU rather than padding — the record stays
 * spec-valid for the decoder, which receives these bytes verbatim as its
 * codec-specific data.
 */
internal object Av1ConfigRepair {

    /** `obu_type = 2` (temporal delimiter), `obu_has_size_field = 1`, then size 0. */
    private val TEMPORAL_DELIMITER_OBU = byteArrayOf(0x12, 0x00)

    /** Size of an ISO-BMFF box header: 4-byte size + 4-byte type. */
    private const val HEADER_SIZE = 8

    /** Bytes of an `AV1CodecConfigurationRecord` before `configOBUs`. */
    private const val CONFIG_RECORD_SIZE = 4

    private val CONTAINERS = setOf("moov", "trak", "mdia", "minf", "stbl")

    /**
     * Returns [init] with an empty `configOBUs` filled in, or [init] unchanged
     * when it has none to fix — no `av1C`, or one that already carries OBUs.
     */
    fun repair(init: ByteArray): ByteArray {
        val path = findAv1c(init, 0, init.size, ArrayDeque()) ?: return init
        val av1cStart = path.last()
        val av1cSize = readSize(init, av1cStart)
        if (av1cSize > HEADER_SIZE + CONFIG_RECORD_SIZE) return init // already present

        val insertAt = av1cStart + av1cSize
        val added = TEMPORAL_DELIMITER_OBU.size
        val repaired = ByteArray(init.size + added)
        init.copyInto(repaired, 0, 0, insertAt)
        TEMPORAL_DELIMITER_OBU.copyInto(repaired, insertAt)
        init.copyInto(repaired, insertAt + added, insertAt, init.size)

        // Every box enclosing the av1C now spans more bytes; a stale size on
        // any ancestor desynchronises the whole tree for the next parser.
        for (boxStart in path) {
            writeSize(repaired, boxStart, readSize(repaired, boxStart) + added)
        }
        return repaired
    }

    /** Offsets of every box from the root down to `av1C`, or null if absent. */
    private fun findAv1c(
        buffer: ByteArray,
        from: Int,
        end: Int,
        ancestors: ArrayDeque<Int>,
    ): List<Int>? {
        var offset = from
        while (offset + HEADER_SIZE <= end) {
            val size = readSize(buffer, offset)
            if (size < HEADER_SIZE || offset + size > end) return null
            val type = buffer.decodeToString(offset + 4, offset + HEADER_SIZE)
            if (type == "av1C") return ancestors.toList() + offset

            // Sample entries and stsd carry their own preamble before children.
            val childrenFrom = when (type) {
                in CONTAINERS -> offset + HEADER_SIZE
                "stsd" -> offset + HEADER_SIZE + 8 // version/flags + entry count
                "av01" -> offset + HEADER_SIZE + 78 // VisualSampleEntry fields
                else -> null
            }
            if (childrenFrom != null) {
                ancestors.addLast(offset)
                findAv1c(buffer, childrenFrom, offset + size, ancestors)?.let { return it }
                ancestors.removeLast()
            }
            offset += size
        }
        return null
    }

    private fun readSize(buffer: ByteArray, offset: Int): Int =
        ((buffer[offset].toInt() and 0xFF) shl 24) or
            ((buffer[offset + 1].toInt() and 0xFF) shl 16) or
            ((buffer[offset + 2].toInt() and 0xFF) shl 8) or
            (buffer[offset + 3].toInt() and 0xFF)

    private fun writeSize(buffer: ByteArray, offset: Int, size: Int) {
        buffer[offset] = (size ushr 24).toByte()
        buffer[offset + 1] = (size ushr 16).toByte()
        buffer[offset + 2] = (size ushr 8).toByte()
        buffer[offset + 3] = size.toByte()
    }
}

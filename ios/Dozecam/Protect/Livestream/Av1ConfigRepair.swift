import Foundation

/// Makes a Protect AV1 initialization segment survive a strict `av1C` parser.
/// The counterpart of Android's `Av1ConfigRepair` (shared/spec/protect.md,
/// "The livestream"; fixtures in `shared/fixtures/livestream`).
///
/// UniFi's muxer writes the 4-byte `AV1CodecConfigurationRecord` and stops,
/// leaving `configOBUs` empty: legal, since the AV1-in-ISOBMFF spec makes it
/// optional and the sequence header travels in-band. Media3's parser reads
/// straight past the record into an OBU header without checking that any
/// bytes remain, so it throws and playback dies before a frame is decoded.
/// libVLC's MP4 demuxer, which iOS uses, does not need it (#66), but it is
/// applied there too and held to the same fixtures.
///
/// Such a parser does handle an OBU it does not care about. So appending one
/// zero-length **temporal delimiter** OBU gives it the bytes it insists on
/// reading, and does so with a real OBU rather than padding: the record stays
/// spec-valid for the decoder, which receives these bytes verbatim as its
/// codec-specific data.
enum Av1ConfigRepair {
    /// `obu_type = 2` (temporal delimiter), `obu_has_size_field = 1`, then size 0.
    private static let temporalDelimiterObu: [UInt8] = [0x12, 0x00]

    /// Size of an ISO-BMFF box header: 4-byte size + 4-byte type.
    private static let headerSize = 8

    /// Bytes of an `AV1CodecConfigurationRecord` before `configOBUs`.
    private static let configRecordSize = 4

    private static let containers: Set<String> = ["moov", "trak", "mdia", "minf", "stbl"]

    /// Returns `initSegment` with an empty `configOBUs` filled in, or
    /// unchanged when it has none to fix: no `av1C`, one that already
    /// carries OBUs, or a box tree that does not add up.
    static func repair(_ initSegment: Data) -> Data {
        let bytes = [UInt8](initSegment)
        guard let path = findAv1c(in: bytes, from: 0, to: bytes.count, ancestors: []), let av1cStart = path.last
        else { return initSegment }
        let av1cSize = readSize(bytes, at: av1cStart)
        if av1cSize > headerSize + configRecordSize { return initSegment }  // already present

        let insertAt = av1cStart + av1cSize
        var repaired = bytes
        repaired.insert(contentsOf: temporalDelimiterObu, at: insertAt)

        // Every box enclosing the av1C now spans more bytes; a stale size on
        // any ancestor desynchronises the whole tree for the next parser.
        for boxStart in path {
            writeSize(&repaired, at: boxStart, readSize(repaired, at: boxStart) + temporalDelimiterObu.count)
        }
        return Data(repaired)
    }

    /// Offsets of every box from the root down to `av1C`, or nil if absent.
    private static func findAv1c(in buffer: [UInt8], from: Int, to end: Int, ancestors: [Int]) -> [Int]? {
        var offset = from
        while offset + headerSize <= end {
            let size = readSize(buffer, at: offset)
            if size < headerSize || offset + size > end { return nil }
            let type = String(decoding: buffer[offset + 4..<offset + headerSize], as: UTF8.self)
            if type == "av1C" { return ancestors + [offset] }

            // Sample entries and stsd carry their own preamble before children.
            let childrenFrom: Int? =
                switch type {
                case _ where containers.contains(type): offset + headerSize
                case "stsd": offset + headerSize + 8  // version/flags + entry count
                case "av01": offset + headerSize + 78  // VisualSampleEntry fields
                default: nil
                }
            if let childrenFrom,
                let found = findAv1c(in: buffer, from: childrenFrom, to: offset + size, ancestors: ancestors + [offset])
            {
                return found
            }
            offset += size
        }
        return nil
    }

    /// A box's declared 32-bit size. Unsigned, so a size past `Int32.max`
    /// overruns the buffer and ends the walk, as Java's negative int does.
    private static func readSize(_ buffer: [UInt8], at offset: Int) -> Int {
        Int(buffer[offset]) << 24 | Int(buffer[offset + 1]) << 16 | Int(buffer[offset + 2]) << 8
            | Int(buffer[offset + 3])
    }

    private static func writeSize(_ buffer: inout [UInt8], at offset: Int, _ size: Int) {
        buffer[offset] = UInt8(truncatingIfNeeded: size >> 24)
        buffer[offset + 1] = UInt8(truncatingIfNeeded: size >> 16)
        buffer[offset + 2] = UInt8(truncatingIfNeeded: size >> 8)
        buffer[offset + 3] = UInt8(truncatingIfNeeded: size)
    }
}

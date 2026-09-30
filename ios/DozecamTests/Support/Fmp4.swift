import Foundation
import Testing

/// A fragmented MP4 test resource split the way Protect sends it: the
/// initialisation segment, then one `moof`+`mdat` pair per fragment.
///
/// The resources in `Resources/` were made with ffmpeg 8 (testsrc2, 320×180,
/// 15 fps, 3 s, a keyframe every 5 frames so every third of a second starts a
/// fragment, no audio):
///
///     ffmpeg -f lavfi -i testsrc2=size=320x180:rate=15 -t 3 \
///       -c:v libx264 -preset veryfast -crf 38 -profile:v baseline -pix_fmt yuv420p \
///       -g 5 -keyint_min 5 -sc_threshold 0 -bf 0 -an \
///       -movflags frag_keyframe+empty_moov+default_base_moof livestream-h264.mp4
///     ffmpeg -f lavfi -i testsrc2=size=320x180:rate=15 -t 3 \
///       -c:v libsvtav1 -preset 12 -crf 55 -g 5 -pix_fmt yuv420p -an \
///       -movflags frag_keyframe+empty_moov+default_base_moof livestream-av1.mp4
struct Fmp4 {
    struct Fragment {
        let moof: Data
        let mdat: Data
    }

    var initSegment: Data
    var fragments: [Fragment]

    static func resource(_ name: String) throws -> Fmp4 {
        let url = try #require(Bundle(for: BundleToken.self).url(forResource: name, withExtension: "mp4"))
        return try Fmp4(Data(contentsOf: url))
    }

    init(_ file: Data) throws {
        let bytes = [UInt8](file)
        var initSegment = Data()
        var fragments: [Fragment] = []
        var pendingMoof: Data?
        var offset = 0
        while offset + 8 <= bytes.count {
            let size = Self.size(bytes, at: offset)
            let type = String(decoding: bytes[offset + 4..<offset + 8], as: UTF8.self)
            guard size >= 8, offset + size <= bytes.count else { throw Malformed() }
            let box = Data(bytes[offset..<offset + size])
            switch type {
            case "ftyp", "moov": initSegment.append(box)
            case "moof": pendingMoof = box
            case "mdat":
                guard let moof = pendingMoof else { throw Malformed() }
                fragments.append(Fragment(moof: moof, mdat: box))
                pendingMoof = nil
            default: break  // mfra and the like: not part of a live stream
            }
            offset += size
        }
        self.initSegment = initSegment
        self.fragments = fragments
    }

    struct Malformed: Error {}

    /// Replaces the video sample entry's type (`avc1`, `av01`) with `fourcc`.
    mutating func renameSampleEntry(_ from: String, to fourcc: String) throws {
        var bytes = [UInt8](initSegment)
        let needle = [UInt8](from.utf8)
        guard let at = (0...(bytes.count - 4)).first(where: { Array(bytes[$0..<$0 + 4]) == needle }) else {
            throw Malformed()
        }
        bytes.replaceSubrange(at..<at + 4, with: Array(fourcc.utf8))
        initSegment = Data(bytes)
    }

    /// Empties the `av1C` record's `configOBUs`, as Protect's muxer writes it
    /// (shared/fixtures/livestream/README.md), shrinking every enclosing box.
    mutating func stripAv1ConfigObus() throws {
        var bytes = [UInt8](initSegment)
        guard let path = Self.path(to: "av1C", in: bytes, from: 0, to: bytes.count, ancestors: []),
            let av1c = path.last
        else { throw Malformed() }
        let removed = Self.size(bytes, at: av1c) - 12  // header + the 4-byte record
        guard removed > 0 else { return }
        bytes.removeSubrange(av1c + 12..<av1c + 12 + removed)
        for box in path { Self.write(&bytes, at: box, Self.size(bytes, at: box) - removed) }
        initSegment = Data(bytes)
    }

    /// The `av1C` box's declared size.
    var av1cSize: Int? {
        let bytes = [UInt8](initSegment)
        return Self.path(to: "av1C", in: bytes, from: 0, to: bytes.count, ancestors: []).map {
            Self.size(bytes, at: $0.last!)
        }
    }

    /// The stream in Protect's wire framing (1-byte type, 3-byte length,
    /// payload): codec string, init segment, then each fragment bracketed by
    /// begin and end, its `moof` in two chunks as a small `chunkSize` makes
    /// the console send it. One element per send: the header and init
    /// segment, then one per fragment, for a server to pace as a camera does.
    func protectFrames(codec: String) -> [Data] {
        var groups = [Self.frame(248, Data(codec.utf8)) + Self.frame(250, initSegment)]
        for fragment in fragments {
            var out = Self.frame(249, Data())
            let half = fragment.moof.count / 2
            out.append(Self.frame(251, fragment.moof.prefix(half)))
            out.append(Self.frame(251, fragment.moof.dropFirst(half)))
            out.append(Self.frame(254, fragment.mdat))
            out.append(Self.frame(255, Data()))
            groups.append(out)
        }
        return groups
    }

    /// Each fragment holds 5 frames at 15 fps.
    static let fragmentDuration: Duration = .milliseconds(333)

    static func frame(_ type: UInt8, _ payload: Data) -> Data {
        let n = payload.count
        return Data([type, UInt8(n >> 16 & 0xFF), UInt8(n >> 8 & 0xFF), UInt8(n & 0xFF)]) + payload
    }

    private static func size(_ b: [UInt8], at o: Int) -> Int {
        Int(b[o]) << 24 | Int(b[o + 1]) << 16 | Int(b[o + 2]) << 8 | Int(b[o + 3])
    }

    private static func write(_ b: inout [UInt8], at o: Int, _ v: Int) {
        b[o] = UInt8(v >> 24 & 0xFF)
        b[o + 1] = UInt8(v >> 16 & 0xFF)
        b[o + 2] = UInt8(v >> 8 & 0xFF)
        b[o + 3] = UInt8(v & 0xFF)
    }

    private static func path(to target: String, in b: [UInt8], from: Int, to end: Int, ancestors: [Int]) -> [Int]? {
        var offset = from
        while offset + 8 <= end {
            let size = size(b, at: offset)
            guard size >= 8, offset + size <= end else { return nil }
            let type = String(decoding: b[offset + 4..<offset + 8], as: UTF8.self)
            if type == target { return ancestors + [offset] }
            let children: Int? =
                switch type {
                case "moov", "trak", "mdia", "minf", "stbl": offset + 8
                case "stsd": offset + 16
                case "av01": offset + 8 + 78
                default: nil
                }
            if let children,
                let found = path(to: target, in: b, from: children, to: offset + size, ancestors: ancestors + [offset])
            {
                return found
            }
            offset += size
        }
        return nil
    }

    private final class BundleToken {}
}

extension Data {
    /// `self` cut into messages of at most `size` bytes, ignoring frame
    /// boundaries, as the console's WebSocket does.
    func messages(of size: Int) -> [Data] {
        stride(from: 0, to: count, by: size).map {
            Data(self[($0 + startIndex)..<Swift.min($0 + startIndex + size, endIndex)])
        }
    }
}

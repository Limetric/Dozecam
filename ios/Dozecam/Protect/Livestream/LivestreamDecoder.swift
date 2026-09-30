import Foundation

/// Frame-type bytes prefixing every payload on Protect's livestream WebSocket.
/// The console emits an initialization segment once, then one group of media
/// frames per fragment, bracketed by `beginSegment` and `endSegment`.
enum LivestreamFrame {
    static let timestamp: UInt8 = 247
    static let codecInformation: UInt8 = 248
    static let beginSegment: UInt8 = 249
    static let initSegment: UInt8 = 250
    static let moof: UInt8 = 251
    static let video: UInt8 = 252
    static let audio: UInt8 = 253
    static let mdat: UInt8 = 254
    static let endSegment: UInt8 = 255
}

/// One complete unit of the fMP4 stream, ready to hand to a demuxer.
enum LivestreamSegment: Equatable, Sendable {
    /// FTYP+MOOV, with the codec string announced before it. Must reach the
    /// demuxer before any `media` fragment.
    case initialization(Data, codec: String)
    /// One complete fMP4 fragment: moof followed by its media boxes.
    case media(Data)
}

/// A frame whose type byte is not part of the protocol: the stream is out of
/// sync, and carrying on would feed the demuxer garbage.
struct LivestreamProtocolError: Error, Equatable, CustomStringConvertible {
    let frameType: UInt8
    var description: String { "Unknown livestream frame type \(frameType); the stream is out of sync" }
}

/// Decodes the livestream wire protocol: a 1-byte frame type, a 3-byte
/// big-endian payload length, then the payload. The counterpart of Android's
/// `LivestreamDecoder` (shared/spec/protect.md, "The livestream"; fixtures in
/// `shared/fixtures/livestream`).
///
/// Two properties of the transport drive the design. WebSocket message
/// boundaries are meaningless here (a frame routinely straddles them, and one
/// message can carry several), so undecodable bytes are carried forward rather
/// than parsed per message. And the console emits a fragment's boxes as
/// separate frames, so they are buffered and concatenated in `moof, mdat,
/// video, audio` order, which is the order a demuxer needs regardless of the
/// order they arrived in.
///
/// A value type holding one stream's state: feed it every message of that
/// stream, in order, from one place.
struct LivestreamDecoder: Sendable {
    /// 1 type byte + 3 length bytes.
    private static let headerBytes = 4

    private var pending: [UInt8] = []
    private var codec = ""

    // Runs of chunks, not single buffers: the negotiated chunk size caps how
    // much of a box rides in one frame, so any box larger than it arrives as a
    // run of frames of the same type. Keeping only the newest would hand the
    // demuxer a truncated moof or mdat: structurally valid framing wrapping
    // unusable fMP4, which fails as a black picture rather than a clean error.
    private var moof: [[UInt8]] = []
    private var mdat: [[UInt8]] = []
    private var video: [[UInt8]] = []
    private var audio: [[UInt8]] = []

    init() {}

    /// Decodes everything `message` completes; a partial tail waits for more
    /// bytes. Throws when a frame type is not part of the protocol.
    mutating func decode(_ message: Data) throws(LivestreamProtocolError) -> [LivestreamSegment] {
        pending.append(contentsOf: message)
        var segments: [LivestreamSegment] = []
        var offset = 0

        while pending.count - offset >= Self.headerBytes {
            let type = pending[offset]
            let length =
                Int(pending[offset + 1]) << 16 | Int(pending[offset + 2]) << 8 | Int(pending[offset + 3])
            let start = offset + Self.headerBytes
            if pending.count - start < length { break }  // payload still in flight

            let payload = Array(pending[start..<start + length])
            if let segment = try consume(type, payload) { segments.append(segment) }
            offset = start + length
        }

        pending.removeFirst(offset)
        return segments
    }

    private mutating func consume(_ type: UInt8, _ payload: [UInt8]) throws(LivestreamProtocolError)
        -> LivestreamSegment?
    {
        switch type {
        case LivestreamFrame.codecInformation:
            codec = String(decoding: payload, as: UTF8.self)
        case LivestreamFrame.initSegment:
            return .initialization(Data(payload), codec: codec)
        case LivestreamFrame.beginSegment:
            clearFragment()
        case LivestreamFrame.moof:
            moof.append(payload)
        case LivestreamFrame.mdat:
            mdat.append(payload)
        case LivestreamFrame.video:
            video.append(payload)
        case LivestreamFrame.audio:
            audio.append(payload)
        case LivestreamFrame.endSegment:
            let fragment = assembleFragment()
            clearFragment()
            // An empty fragment carries no samples; forwarding it would only
            // make the demuxer re-read zero bytes.
            if !fragment.isEmpty { return .media(fragment) }
        case LivestreamFrame.timestamp:
            break  // decode timestamps; the moof carries its own
        default:
            throw LivestreamProtocolError(frameType: type)
        }
        return nil
    }

    /// Concatenates the fragment's chunks in the order a demuxer expects.
    private func assembleFragment() -> Data {
        var fragment = Data()
        for chunks in [moof, mdat, video, audio] {
            for chunk in chunks { fragment.append(contentsOf: chunk) }
        }
        return fragment
    }

    private mutating func clearFragment() {
        moof.removeAll()
        mdat.removeAll()
        video.removeAll()
        audio.removeAll()
    }
}

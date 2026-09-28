package app.dozecam.protect

/**
 * Frame-type bytes prefixing every payload on Protect's livestream WebSocket.
 * The controller emits an initialization segment once, then one group of
 * media frames per fragment, bracketed by [BEGIN_SEGMENT] and [END_SEGMENT].
 */
internal object LivestreamFrame {
    const val TIMESTAMP = 247
    const val CODEC_INFORMATION = 248
    const val BEGIN_SEGMENT = 249
    const val INIT_SEGMENT = 250
    const val MOOF = 251
    const val VIDEO = 252
    const val AUDIO = 253
    const val MDAT = 254
    const val END_SEGMENT = 255
}

/** One complete unit of the fMP4 stream, ready to hand to a demuxer. */
sealed interface LivestreamSegment {

    /** FTYP+MOOV. Must reach the extractor before any [Media] fragment. */
    class Init(val data: ByteArray, val codec: String) : LivestreamSegment

    /** One complete fMP4 fragment: moof followed by its media boxes. */
    class Media(val data: ByteArray) : LivestreamSegment
}

/** A frame whose type byte is not part of the protocol — the stream is desynced. */
class LivestreamProtocolException(message: String) : Exception(message)

/**
 * Decodes the livestream wire protocol: a 1-byte frame type, a 3-byte
 * big-endian payload length, then the payload.
 *
 * Two properties of the transport drive the design. WebSocket message
 * boundaries are meaningless here — a frame routinely straddles them, and one
 * message can carry several — so undecodable bytes are carried forward rather
 * than parsed per message. And the controller emits a fragment's boxes as
 * separate frames, so they are buffered and concatenated in `moof, mdat,
 * video, audio` order, which is the order a demuxer needs regardless of the
 * order they arrived in.
 *
 * Not thread-safe: feed it from the WebSocket's single callback thread.
 */
class LivestreamDecoder {

    private var pending = ByteArray(0)
    private var codec = ""

    // Lists, not single buffers: the negotiated chunk size caps how much of a
    // box rides in one frame, so any box larger than it arrives as a run of
    // frames of the same type. Keeping only the newest would hand the demuxer
    // a truncated moof or mdat — structurally valid framing wrapping unusable
    // fMP4, which fails as a black picture rather than a clean error.
    private val moof = mutableListOf<ByteArray>()
    private val mdat = mutableListOf<ByteArray>()
    private val video = mutableListOf<ByteArray>()
    private val audio = mutableListOf<ByteArray>()

    /** Decodes everything [chunk] completes; a partial tail waits for more bytes. */
    fun decode(chunk: ByteArray): List<LivestreamSegment> {
        pending = if (pending.isEmpty()) chunk else pending + chunk
        val segments = mutableListOf<LivestreamSegment>()
        var offset = 0

        while (pending.size - offset >= HEADER_BYTES) {
            val type = pending[offset].toInt() and 0xFF
            val length = ((pending[offset + 1].toInt() and 0xFF) shl 16) or
                ((pending[offset + 2].toInt() and 0xFF) shl 8) or
                (pending[offset + 3].toInt() and 0xFF)
            val start = offset + HEADER_BYTES
            if (pending.size - start < length) break // payload still in flight

            val payload = pending.copyOfRange(start, start + length)
            consume(type, payload)?.let(segments::add)
            offset = start + length
        }

        pending = if (offset == 0) pending else pending.copyOfRange(offset, pending.size)
        return segments
    }

    private fun consume(type: Int, payload: ByteArray): LivestreamSegment? {
        when (type) {
            LivestreamFrame.CODEC_INFORMATION -> codec = payload.decodeToString()
            LivestreamFrame.INIT_SEGMENT -> return LivestreamSegment.Init(payload, codec)
            LivestreamFrame.BEGIN_SEGMENT -> clearFragment()
            LivestreamFrame.MOOF -> moof += payload
            LivestreamFrame.MDAT -> mdat += payload
            LivestreamFrame.VIDEO -> video += payload
            LivestreamFrame.AUDIO -> audio += payload
            LivestreamFrame.END_SEGMENT -> {
                val fragment = assembleFragment()
                clearFragment()
                // An empty fragment carries no samples; forwarding it would
                // only make the extractor re-read zero bytes.
                if (fragment.isNotEmpty()) return LivestreamSegment.Media(fragment)
            }
            LivestreamFrame.TIMESTAMP -> Unit // decode timestamps; the moof carries its own
            else -> throw LivestreamProtocolException(
                "Unknown livestream frame type $type; the stream is out of sync",
            )
        }
        return null
    }

    /** Concatenates the fragment's chunks in the order a demuxer expects. */
    private fun assembleFragment(): ByteArray {
        val boxes = listOf(moof, mdat, video, audio)
        val total = boxes.sumOf { chunks -> chunks.sumOf { it.size } }
        if (total == 0) return ByteArray(0)
        val fragment = ByteArray(total)
        var offset = 0
        for (chunks in boxes) {
            for (chunk in chunks) {
                chunk.copyInto(fragment, offset)
                offset += chunk.size
            }
        }
        return fragment
    }

    private fun clearFragment() {
        moof.clear()
        mdat.clear()
        video.clear()
        audio.clear()
    }

    private companion object {
        /** 1 type byte + 3 length bytes. */
        const val HEADER_BYTES = 4
    }
}

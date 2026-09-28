package app.dozecam.audio.talkback

/**
 * Wraps encoded Opus frames in RTP headers, one frame per datagram.
 *
 * There is no signalling with the camera — no SDP, no negotiation, nothing that
 * agrees a payload type — so every field here is a guess that has to match what
 * cameras already accept. They accept ffmpeg's, which stamps dynamic payload
 * type 97 and a 48 kHz clock on a 24 kHz mono stream and is demonstrably
 * ignored on the far side. This reproduces that rather than deriving something
 * more defensible, because reproducing it is the part that was tested.
 *
 * Not thread-safe: one per talk-back press, used from the sending thread.
 */
class RtpPacketiser(
    private val ssrc: Int,
    private val timestampIncrement: Int,
    startSequence: Int = 0,
    startTimestamp: Int = 0,
) {
    private var sequence: Int = startSequence and 0xFFFF
    private var timestamp: Int = startTimestamp
    private var first = true

    fun packetise(opusFrame: ByteArray): ByteArray {
        val packet = ByteArray(HEADER_BYTES + opusFrame.size)

        // Version 2, no padding, no extension, no CSRCs.
        packet[0] = 0x80.toByte()
        // The marker bit opens a talkspurt (RFC 3551); after silence, this
        // packet is the first sound. Everything after it is a continuation.
        packet[1] = if (first) (PAYLOAD_TYPE or 0x80).toByte() else PAYLOAD_TYPE.toByte()
        packet.putShort(2, sequence)
        packet.putInt(4, timestamp)
        packet.putInt(8, ssrc)
        opusFrame.copyInto(packet, HEADER_BYTES)

        first = false
        sequence = (sequence + 1) and 0xFFFF
        // Deliberately allowed to wrap: RTP timestamps are modulo 2^32 and the
        // receiver reads differences, not absolutes.
        timestamp += timestampIncrement
        return packet
    }

    private fun ByteArray.putShort(at: Int, value: Int) {
        this[at] = (value ushr 8).toByte()
        this[at + 1] = value.toByte()
    }

    private fun ByteArray.putInt(at: Int, value: Int) {
        this[at] = (value ushr 24).toByte()
        this[at + 1] = (value ushr 16).toByte()
        this[at + 2] = (value ushr 8).toByte()
        this[at + 3] = value.toByte()
    }

    companion object {
        const val HEADER_BYTES = 12

        /**
         * ffmpeg's choice for Opus, and therefore the one cameras have been
         * seen to tolerate. Nothing reads it, but nothing has been tested
         * without it either.
         */
        const val PAYLOAD_TYPE = 97
    }
}

package app.dozecam.audio.talkback

import android.media.MediaCodec
import android.media.MediaFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Turns 16-bit mono PCM into Opus frames with the platform encoder.
 *
 * `c2.android.opus.encoder` has been in AOSP's default software codec list
 * since Android 12, below our minSdk, so this needs neither a new dependency
 * nor a version check. It advertises 8, 12, 16, 24 and 48 kHz —
 * [TalkbackFormat] refuses anything else before we get here.
 *
 * Synchronous rather than callback-driven on purpose: talk-back is one paced
 * loop on one thread, and a queue between the microphone and the socket is the
 * thing that produces the bursts the pacer exists to avoid.
 */
class OpusEncoder(
    sampleRate: Int,
    bitRate: Int = DEFAULT_BIT_RATE,
) : FrameEncoder {

    private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
    private val info = MediaCodec.BufferInfo()
    private var started = false

    init {
        val format = MediaFormat
            .createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, sampleRate, CHANNELS)
            .apply {
                setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_BYTES)
            }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        started = true
    }

    /**
     * Encodes one frame's worth of samples, returning whatever frames the
     * encoder was ready to part with — usually one, occasionally none while it
     * primes, never a partial frame.
     */
    override fun encode(pcm: ShortArray, presentationTimeUs: Long): List<ByteArray> {
        val inputIndex = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
        if (inputIndex >= 0) {
            val input = codec.getInputBuffer(inputIndex)!!
            input.clear()
            // MediaCodec wants native-endian shorts; the wire order is the
            // packetiser's problem, not the encoder's.
            input.order(ByteOrder.nativeOrder()).asShortBuffer().put(pcm)
            codec.queueInputBuffer(inputIndex, 0, pcm.size * Short.SIZE_BYTES, presentationTimeUs, 0)
        }
        return drain()
    }

    /**
     * Everything the encoder is still holding, once there is no more to say.
     *
     * Opus encodes with a few milliseconds of lookahead, so the last frames fed
     * in are still inside when a button comes up. Dropping them truncates the
     * final word; the tail of silence that follows has nothing to push them out
     * with, because that silence is queued after them.
     */
    override fun finish(): List<ByteArray> {
        val inputIndex = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
        if (inputIndex >= 0) {
            codec.queueInputBuffer(
                inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM,
            )
        }
        return drain()
    }

    private fun drain(): List<ByteArray> {
        val frames = mutableListOf<ByteArray>()
        while (true) {
            val outputIndex = codec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)
            if (outputIndex < 0) return frames
            val output = codec.getOutputBuffer(outputIndex)
            // The encoder opens with OpusHead and OpusTags. They describe the
            // stream for a container; there is no container here, and a camera
            // handed them as audio would be handed noise.
            val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
            if (output != null && !isConfig && info.size > 0) {
                frames += output.copyOfRange(info.offset, info.size)
            }
            codec.releaseOutputBuffer(outputIndex, false)
        }
    }

    private fun ByteBuffer.copyOfRange(offset: Int, size: Int): ByteArray {
        val bytes = ByteArray(size)
        position(offset)
        get(bytes)
        return bytes
    }

    override fun close() {
        if (started) {
            runCatching { codec.stop() }
            started = false
        }
        codec.release()
    }

    companion object {
        private const val CHANNELS = 1

        /** Speech at 24 kHz mono; the camera is a small speaker in a room. */
        const val DEFAULT_BIT_RATE = 32_000

        private const val MAX_INPUT_BYTES = 8_192

        /**
         * Short but not zero. The loop is paced by wall clock, so a poll that
         * blocks briefly costs nothing, and one that never blocks spins.
         */
        private const val DEQUEUE_TIMEOUT_US = 5_000L
    }
}

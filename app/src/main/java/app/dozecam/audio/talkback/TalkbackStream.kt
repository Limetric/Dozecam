package app.dozecam.audio.talkback

import java.io.Closeable
import java.util.concurrent.locks.LockSupport

/** Where a frame's worth of microphone samples comes from. */
interface PcmSource : Closeable {
    /** Fills [into] with one frame, blocking until it can. False when finished. */
    fun read(into: ShortArray): Boolean
}

/** Turns PCM frames into encoded ones, holding back whatever it must. */
interface FrameEncoder : Closeable {
    fun encode(pcm: ShortArray, presentationTimeUs: Long): List<ByteArray>

    /** Everything still held, once there is no more to say. */
    fun finish(): List<ByteArray>
}

/** Where an encoded frame goes. */
fun interface FrameSink {
    fun send(frame: ByteArray)
}

/**
 * One press of a talk-back button, start to finish.
 *
 * Silence, then the room, then silence again. The opening frames cover the
 * click a camera makes starting its jitter buffer, so it lands before the first
 * syllable rather than on it; the closing ones give that buffer something real
 * to drain, instead of leaving the decoder to invent an ending out of the last
 * word it heard.
 *
 * Every frame is timed against the moment the button went down rather than
 * against the frame before it, because the camera cannot ask anyone to slow
 * down and a burst is what it hears when nobody does.
 *
 * Blocking, and meant for a thread of its own: [run] returns when the button
 * comes up and the tail has gone out.
 */
class TalkbackStream(
    private val format: TalkbackFormat.Speakable,
    private val source: PcmSource,
    private val encoder: FrameEncoder,
    private val sink: FrameSink,
    private val clock: () -> Long = System::nanoTime,
    private val park: (Long) -> Unit = LockSupport::parkNanos,
) {
    /**
     * Sends until [isHeld] goes false, then sends the tail. Returns how many
     * encoded frames left the phone — which says nothing about how many
     * arrived, because nothing here can.
     */
    fun run(isHeld: () -> Boolean): Int {
        val pacer = TalkbackPacer(clock())
        val silence = ShortArray(format.frameSamples)
        val captured = ShortArray(format.frameSamples)
        var index = 0L
        var sent = 0

        fun pace() = park(pacer.waitNanos(index, clock()))
        fun emit(frames: List<ByteArray>) {
            frames.forEach { sink.send(it) }
            sent += frames.size
        }

        repeat(TalkbackPacer.LEAD_IN_FRAMES) {
            pace()
            emit(encoder.encode(silence, index.presentationTimeUs()))
            index++
        }

        while (isHeld()) {
            pace()
            // A microphone that has stopped producing ends the press: carrying
            // on would send the same stale frame at twenty millisecond
            // intervals for as long as somebody leaned on the button.
            if (!source.read(captured)) break
            emit(encoder.encode(captured, index.presentationTimeUs()))
            index++
        }

        repeat(TalkbackPacer.LEAD_OUT_FRAMES) {
            pace()
            emit(encoder.encode(silence, index.presentationTimeUs()))
            index++
        }

        emit(encoder.finish())
        return sent
    }

    private fun Long.presentationTimeUs(): Long = this * TalkbackFormat.FRAME_MILLIS * 1_000L
}

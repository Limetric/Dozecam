package app.dozecam.player

import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import android.net.Uri

/**
 * Hands fMP4 from the WebSocket thread to ExoPlayer's loader thread.
 *
 * The queue is bounded on purpose. A live camera has no back pressure — the
 * console keeps sending whether or not anything is draining — so an unbounded
 * buffer would answer a stalled decoder by growing until the process dies.
 * Overflowing instead fails the stream, which the watchdog can see and
 * reconnect from.
 */
class LivestreamPipe(private val maxPendingSegments: Int = MAX_PENDING_SEGMENTS) {

    private val queue = LinkedBlockingQueue<ByteArray>(maxPendingSegments)

    @Volatile
    private var failure: Throwable? = null

    @Volatile
    private var finished = false

    // Touched only by the consumer thread.
    private var current: ByteArray = EMPTY
    private var position = 0

    /** Producer side. Returns false when the consumer has fallen too far behind. */
    fun offer(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return true
        return queue.offer(bytes)
    }

    /** Producer side: the stream ended for a reason the consumer should surface. */
    fun fail(cause: Throwable) {
        if (failure == null) failure = cause
        finished = true
    }

    /** Producer side: the stream ended cleanly. */
    fun finish() {
        finished = true
    }

    /**
     * Consumer side. Blocks until bytes arrive, the stream ends, or it fails;
     * returns the byte count, or [C.RESULT_END_OF_INPUT] at the end.
     */
    @Throws(IOException::class)
    fun read(target: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        while (position >= current.size) {
            failure?.let { throw IOException("Livestream failed", it) }
            val next = try {
                queue.poll(POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                // ExoPlayer cancels a load by interrupting its loader thread.
                // Restore the flag and answer in the currency it expects — a
                // bare InterruptedException here reads as an unexplained
                // playback error instead of an orderly cancellation.
                Thread.currentThread().interrupt()
                throw InterruptedIOException("Livestream read interrupted")
            }
            if (next == null) {
                // Only end the stream once the producer is done *and* the
                // backlog is drained, or the last segments would be dropped.
                if (finished && queue.isEmpty()) {
                    failure?.let { throw IOException("Livestream failed", it) }
                    return C.RESULT_END_OF_INPUT
                }
                continue
            }
            current = next
            position = 0
        }
        val count = minOf(length, current.size - position)
        current.copyInto(target, offset, position, position + count)
        position += count
        return count
    }

    private companion object {
        val EMPTY = ByteArray(0)

        /** ~25 s of 100 ms fragments: a real stall, not a passing hiccup. */
        const val MAX_PENDING_SEGMENTS = 256
        const val POLL_TIMEOUT_MS = 250L
    }
}

/**
 * Presents a [LivestreamPipe] as an ExoPlayer source. The stream is live and
 * unbounded, so it reports an unknown length and is never seekable.
 */
class LivestreamDataSource(private val pipe: LivestreamPipe) : BaseDataSource(true) {

    private var uri: Uri? = null
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        opened = true
        transferStarted(dataSpec)
        return C.LENGTH_UNSET.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val count = pipe.read(buffer, offset, length)
        if (count != C.RESULT_END_OF_INPUT) bytesTransferred(count)
        return count
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        if (opened) {
            opened = false
            transferEnded()
        }
        uri = null
    }

    /** One pipe, one connection: a reconnect builds a new source over a new pipe. */
    class Factory(private val pipe: LivestreamPipe) : DataSource.Factory {
        override fun createDataSource(): DataSource = LivestreamDataSource(pipe)
    }
}

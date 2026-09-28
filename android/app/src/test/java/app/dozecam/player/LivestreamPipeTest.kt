package app.dozecam.player

import androidx.media3.common.C
import java.io.IOException
import java.io.InterruptedIOException
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LivestreamPipeTest {

    private fun LivestreamPipe.readString(length: Int): String {
        val buffer = ByteArray(length)
        val count = read(buffer, 0, length)
        return if (count == C.RESULT_END_OF_INPUT) "" else buffer.decodeToString(0, count)
    }

    @Test
    fun `reads back what was offered`() {
        val pipe = LivestreamPipe()

        assertTrue(pipe.offer("moof".encodeToByteArray()))

        assertEquals("moof", pipe.readString(16))
    }

    @Test
    fun `splits one segment across several reads`() {
        val pipe = LivestreamPipe()
        pipe.offer("abcdef".encodeToByteArray())

        assertEquals("ab", pipe.readString(2))
        assertEquals("cd", pipe.readString(2))
        assertEquals("ef", pipe.readString(2))
    }

    @Test
    fun `does not merge two segments into one read`() {
        val pipe = LivestreamPipe()
        pipe.offer("aa".encodeToByteArray())
        pipe.offer("bb".encodeToByteArray())

        // A read never spans segments, so a short count is expected here.
        assertEquals("aa", pipe.readString(16))
        assertEquals("bb", pipe.readString(16))
    }

    @Test
    fun `reports end of input only once the backlog is drained`() {
        val pipe = LivestreamPipe()
        pipe.offer("tail".encodeToByteArray())
        pipe.finish()

        assertEquals("tail", pipe.readString(16))
        assertEquals(C.RESULT_END_OF_INPUT, pipe.read(ByteArray(16), 0, 16))
    }

    @Test
    fun `surfaces a producer failure to the reader`() {
        val pipe = LivestreamPipe()
        pipe.fail(IOException("socket died"))

        assertThrows(IOException::class.java) { pipe.read(ByteArray(16), 0, 16) }
    }

    @Test
    fun `rejects an offer once the consumer falls too far behind`() {
        val pipe = LivestreamPipe(maxPendingSegments = 2)

        assertTrue(pipe.offer("1".encodeToByteArray()))
        assertTrue(pipe.offer("2".encodeToByteArray()))
        // Overflowing fails the stream rather than growing without bound.
        assertFalse(pipe.offer("3".encodeToByteArray()))
    }

    @Test
    fun `blocks until the producer supplies bytes`() {
        val pipe = LivestreamPipe()
        val producer = thread {
            Thread.sleep(300) // longer than one poll window
            pipe.offer("late".encodeToByteArray())
        }

        val read = pipe.readString(16)
        producer.join()

        assertEquals("late", read)
    }

    @Test
    fun `an empty offer is a no-op rather than a phantom segment`() {
        val pipe = LivestreamPipe()

        assertTrue(pipe.offer(ByteArray(0)))
        pipe.finish()

        assertEquals(C.RESULT_END_OF_INPUT, pipe.read(ByteArray(16), 0, 16))
    }

    @Test
    fun `an interrupted read is reported as cancellation, not a playback error`() {
        val pipe = LivestreamPipe()
        var thrown: Throwable? = null
        var interruptFlagKept = false

        val reader = thread {
            try {
                pipe.read(ByteArray(16), 0, 16)
            } catch (e: Throwable) {
                thrown = e
                interruptFlagKept = Thread.currentThread().isInterrupted
            }
        }
        Thread.sleep(100) // let it block on the queue
        reader.interrupt()
        reader.join(5_000)

        assertTrue("expected InterruptedIOException, got $thrown", thrown is InterruptedIOException)
        assertTrue("interrupt flag must be restored", interruptFlagKept)
    }
}

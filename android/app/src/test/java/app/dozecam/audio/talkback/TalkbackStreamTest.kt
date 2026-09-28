package app.dozecam.audio.talkback

import app.dozecam.protect.TalkbackSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TalkbackStreamTest {

    private val format = TalkbackFormat.of(
        TalkbackSession("rtp://192.168.1.12:7004", "opus", 24_000, 16),
    ) as TalkbackFormat.Speakable

    private val leadIn = TalkbackPacer.LEAD_IN_FRAMES
    private val leadOut = TalkbackPacer.LEAD_OUT_FRAMES

    /** Hands out a recognisable frame each read, and can run dry. */
    private class FakeMicrophone(private var framesAvailable: Int = Int.MAX_VALUE) : PcmSource {
        var reads = 0
            private set
        var closed = false
            private set

        override fun read(into: ShortArray): Boolean {
            if (framesAvailable <= 0) return false
            framesAvailable--
            reads++
            into.fill(MIC_SAMPLE)
            return true
        }

        override fun close() {
            closed = true
        }
    }

    /** Records what it was asked to encode; emits one frame per call. */
    private class FakeEncoder : FrameEncoder {
        val encoded = mutableListOf<ShortArray>()
        val presentationTimes = mutableListOf<Long>()
        var finished = false
            private set

        override fun encode(pcm: ShortArray, presentationTimeUs: Long): List<ByteArray> {
            encoded += pcm.copyOf()
            presentationTimes += presentationTimeUs
            return listOf(byteArrayOf(encoded.size.toByte()))
        }

        override fun finish(): List<ByteArray> {
            finished = true
            return emptyList()
        }

        override fun close() = Unit
    }

    private fun stream(
        source: PcmSource,
        encoder: FrameEncoder,
        sink: MutableList<ByteArray>,
        clock: () -> Long = { 0L },
        park: (Long) -> Unit = {},
    ) = TalkbackStream(format, source, encoder, { sink += it }, clock, park)

    @Test
    fun `a press is silence, then the room, then silence`() {
        val mic = FakeMicrophone()
        val encoder = FakeEncoder()
        val sent = mutableListOf<ByteArray>()
        var held = true
        var pressFrames = 0

        stream(mic, encoder, sent).run {
            // Three frames of actual talking, then the button comes up.
            if (++pressFrames > 3) held = false
            held
        }

        assertEquals(leadIn + 3 + leadOut, encoder.encoded.size)
        assertTrue(
            "the lead-in must be silent",
            encoder.encoded.take(leadIn).all { frame -> frame.all { it == 0.toShort() } },
        )
        assertTrue(
            "the room must reach the encoder",
            encoder.encoded.drop(leadIn).take(3).all { frame -> frame.all { it == MIC_SAMPLE } },
        )
        assertTrue(
            "the lead-out must be silent",
            encoder.encoded.takeLast(leadOut).all { frame -> frame.all { it == 0.toShort() } },
        )
    }

    @Test
    fun `the microphone is not read before the lead-in has gone out`() {
        val mic = FakeMicrophone()
        val encoder = FakeEncoder()
        val sent = mutableListOf<ByteArray>()
        var frames = 0

        stream(mic, encoder, sent).run { ++frames <= 1 }

        // One held frame read, and not one sample more: the lead-in and the
        // tail are generated, never captured.
        assertEquals(1, mic.reads)
    }

    /**
     * A microphone that stops producing ends the press. Carrying on would send
     * the same stale frame every twenty milliseconds for as long as somebody
     * leaned on the button.
     */
    @Test
    fun `a microphone that runs dry ends the press without waiting for release`() {
        val mic = FakeMicrophone(framesAvailable = 2)
        val encoder = FakeEncoder()
        val sent = mutableListOf<ByteArray>()

        val count = stream(mic, encoder, sent).run { true }

        assertEquals(2, mic.reads)
        assertEquals(leadIn + 2 + leadOut, count)
    }

    @Test
    fun `every frame is sent, and the encoder is asked for its last word`() {
        val mic = FakeMicrophone()
        val encoder = FakeEncoder()
        val sent = mutableListOf<ByteArray>()
        var frames = 0

        val count = stream(mic, encoder, sent).run { ++frames <= 5 }

        assertEquals(leadIn + 5 + leadOut, sent.size)
        assertEquals(sent.size, count)
        assertTrue("the encoder must be flushed on release", encoder.finished)
    }

    /**
     * Presentation times are the schedule restated: twenty milliseconds a
     * frame from the press, whatever the wall clock did in between.
     */
    @Test
    fun `presentation times advance one frame at a time`() {
        val mic = FakeMicrophone()
        val encoder = FakeEncoder()
        val sent = mutableListOf<ByteArray>()
        var frames = 0

        stream(mic, encoder, sent).run { ++frames <= 2 }

        val expected = (0 until leadIn + 2 + leadOut).map { it * 20_000L }
        assertEquals(expected, encoder.presentationTimes)
    }

    /**
     * The clock the pacer is given never moves here, so every frame is
     * perpetually early and every wait is a full frame. What matters is that
     * the waits are computed at all, and from the absolute schedule.
     */
    @Test
    fun `each frame waits for its own moment`() {
        val mic = FakeMicrophone()
        val encoder = FakeEncoder()
        val sent = mutableListOf<ByteArray>()
        val waits = mutableListOf<Long>()
        var frames = 0

        stream(mic, encoder, sent, clock = { 0L }, park = { waits += it }).run { ++frames <= 1 }

        assertEquals((0 until leadIn + 1 + leadOut).map { it * 20_000_000L }, waits)
    }

    /** A press that is over before it starts still opens and closes cleanly. */
    @Test
    fun `a button released instantly still sends both silences`() {
        val mic = FakeMicrophone()
        val encoder = FakeEncoder()
        val sent = mutableListOf<ByteArray>()

        val count = stream(mic, encoder, sent).run { false }

        assertEquals(0, mic.reads)
        assertEquals(leadIn + leadOut, count)
        assertTrue(encoder.finished)
    }

    private companion object {
        const val MIC_SAMPLE: Short = 1234
    }
}

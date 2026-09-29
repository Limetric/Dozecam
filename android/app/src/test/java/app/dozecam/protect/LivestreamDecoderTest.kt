package app.dozecam.protect

import app.dozecam.testing.Fixtures
import kotlinx.serialization.Serializable
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The byte streams and what they must decode to are the shared vectors in
 * `shared/fixtures/livestream` (see its README for the wire format).
 */
class LivestreamDecoderTest {

    @Serializable
    private data class Table(val cases: List<Case>)

    @Serializable
    private data class Case(val name: String, val messages: List<Message>)

    @Serializable
    private data class Message(
        val file: String,
        val segments: List<Segment>? = null,
        val error: String? = null,
    )

    @Serializable
    private data class Segment(
        val type: String,
        val codec: String? = null,
        val text: String? = null,
        val file: String? = null,
    )

    private val table = Fixtures.decode<Table>("livestream/decoder.json")

    private fun bytes(file: String) = Fixtures.bytes("livestream/$file")

    /** Feeds the fixture case called [name] to a fresh decoder, message by message. */
    private fun play(name: String) {
        val case = table.cases.singleOrNull { it.name == name } ?: error("no fixture case \"$name\"")
        val decoder = LivestreamDecoder()
        case.messages.forEachIndexed { i, message ->
            val where = "${case.name}, message ${i + 1}"
            val chunk = bytes(message.file)
            when (message.error) {
                null -> {
                    val expected = message.segments ?: error("$where: neither segments nor error")
                    val segments = decoder.decode(chunk)
                    assertEquals("$where: segment count", expected.size, segments.size)
                    expected.zip(segments).forEachIndexed { n, (want, got) ->
                        check("$where, segment ${n + 1}", want, got)
                    }
                }
                "protocol" -> assertThrows(where, LivestreamProtocolException::class.java) {
                    decoder.decode(chunk)
                }
                else -> error("$where: unknown error ${message.error}")
            }
        }
    }

    private fun check(where: String, want: Segment, got: LivestreamSegment) {
        val data = when {
            want.type == "init" && got is LivestreamSegment.Init -> {
                want.codec?.let { assertEquals("$where: codec", it, got.codec) }
                got.data
            }
            want.type == "media" && got is LivestreamSegment.Media -> got.data
            else -> throw AssertionError("$where: expected ${want.type}, got ${got::class.simpleName}")
        }
        val expected = want.text?.encodeToByteArray()
            ?: want.file?.let(::bytes)
            ?: error("$where: segment has neither text nor file")
        assertArrayEquals("$where: data", expected, data)
    }

    @Test
    fun `emits the init segment with the codec announced before it`() {
        play("emits the init segment with the codec announced before it")
    }

    @Test
    fun `assembles a fragment in moof mdat video audio order`() {
        // Deliberately out of order on the wire.
        play("assembles a fragment in moof mdat video audio order")
    }

    @Test
    fun `carries a frame split across websocket messages`() {
        // Split mid-header, then mid-payload: both must survive.
        play("carries a frame split across websocket messages")
    }

    @Test
    fun `decodes several fragments arriving in one message`() {
        play("decodes several fragments arriving in one message")
    }

    @Test
    fun `does not leak boxes from one fragment into the next`() {
        // The second fragment carries no audio; the first one's must not ride along.
        play("does not leak boxes from one fragment into the next")
    }

    @Test
    fun `ignores an empty fragment rather than emitting zero bytes`() {
        play("ignores an empty fragment rather than emitting zero bytes")
    }

    @Test
    fun `ignores timestamp frames`() {
        play("ignores timestamp frames")
    }

    @Test
    fun `reads a payload longer than a 16-bit length`() {
        play("reads a payload longer than a 16-bit length")
    }

    @Test
    fun `rejects an unknown frame type instead of desyncing silently`() {
        play("rejects an unknown frame type instead of desyncing silently")
    }

    @Test
    fun `concatenates a box delivered as several chunks`() {
        // The negotiated chunk size caps a frame's payload, so a large mdat
        // arrives as a run of MDAT frames. Keeping only the last would hand the
        // demuxer a truncated fragment.
        play("concatenates a box delivered as several chunks")
    }

    @Test
    fun `keeps box order when every type is chunked`() {
        // Grouped by box, chunks in arrival order within each box.
        play("keeps box order when every type is chunked")
    }

    @Test
    fun `chunks do not survive into the following fragment`() {
        play("chunks do not survive into the following fragment")
    }
}

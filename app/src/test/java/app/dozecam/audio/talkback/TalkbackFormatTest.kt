package app.dozecam.audio.talkback

import app.dozecam.protect.TalkbackSession
import org.junit.Assert.assertEquals
import org.junit.Test

class TalkbackFormatTest {

    private fun session(
        url: String = "rtp://192.168.1.12:7004",
        codec: String = "opus",
        rate: Int = 24_000,
    ) = TalkbackSession(url, codec, rate, 16)

    @Test
    fun `the usual camera is speakable, at twenty milliseconds a frame`() {
        val format = TalkbackFormat.of(session()) as TalkbackFormat.Speakable

        assertEquals("192.168.1.12", format.host)
        assertEquals(7004, format.port)
        assertEquals(24_000, format.sampleRate)
        assertEquals(480, format.frameSamples)
        assertEquals(960, format.rtpTimestampIncrement)
    }

    /**
     * The RTP clock is 48 kHz whatever the camera asked to be fed, so the
     * timestamp step is the one number that does not move with the rate.
     */
    @Test
    fun `the rtp timestamp step ignores the sampling rate`() {
        val slow = TalkbackFormat.of(session(rate = 16_000)) as TalkbackFormat.Speakable
        val fast = TalkbackFormat.of(session(rate = 48_000)) as TalkbackFormat.Speakable

        assertEquals(320, slow.frameSamples)
        assertEquals(960, slow.rtpTimestampIncrement)
        assertEquals(960, fast.frameSamples)
        assertEquals(960, fast.rtpTimestampIncrement)
    }

    @Test
    fun `codecs android cannot encode are refused rather than attempted`() {
        assertEquals(
            TalkbackFormat.Refused(TalkbackFormat.Reason.CODEC_NOT_ENCODABLE),
            TalkbackFormat.of(session(codec = "vorbis")),
        )
        assertEquals(
            TalkbackFormat.Refused(TalkbackFormat.Reason.CODEC_NOT_ENCODABLE),
            TalkbackFormat.of(session(codec = "aac")),
        )
    }

    @Test
    fun `a rate the platform encoder will not take is refused`() {
        assertEquals(
            TalkbackFormat.Refused(TalkbackFormat.Reason.RATE_NOT_ENCODABLE),
            TalkbackFormat.of(session(rate = 44_100)),
        )
    }

    @Test
    fun `a camera we cannot address is refused before a socket is opened`() {
        assertEquals(
            TalkbackFormat.Refused(TalkbackFormat.Reason.NO_ADDRESS),
            TalkbackFormat.of(session(url = "not a url")),
        )
    }
}

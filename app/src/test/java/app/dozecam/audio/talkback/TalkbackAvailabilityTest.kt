package app.dozecam.audio.talkback

import app.dozecam.protect.TalkbackSession
import org.junit.Assert.assertEquals
import org.junit.Test

class TalkbackAvailabilityTest {

    private val speakable = TalkbackFormat.of(
        TalkbackSession("rtp://192.168.1.12:7004", "opus", 24_000, 16),
    )

    private fun availability(
        hasSpeaker: Boolean = true,
        hasApiKey: Boolean = true,
        format: TalkbackFormat? = speakable,
        reachable: Boolean? = true,
        microphoneGranted: Boolean = true,
        locked: Boolean = false,
    ) = TalkbackAvailability.of(
        hasSpeaker, hasApiKey, format, reachable, microphoneGranted, locked,
    )

    @Test
    fun `a reachable camera with a speaker and permission is ready`() {
        assertEquals(TalkbackAvailability.Ready, availability())
    }

    @Test
    fun `nothing is resolved until the format and the probe have answered`() {
        assertEquals(TalkbackAvailability.Resolving, availability(format = null))
        assertEquals(TalkbackAvailability.Resolving, availability(reachable = null))
    }

    /**
     * The camera streams video through the console and cannot be spoken to
     * directly. This is the failure that looks most like a bug, so it gets its
     * own answer rather than being folded into "unsupported".
     */
    @Test
    fun `a camera off this network is unreachable rather than unsupported`() {
        assertEquals(TalkbackAvailability.Unreachable, availability(reachable = false))
    }

    @Test
    fun `permission is asked for only once everything else has checked out`() {
        assertEquals(
            TalkbackAvailability.NeedsPermission,
            availability(microphoneGranted = false),
        )
    }

    /** A permission dialog cannot be answered over a keyguard. */
    @Test
    fun `a locked phone is told to unlock rather than shown a dialog`() {
        assertEquals(
            TalkbackAvailability.NeedsUnlock,
            availability(microphoneGranted = false, locked = true),
        )
    }

    /** Locked but already granted is just ready: nothing needs answering. */
    @Test
    fun `a locked phone that already has the microphone can still talk`() {
        assertEquals(TalkbackAvailability.Ready, availability(locked = true))
    }

    @Test
    fun `a camera with no speaker is unsupported whatever else is true`() {
        assertEquals(
            TalkbackAvailability.Unsupported(TalkbackAvailability.Reason.NO_SPEAKER),
            availability(hasSpeaker = false, microphoneGranted = false, reachable = false),
        )
    }

    @Test
    fun `a console that never issued a key cannot be asked where to send audio`() {
        assertEquals(
            TalkbackAvailability.Unsupported(TalkbackAvailability.Reason.NO_API_KEY),
            availability(hasApiKey = false),
        )
    }

    @Test
    fun `format refusals carry through as their own reasons`() {
        val vorbis = TalkbackFormat.of(TalkbackSession("rtp://h:7004", "vorbis", 24_000, 16))
        val oddRate = TalkbackFormat.of(TalkbackSession("rtp://h:7004", "opus", 44_100, 16))
        val noAddress = TalkbackFormat.of(TalkbackSession("nonsense", "opus", 24_000, 16))

        assertEquals(
            TalkbackAvailability.Unsupported(TalkbackAvailability.Reason.CODEC),
            availability(format = vorbis),
        )
        assertEquals(
            TalkbackAvailability.Unsupported(TalkbackAvailability.Reason.RATE),
            availability(format = oddRate),
        )
        assertEquals(
            TalkbackAvailability.Unsupported(TalkbackAvailability.Reason.ADDRESS),
            availability(format = noAddress),
        )
    }

    /**
     * Nobody is asked for a microphone on behalf of a camera that was never
     * going to make a sound.
     */
    @Test
    fun `a camera that cannot work never prompts for the microphone`() {
        val everythingWrong = availability(
            hasSpeaker = false,
            hasApiKey = false,
            format = null,
            reachable = false,
            microphoneGranted = false,
            locked = true,
        )

        assertEquals(
            TalkbackAvailability.Unsupported(TalkbackAvailability.Reason.NO_SPEAKER),
            everythingWrong,
        )
    }
}

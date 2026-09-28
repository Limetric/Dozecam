package app.dozecam.audio.talkback

import app.dozecam.data.Camera
import app.dozecam.data.ProtectStream
import app.dozecam.protect.ProtectApiException
import app.dozecam.protect.TalkbackSession
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.async
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import kotlinx.coroutines.launch
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProtectTalkbackTest {

    /** Enough frames to prove a press ran away, few enough to fail quickly. */
    private val RUNAWAY = 2_000

    private val console = "192.168.1.1"
    private val opus = TalkbackSession("rtp://192.168.1.12:7004", "opus", 24_000, 16)

    private fun camera(
        protectId: String? = "cam1",
        consoleHost: String? = console,
    ) = Camera(
        id = "local-1",
        name = "Bedroom",
        url = "rtsp://x/y",
        protect = protectId?.let { ProtectStream(cameraId = it, channel = 1, consoleHost = consoleHost) },
    )

    private fun talkback(
        scope: kotlinx.coroutines.test.TestScope,
        speakers: Map<String, Boolean> = mapOf("cam1" to true),
        session: suspend (String) -> TalkbackSession = { opus },
        host: String? = console,
        apiKey: Boolean = true,
        reachable: Boolean = true,
        microphoneGranted: Boolean = true,
        locked: Boolean = false,
    ) = ProtectTalkback(
        scope = scope,
        speakers = { speakers },
        session = session,
        consoleHost = { host },
        hasApiKey = { apiKey },
        reachability = TalkbackReachability { _, _ -> reachable },
        microphoneGranted = { microphoneGranted },
        locked = { locked },
        openMicrophone = { null },
        // The press runs on this rather than Dispatchers.IO, so a test can
        // advance it instead of racing it.
        io = StandardTestDispatcher(scope.testScheduler),
        park = {},
    )

    /**
     * Subscribes before the action and waits for one failure. A SharedFlow
     * delivers nothing to a listener that has not started yet, so the
     * subscription has to happen on this thread rather than when the scheduler
     * gets round to it.
     */
    private fun TestScope.awaitFailure(talkback: Talkback) =
        async(start = CoroutineStart.UNDISPATCHED) { talkback.failures.first() }

    @Test
    fun `everything in place is ready to talk`() = runTest {
        val talkback = talkback(this)

        talkback.watch(camera())
        advanceUntilIdle()

        assertEquals(TalkbackAvailability.Ready, talkback.availability.value)
    }

    /** A camera someone typed a URL for has no console to ask. */
    @Test
    fun `a camera with no protect identity has no console behind it`() = runTest {
        val talkback = talkback(this)

        talkback.watch(camera(protectId = null))
        advanceUntilIdle()

        assertEquals(
            TalkbackAvailability.Unsupported(TalkbackAvailability.Reason.NO_CONSOLE),
            talkback.availability.value,
        )
    }

    /**
     * Re-onboarding to a second console leaves the first one's cameras behind.
     * Their ids mean nothing to the console now signed in, and asking anyway
     * would either fail or, worse, hit a different camera with the same id.
     */
    @Test
    fun `a camera from a console no longer signed in is refused`() = runTest {
        val talkback = talkback(this)

        talkback.watch(camera(consoleHost = "10.0.0.1"))
        advanceUntilIdle()

        assertEquals(
            TalkbackAvailability.Unsupported(TalkbackAvailability.Reason.NO_CONSOLE),
            talkback.availability.value,
        )
    }

    @Test
    fun `a console that issued no key cannot be asked`() = runTest {
        val talkback = talkback(this, apiKey = false)

        talkback.watch(camera())
        advanceUntilIdle()

        assertEquals(
            TalkbackAvailability.Unsupported(TalkbackAvailability.Reason.NO_API_KEY),
            talkback.availability.value,
        )
    }

    @Test
    fun `a camera without a speaker is never asked for a session`() = runTest {
        var asked = false
        val talkback = talkback(
            this,
            speakers = mapOf("cam1" to false),
            session = { asked = true; opus },
        )

        talkback.watch(camera())
        advanceUntilIdle()

        assertEquals(
            TalkbackAvailability.Unsupported(TalkbackAvailability.Reason.NO_SPEAKER),
            talkback.availability.value,
        )
        assertFalse("a speakerless camera should not be asked where to send audio", asked)
    }

    @Test
    fun `a console that refuses the session leaves no address`() = runTest {
        val talkback = talkback(this, session = { throw ProtectApiException("nope", 404) })

        talkback.watch(camera())
        advanceUntilIdle()

        assertEquals(
            TalkbackAvailability.Unsupported(TalkbackAvailability.Reason.ADDRESS),
            talkback.availability.value,
        )
    }

    @Test
    fun `a codec this phone cannot encode is refused before the network is probed`() = runTest {
        var probed = false
        val talkback = ProtectTalkback(
            scope = this,
            speakers = { mapOf("cam1" to true) },
            session = { opus.copy(codec = "vorbis") },
            consoleHost = { console },
            hasApiKey = { true },
            reachability = TalkbackReachability { _, _ -> probed = true; true },
            microphoneGranted = { true },
            locked = { false },
            openMicrophone = { null },
            io = StandardTestDispatcher(testScheduler),
            park = {},
        )

        talkback.watch(camera())
        advanceUntilIdle()

        assertEquals(
            TalkbackAvailability.Unsupported(TalkbackAvailability.Reason.CODEC),
            talkback.availability.value,
        )
        assertFalse("no point probing a camera we could not encode for", probed)
    }

    @Test
    fun `a camera off this network is unreachable`() = runTest {
        val talkback = talkback(this, reachable = false)

        talkback.watch(camera())
        advanceUntilIdle()

        assertEquals(TalkbackAvailability.Unreachable, talkback.availability.value)
    }

    @Test
    fun `a locked phone without the microphone is told to unlock`() = runTest {
        val talkback = talkback(this, microphoneGranted = false, locked = true)

        talkback.watch(camera())
        advanceUntilIdle()

        assertEquals(TalkbackAvailability.NeedsUnlock, talkback.availability.value)
    }

    @Test
    fun `leaving the camera stops asking about it`() = runTest {
        val talkback = talkback(this)

        talkback.watch(camera())
        advanceUntilIdle()
        talkback.watch(null)
        advanceUntilIdle()

        assertEquals(TalkbackAvailability.Resolving, talkback.availability.value)
    }

    /**
     * A phone in a call, or one whose voice-communication source is already
     * taken, refuses the microphone — and AudioRecord throws rather than
     * returning null. On a supervisor scope an uncaught throw here reaches the
     * default handler, so the failure mode is a baby monitor that dies when a
     * call arrives.
     */
    @Test
    fun `a microphone that throws ends the press instead of the app`() = runTest {
        val talkback = ProtectTalkback(
            scope = this,
            speakers = { mapOf("cam1" to true) },
            session = { opus },
            consoleHost = { console },
            hasApiKey = { true },
            reachability = TalkbackReachability { _, _ -> true },
            microphoneGranted = { true },
            locked = { false },
            openMicrophone = { throw IllegalStateException("mic busy") },
            io = StandardTestDispatcher(testScheduler),
            park = {},
        )
        val failure = awaitFailure(talkback)

        talkback.watch(camera())
        advanceUntilIdle()
        talkback.press()
        advanceUntilIdle()

        assertFalse("a failed press must not still claim to be talking", talkback.talking.value)
        assertTrue("the failure should be reported", failure.isCompleted)
    }

    /** A microphone this device simply cannot provide is a failure, not silence. */
    @Test
    fun `a microphone that cannot be opened is reported rather than ignored`() = runTest {
        val talkback = talkback(this)
        val failure = awaitFailure(talkback)

        talkback.watch(camera())
        advanceUntilIdle()
        // The default fake returns null, which used to complete silently.
        talkback.press()
        advanceUntilIdle()

        assertFalse(talkback.talking.value)
        assertTrue("the failure should be reported", failure.isCompleted)
    }

    /**
     * Wi-Fi going away mid-sentence throws on the socket rather than politely
     * ending the stream.
     */
    @Test
    fun `a socket that dies mid-press is reported rather than thrown`() = runTest {
        val talkback = ProtectTalkback(
            scope = this,
            speakers = { mapOf("cam1" to true) },
            session = { opus },
            consoleHost = { console },
            hasApiKey = { true },
            reachability = TalkbackReachability { _, _ -> true },
            microphoneGranted = { true },
            locked = { false },
            openMicrophone = { object : PcmSource {
                override fun read(into: ShortArray) = true
                override fun close() = Unit
            } },
            newEncoder = { object : FrameEncoder {
                override fun encode(pcm: ShortArray, presentationTimeUs: Long) = listOf(byteArrayOf(1))
                override fun finish() = emptyList<ByteArray>()
                override fun close() = Unit
            } },
            newSender = { FrameSink { throw java.io.IOException("network unreachable") } },
            io = StandardTestDispatcher(testScheduler),
            park = {},
        )
        val failure = awaitFailure(talkback)

        talkback.watch(camera())
        advanceUntilIdle()
        talkback.press()
        advanceUntilIdle()

        assertFalse(talkback.talking.value)
        assertTrue("the failure should be reported", failure.isCompleted)
    }

    /**
     * Reachability is a fact about the network the phone is on. A camera cached
     * as unreachable from elsewhere must not stay that way after coming home;
     * before this, only killing the process cleared it.
     */
    @Test
    fun `refreshing forgets where the camera could not be reached`() = runTest {
        var onHomeNetwork = false
        val probes = mutableListOf<String>()
        val talkback = ProtectTalkback(
            scope = this,
            speakers = { mapOf("cam1" to true) },
            session = { opus },
            consoleHost = { console },
            hasApiKey = { true },
            reachability = TalkbackReachability { host, _ -> probes += host; onHomeNetwork },
            microphoneGranted = { true },
            locked = { false },
            openMicrophone = { null },
            io = StandardTestDispatcher(testScheduler),
            park = {},
        )

        talkback.watch(camera())
        advanceUntilIdle()
        assertEquals(TalkbackAvailability.Unreachable, talkback.availability.value)

        onHomeNetwork = true
        talkback.refresh()
        advanceUntilIdle()

        assertEquals(TalkbackAvailability.Ready, talkback.availability.value)
        assertTrue("the camera must be probed again, not read from cache", probes.size > 1)
    }

    /**
     * The reported failure: a network handover during a press calls refresh(),
     * which moves availability to Resolving and takes the control off screen —
     * cancelling the gesture that would have released it. Nothing else ended
     * the press, so the microphone stayed open and the room kept being spoken
     * to with no button on screen to stop it.
     */
    @Test
    fun `resolving again ends a press the control can no longer end itself`() = runTest {
        var reads = 0
        var microphoneClosed = false
        val talkback = ProtectTalkback(
            scope = this,
            speakers = { mapOf("cam1" to true) },
            session = { opus },
            consoleHost = { console },
            hasApiKey = { true },
            reachability = TalkbackReachability { _, _ -> true },
            microphoneGranted = { true },
            locked = { false },
            openMicrophone = {
                object : PcmSource {
                    override fun read(into: ShortArray): Boolean {
                        reads++
                        // Bounded only so this fails rather than hangs when the
                        // press is never ended: a real microphone would go on.
                        return reads < RUNAWAY
                    }
                    override fun close() { microphoneClosed = true }
                }
            },
            newEncoder = {
                object : FrameEncoder {
                    override fun encode(pcm: ShortArray, presentationTimeUs: Long) = emptyList<ByteArray>()
                    override fun finish() = emptyList<ByteArray>()
                    override fun close() = Unit
                }
            },
            newSender = { FrameSink { } },
            io = StandardTestDispatcher(testScheduler),
            park = {},
        )

        talkback.watch(camera())
        advanceUntilIdle()
        // Ends the press from underneath the control, exactly as a network
        // handover does: refresh() moves availability to Resolving, which is
        // what takes the button off screen.
        talkback.press()
        talkback.refresh()
        advanceUntilIdle()

        assertFalse("the press must not still be running", talkback.talking.value)
        assertTrue(
            "the microphone must be closed, not left open on a cancelled press",
            microphoneClosed,
        )
        assertTrue(
            "the press should have stopped at once, not run on; read $reads frames",
            reads < RUNAWAY / 10,
        )
    }

    /**
     * The volume setting has to land between the microphone and the encoder:
     * anywhere later and the camera would receive full-scale audio to play at
     * its own, shared, speaker volume.
     */
    @Test
    fun `a press attenuates what the encoder hears by the set volume`() = runTest {
        val loudest = mutableListOf<Short>()
        var frames = 0
        val talkback = ProtectTalkback(
            scope = this,
            speakers = { mapOf("cam1" to true) },
            session = { opus },
            consoleHost = { console },
            hasApiKey = { true },
            reachability = TalkbackReachability { _, _ -> true },
            microphoneGranted = { true },
            locked = { false },
            openMicrophone = {
                object : PcmSource {
                    // One spoken frame, then the microphone ends the press.
                    override fun read(into: ShortArray): Boolean {
                        if (frames++ > 0) return false
                        into.fill(10_000)
                        return true
                    }
                    override fun close() = Unit
                }
            },
            volume = { 0.5f },
            newEncoder = {
                object : FrameEncoder {
                    override fun encode(pcm: ShortArray, presentationTimeUs: Long): List<ByteArray> {
                        loudest += pcm.maxOrNull() ?: 0
                        return emptyList()
                    }
                    override fun finish() = emptyList<ByteArray>()
                    override fun close() = Unit
                }
            },
            newSender = { FrameSink { } },
            io = StandardTestDispatcher(testScheduler),
            park = {},
        )

        talkback.watch(camera())
        advanceUntilIdle()
        talkback.press()
        advanceUntilIdle()

        val expected = (10_000 * TalkbackGain.amplitude(0.5f)).toInt().toShort()
        assertTrue("the spoken frame should reach the encoder", loudest.any { it > 0 })
        assertEquals(expected, loudest.max())
    }

    /** The slider is read at the press, so moving it reaches the very next one. */
    @Test
    fun `each press picks up the volume as it is then`() = runTest {
        var volume = 1f
        val loudest = mutableListOf<Short>()
        val talkback = ProtectTalkback(
            scope = this,
            speakers = { mapOf("cam1" to true) },
            session = { opus },
            consoleHost = { console },
            hasApiKey = { true },
            reachability = TalkbackReachability { _, _ -> true },
            microphoneGranted = { true },
            locked = { false },
            openMicrophone = {
                object : PcmSource {
                    var frames = 0
                    override fun read(into: ShortArray): Boolean {
                        if (frames++ > 0) return false
                        into.fill(10_000)
                        return true
                    }
                    override fun close() = Unit
                }
            },
            volume = { volume },
            newEncoder = {
                object : FrameEncoder {
                    override fun encode(pcm: ShortArray, presentationTimeUs: Long): List<ByteArray> {
                        loudest += pcm.maxOrNull() ?: 0
                        return emptyList()
                    }
                    override fun finish() = emptyList<ByteArray>()
                    override fun close() = Unit
                }
            },
            newSender = { FrameSink { } },
            io = StandardTestDispatcher(testScheduler),
            park = {},
        )

        talkback.watch(camera())
        advanceUntilIdle()
        talkback.press()
        advanceUntilIdle()
        val fullVolume = loudest.max()

        volume = 0f
        loudest.clear()
        talkback.press()
        advanceUntilIdle()

        assertEquals(10_000.toShort(), fullVolume)
        assertEquals(
            (10_000 * TalkbackGain.amplitude(0f)).toInt().toShort(),
            loudest.max(),
        )
    }

    /** Pressing a control that is not ready must never open a microphone. */
    @Test
    fun `a press on an unreachable camera does nothing`() = runTest {
        var opened = false
        val talkback = ProtectTalkback(
            scope = this,
            speakers = { mapOf("cam1" to true) },
            session = { opus },
            consoleHost = { console },
            hasApiKey = { true },
            reachability = TalkbackReachability { _, _ -> false },
            microphoneGranted = { true },
            locked = { false },
            openMicrophone = { opened = true; null },
            io = StandardTestDispatcher(testScheduler),
            park = {},
        )

        talkback.watch(camera())
        advanceUntilIdle()
        talkback.press()
        advanceUntilIdle()

        assertFalse("an unreachable camera must not open the microphone", opened)
        assertFalse(talkback.talking.value)
    }
}

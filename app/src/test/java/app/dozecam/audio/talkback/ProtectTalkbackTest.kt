package app.dozecam.audio.talkback

import app.dozecam.data.Camera
import app.dozecam.data.ProtectStream
import app.dozecam.protect.ProtectApiException
import app.dozecam.protect.TalkbackSession
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProtectTalkbackTest {

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
        scope: kotlinx.coroutines.CoroutineScope,
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
    )

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
        )

        talkback.watch(camera())
        advanceUntilIdle()
        talkback.press()
        advanceUntilIdle()

        assertFalse("an unreachable camera must not open the microphone", opened)
        assertFalse(talkback.talking.value)
    }
}

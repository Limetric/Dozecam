package app.dozecam.audio.talkback

import app.dozecam.data.Camera
import app.dozecam.protect.TalkbackSession
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What the viewer needs from talk-back — small enough for a screen test to
 * stand in for the whole of it.
 */
interface Talkback {
    /** What the control on screen should say about itself. */
    val availability: StateFlow<TalkbackAvailability>

    /** Whether the phone is speaking right now. Never whether it is heard. */
    val talking: StateFlow<Boolean>

    /**
     * A press that ended in failure rather than in silence.
     *
     * Talking to a camera reaches for a microphone, an encoder and a socket,
     * and any of the three can be refused by a phone doing something else —
     * a call in progress, another app holding the voice-communication source,
     * Wi-Fi going away mid-sentence. None of that is exceptional enough to be
     * worth a crash, and all of it is worth saying out loud.
     */
    val failures: SharedFlow<Unit>

    /** The camera on screen alone, or null when the grid is showing. */
    fun watch(camera: Camera?)

    /**
     * Re-asks the questions whose answers live outside this class — the
     * microphone grant, the keyguard, and whether the camera can be reached
     * from the network this device is on now.
     */
    fun refresh()

    fun press()
    fun release()
}

/**
 * Talk-back over the documented Integration API.
 *
 * Resolution is deliberately front-loaded: by the time a finger arrives, the
 * camera's address, format and reachability are already known, because a button
 * that spends a second thinking is a button somebody presses twice. Nothing is
 * asked of the console until a camera is actually on screen alone, though —
 * talk-back is a single-camera feature and the grid should cost nothing.
 *
 * The two network calls are handed in rather than built here. They need a
 * console address, a pinned certificate and an API key, all of which the
 * activity already knows how to assemble, and injecting them is what lets the
 * ordering below be tested without a console.
 */
class ProtectTalkback(
    private val scope: CoroutineScope,
    /** Speaker flags by Protect camera id, for the signed-in console. */
    private val speakers: suspend () -> Map<String, Boolean>,
    private val session: suspend (cameraId: String) -> TalkbackSession,
    /** Null when no console is signed in, or it issued no API key. */
    private val consoleHost: suspend () -> String?,
    private val hasApiKey: suspend () -> Boolean,
    private val reachability: TalkbackReachability = TalkbackReachability(),
    private val microphoneGranted: () -> Boolean,
    private val locked: () -> Boolean,
    private val openMicrophone: (TalkbackFormat.Speakable) -> PcmSource? = AudioRecordSource::open,
    private val newEncoder: (TalkbackFormat.Speakable) -> FrameEncoder = { OpusEncoder(it.sampleRate) },
    private val newSender: (TalkbackFormat.Speakable) -> FrameSink = ::defaultSender,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : Talkback {

    private val _availability = MutableStateFlow<TalkbackAvailability>(
        TalkbackAvailability.Resolving,
    )
    override val availability: StateFlow<TalkbackAvailability> = _availability.asStateFlow()

    private val _talking = MutableStateFlow(false)
    override val talking: StateFlow<Boolean> = _talking.asStateFlow()

    // One buffered slot: a listener that missed a failure while off screen has
    // nothing useful to say about it by the time it comes back.
    private val _failures = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    override val failures: SharedFlow<Unit> = _failures.asSharedFlow()

    private var watched: Camera? = null
    private var resolving: Job? = null
    private var speaking: Job? = null

    /** What the last resolve settled on, and what a press will use. */
    private var format: TalkbackFormat.Speakable? = null

    @Volatile
    private var held = false

    override fun watch(camera: Camera?) {
        if (camera?.id == watched?.id) return
        watched = camera
        release()
        resolve()
    }

    /**
     * Called when the microphone grant, the lock state or the network may have
     * changed. Reachability is forgotten rather than trusted: an answer from a
     * previous network is worse than no answer, and a camera cached as
     * unreachable from a train would otherwise stay that way until the process
     * died.
     */
    override fun refresh() = resolve(forgetReachability = true)

    private fun resolve(forgetReachability: Boolean = false) {
        resolving?.cancel()
        format = null
        val camera = watched
        if (camera == null) {
            _availability.value = TalkbackAvailability.Resolving
            return
        }
        _availability.value = TalkbackAvailability.Resolving

        resolving = scope.launch {
            if (forgetReachability) reachability.forget()
            _availability.value = resolveFor(camera)
        }
    }

    private suspend fun resolveFor(camera: Camera): TalkbackAvailability {
        val protect = camera.protect
            ?: return unsupported(TalkbackAvailability.Reason.NO_CONSOLE)
        val host = consoleHost()
            ?: return unsupported(TalkbackAvailability.Reason.NO_CONSOLE)
        // A camera left behind by a console that is no longer the signed-in one
        // would have its id negotiated against a console that never issued it.
        if (protect.consoleHost != null && protect.consoleHost != host) {
            return unsupported(TalkbackAvailability.Reason.NO_CONSOLE)
        }
        if (!hasApiKey()) return unsupported(TalkbackAvailability.Reason.NO_API_KEY)

        val hasSpeaker = runCatching { speakers()[protect.cameraId] }.getOrNull() ?: false
        if (!hasSpeaker) return unsupported(TalkbackAvailability.Reason.NO_SPEAKER)

        val described = runCatching { session(protect.cameraId) }.getOrNull()
            ?: return unsupported(TalkbackAvailability.Reason.ADDRESS)

        val resolved = TalkbackFormat.of(described)
        if (resolved is TalkbackFormat.Refused) {
            return TalkbackAvailability.of(
                hasSpeaker = true,
                hasApiKey = true,
                format = resolved,
                reachable = null,
                microphoneGranted = microphoneGranted(),
                locked = locked(),
            )
        }

        val speakable = resolved as TalkbackFormat.Speakable
        val reachable = reachability.isReachable(speakable.host)
        format = speakable.takeIf { reachable }

        return TalkbackAvailability.of(
            hasSpeaker = true,
            hasApiKey = true,
            format = resolved,
            reachable = reachable,
            microphoneGranted = microphoneGranted(),
            locked = locked(),
        )
    }

    private fun unsupported(reason: TalkbackAvailability.Reason) =
        TalkbackAvailability.Unsupported(reason)

    override fun press() {
        val speakable = format ?: return
        if (_availability.value != TalkbackAvailability.Ready) return
        if (speaking?.isActive == true) return

        held = true
        _talking.value = true
        speaking = scope.launch {
            var failed = false
            try {
                withContext(io) {
                    // The microphone, the encoder and the socket are all opened
                    // here rather than kept alive between presses: a baby
                    // monitor that held the microphone open between sentences
                    // would be a different app from the one in the manifest.
                    //
                    // All three can be refused. AudioRecord will not start
                    // during a call or while another app holds the
                    // voice-communication source, MediaCodec can fail to
                    // configure, and a socket to a camera that just went away
                    // throws on the first datagram. None of that is worth
                    // taking the viewer down for, which is what an uncaught
                    // throw in this coroutine would do.
                    val microphone = openMicrophone(speakable)
                        ?: throw IllegalStateException("no usable microphone at ${speakable.sampleRate}Hz")
                    microphone.use { source ->
                        newEncoder(speakable).use { encoder ->
                            val sink = newSender(speakable)
                            try {
                                TalkbackStream(speakable, source, encoder, sink).run { held }
                            } finally {
                                (sink as? AutoCloseable)?.close()
                            }
                        }
                    }
                }
            } catch (cancellation: CancellationException) {
                // Leaving the camera cancels the press; that is an ending, not
                // a failure, and it must reach the caller to stay cancelled.
                throw cancellation
            } catch (failure: Exception) {
                failed = true
            } finally {
                // Whatever happened, the button is no longer held and the
                // control must not be left claiming to be talking.
                held = false
                _talking.value = false
            }
            if (failed) _failures.tryEmit(Unit)
        }
    }

    override fun release() {
        held = false
    }
}

private fun defaultSender(format: TalkbackFormat.Speakable): FrameSink = TalkbackSender(
    host = format.host,
    port = format.port,
    packetiser = RtpPacketiser(
        // A fresh source for every press: sequence numbers restart at zero, and
        // a receiver told two presses share a source would read the second as
        // sixty thousand packets of reordering.
        ssrc = freshSsrc(),
        timestampIncrement = format.rtpTimestampIncrement,
    ),
)

private fun freshSsrc(): Int = java.util.concurrent.ThreadLocalRandom.current().nextInt()

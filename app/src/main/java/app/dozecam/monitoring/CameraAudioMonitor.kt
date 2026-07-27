package app.dozecam.monitoring

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import app.dozecam.audio.PcmRms
import app.dozecam.audio.SoundDetector
import app.dozecam.data.Camera
import app.dozecam.data.DetectorSettings
import app.dozecam.player.ConnectionState
import app.dozecam.player.LivestreamConnection
import app.dozecam.player.PlaybackWatchdog
import app.dozecam.player.PlayerEvent
import app.dozecam.player.StreamSource
import app.dozecam.protect.ProtectLivestreamProvider
import java.nio.ByteBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

/**
 * One camera's share of the monitor: an audio-only session feeding a
 * [SoundDetector], with its own [PlaybackWatchdog] so a camera that drops off
 * the network reconnects on its own without disturbing the others.
 *
 * The video track is disabled outright whichever transport carries it, so only
 * audio is ever decoded — which is why a camera that has to be *watched* over
 * Protect's livestream (AV1, which RTSP cannot depayload) can still be
 * *monitored* over its plain RTSP stream.
 *
 * [transports] is that stream first and anything else that would do after it,
 * because "cannot be decoded" is not a thing a stream announces: it looks
 * exactly like a camera that is quiet. So a transport that has never yielded a
 * single audio buffer after several attempts is abandoned for the next one,
 * rather than reconnected to all night.
 *
 * Main-thread object; [scope] is expected to dispatch there.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class CameraAudioMonitor(
    private val context: Context,
    val camera: Camera,
    /** Ways to listen to this camera, best first; see [MonitorTransports]. */
    private val transports: List<StreamSource>,
    private val livestreamProvider: ProtectLivestreamProvider,
    private val scope: CoroutineScope,
    private val detectorSettings: DetectorSettings,
    private val onLevel: (rms: Float) -> Unit,
    private val onPhase: (SoundDetector.Phase) -> Unit,
    private val onConnection: (ConnectionState) -> Unit,
    private val onTrigger: () -> Unit,
) {
    private val detector = SoundDetector(detectorSettings)
    private var player: ExoPlayer? = null
    private var jobs = mutableListOf<Job>()

    /** Which of [transports] is being listened on, and when to give up on it. */
    private val fallback = TransportFallback(transports.size)

    private var connecting: Job? = null
    private var stream: LivestreamConnection? = null


    private val transport: StreamSource?
        get() = transports.getOrNull(fallback.index)

    private val watchdog = PlaybackWatchdog(scope = scope, onReconnect = { restart() })

    // Audio callbacks land on the playback thread; ship (levelRms, atMs) pairs
    // to the main thread for the detector and UI.
    private val levels = MutableSharedFlow<Pair<Float, Long>>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private val levelSink = object : TeeAudioProcessor.AudioBufferSink {
        override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) = Unit

        override fun handleBuffer(buffer: ByteBuffer) {
            levels.tryEmit(PcmRms.of(buffer) to SystemClock.elapsedRealtime())
        }
    }

    /**
     * When the current transport took over. The queue above is 64 buffers deep
     * and filled from the playback thread, so a buffer decoded on the stream we
     * just left can still be waiting in it — and its timestamp, taken as it was
     * decoded, is what tells the two apart. Main thread only, like the switch
     * that moves it.
     */
    private var transportSinceMs = SystemClock.elapsedRealtime()

    fun start() {
        if (transport == null) return // nothing to listen on; the service filters these out
        jobs += scope.launch {
            levels.collect { (rms, atMs) ->
                // Belongs to a transport we have left. Dropped outright rather
                // than merely discounted: it would vouch for a replacement
                // nothing has tested yet, report it live, push out its stall
                // deadline, and could raise an alert for a stream that is no
                // longer being listened to.
                if (atMs < transportSinceMs) return@collect
                // Proof this transport works, which is what makes abandoning a
                // transport that never gets here safe.
                fallback.onAudioDecoded()
                onLevel(rms)
                watchdog.onPlayerEvent(PlayerEvent.TimeChanged(atMs))
                if (detector.onLevel(rms, atMs)) onTrigger()
                onPhase(detector.phase)
            }
        }
        jobs += scope.launch {
            watchdog.state.collect(onConnection)
        }

        player = buildPlayer().also { exo ->
            exo.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (isPlaying) watchdog.onPlayerEvent(PlayerEvent.Playing)
                }

                override fun onPlayerError(error: PlaybackException) {
                    watchdog.onPlayerEvent(PlayerEvent.Error)
                }
            })
            watchdog.start()
        }
        connect()
    }

    /**
     * Nothing decoded after several restarts means the next transport gets its
     * turn. Decided here, as the restart is made, rather than when the watchdog
     * merely announces one: it abandons the restart if the stream recovers
     * during its backoff, which would leave the player on one transport and
     * this object convinced it was on another.
     *
     * Announced, because a monitor quietly changing how it listens to a nursery
     * is something the logs should be able to explain afterwards.
     */
    private fun considerFallback() {
        val abandoned = transport
        if (!fallback.onRestart()) return
        transportSinceMs = SystemClock.elapsedRealtime()
        Log.i(
            TAG,
            "${camera.name}: no audio over ${label(abandoned)}; " +
                "falling back to ${label(transport)}",
        )
    }

    private fun label(source: StreamSource?): String = when (source) {
        is StreamSource.Rtsp -> "RTSP"
        is StreamSource.Livestream -> "the Protect livestream"
        null -> "nothing"
    }

    fun updateSettings(settings: DetectorSettings) {
        detector.updateSettings(settings)
    }

    fun onNetworkAvailable() = watchdog.onNetworkAvailable()

    fun onNetworkLost() = watchdog.onNetworkLost()

    fun stop() {
        watchdog.stop()
        jobs.forEach { it.cancel() }
        jobs.clear()
        closeConnection()
        player?.release()
        player = null
    }

    private fun restart() {
        considerFallback()
        player?.stop()
        connect()
    }

    /**
     * Opens the current transport. A livestream token is minted per
     * negotiation and cannot be replayed, so every reconnect comes back through
     * here rather than reusing what the last one opened.
     */
    private fun connect() {
        val exo = player ?: return
        closeConnection()
        connecting = scope.launch {
            when (val source = transport) {
                null -> return@launch

                is StreamSource.Rtsp -> exo.setMediaSource(
                    RtspMediaSource.Factory()
                        .setForceUseRtpTcp(true)
                        .createMediaSource(MediaItem.fromUri(source.url)),
                )

                is StreamSource.Livestream -> {
                    val opened = try {
                        LivestreamConnection.open(livestreamProvider, source) {
                            // The pipe only surfaces this once ExoPlayer next
                            // reads; tell the watchdog straight away so a socket
                            // that dies between fragments still reconnects.
                            scope.launch { watchdog.onPlayerEvent(PlayerEvent.Error) }
                        }
                    } catch (e: Exception) {
                        ensureActive() // a cancelled attempt is not a stream failure
                        watchdog.onPlayerEvent(PlayerEvent.Error)
                        return@launch
                    }
                    stream = opened
                    exo.setMediaSource(opened.mediaSource)
                }
            }
            exo.prepare()
            exo.play()
        }
    }

    private fun closeConnection() {
        connecting?.cancel()
        connecting = null
        stream?.close()
        stream = null
    }

    private fun buildPlayer(): ExoPlayer {
        val renderersFactory = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink = DefaultAudioSink.Builder(context)
                .setAudioProcessors(arrayOf<AudioProcessor>(TeeAudioProcessor(levelSink)))
                .build()
        }
        return ExoPlayer.Builder(context, renderersFactory).build().apply {
            trackSelectionParameters = trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                .build()
            volume = 0f // monitor silently; the wake alert surfaces the live view
        }
    }

    private companion object {
        const val TAG = "Dozecam"
    }
}

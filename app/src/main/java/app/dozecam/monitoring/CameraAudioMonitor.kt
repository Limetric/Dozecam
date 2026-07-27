package app.dozecam.monitoring

import android.content.Context
import android.os.SystemClock
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
import app.dozecam.player.PlaybackWatchdog
import app.dozecam.player.PlayerEvent
import java.nio.ByteBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

/**
 * One camera's share of the monitor: an audio-only RTSP session feeding a
 * [SoundDetector], with its own [PlaybackWatchdog] so a camera that drops off
 * the network reconnects on its own without disturbing the others.
 *
 * The video track is disabled outright, which is also why a camera that has to
 * be *watched* over Protect's livestream (AV1, which RTSP cannot depayload)
 * can still be *monitored* here: only its audio track is ever decoded.
 *
 * Main-thread object; [scope] is expected to dispatch there.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class CameraAudioMonitor(
    private val context: Context,
    val camera: Camera,
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

    fun start() {
        jobs += scope.launch {
            levels.collect { (rms, atMs) ->
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
            exo.setMediaSource(mediaSource())
            exo.prepare()
            exo.play()
        }
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
        player?.release()
        player = null
    }

    private fun restart() {
        val exo = player ?: return
        exo.stop()
        exo.setMediaSource(mediaSource())
        exo.prepare()
        exo.play()
    }

    private fun mediaSource() = RtspMediaSource.Factory()
        .setForceUseRtpTcp(true)
        .createMediaSource(MediaItem.fromUri(camera.url))

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
}

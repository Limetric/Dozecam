package app.dozecam.player

import android.content.Context
import android.view.SurfaceView
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.DecoderCounters
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.mp4.FragmentedMp4Extractor
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import app.dozecam.protect.ProtectLivestreamProvider
import app.dozecam.protect.ProtectLivestreamSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Plays a Protect camera over the console's livestream WebSocket: fMP4 in,
 * ExoPlayer out. Unlike RTSP this carries whatever the camera encodes, which
 * is the only way an AV1 camera reaches the screen — Android has no AV1 RTP
 * depayloader, in libVLC, Media3, or anywhere else.
 *
 * Must be constructed and driven from the main thread, which [scope] is
 * expected to dispatch on; only the WebSocket's byte handoff crosses threads.
 */
class LivestreamVideoPlayerController(
    context: Context,
    private val scope: CoroutineScope,
    private val provider: ProtectLivestreamProvider,
) : VideoPlayerController {

    private val player = ExoPlayer.Builder(context).build()
    private val surfaceView = SurfaceView(context)

    /**
     * Holds the surface at the video's own shape. ExoPlayer scales whatever it
     * decodes to fill its surface, so without this the picture stretches to the
     * tile; the ratio arrives with the first decoded frame.
     */
    private val videoFrame = AspectRatioLayout(context).apply {
        addView(
            surfaceView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    private var socket: ProtectLivestreamSocket? = null
    private var connection: Job? = null
    private var frameWatch: Job? = null
    private var renderedFrames = 0

    /** Identity of the decoder the count belongs to; a swap means rebase. */
    private var counters: DecoderCounters? = null

    override var listener: ((PlayerEvent) -> Unit)? = null

    init {
        player.addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() {
                listener?.invoke(PlayerEvent.Playing)
            }

            override fun onPlayerError(error: PlaybackException) {
                listener?.invoke(PlayerEvent.Error)
            }

            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_BUFFERING -> listener?.invoke(PlayerEvent.Buffering)
                    Player.STATE_ENDED -> listener?.invoke(PlayerEvent.Stopped)
                    else -> Unit
                }
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width == 0 || videoSize.height == 0) return
                // pixelWidthHeightRatio is 1 for every camera encode we see, but
                // honouring it costs nothing and is what makes anamorphic sources
                // come out square rather than subtly wrong.
                videoFrame.aspectRatio =
                    videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
            }
        })
    }

    override fun attach(container: ViewGroup) {
        container.addView(
            videoFrame,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        player.setVideoSurfaceView(surfaceView)
    }

    override fun detach() {
        player.clearVideoSurface()
        (videoFrame.parent as? ViewGroup)?.removeView(videoFrame)
    }

    override fun setMuted(muted: Boolean) {
        player.volume = if (muted) 0f else 1f
    }

    override fun play(source: StreamSource) {
        val livestream = source as? StreamSource.Livestream ?: return
        stop()
        connection = scope.launch {
            val pipe = LivestreamPipe()
            val negotiated = try {
                provider.connect(livestream.cameraId, livestream.channel)
            } catch (e: Exception) {
                ensureActive() // a cancelled attempt is not a stream failure
                listener?.invoke(PlayerEvent.Error)
                return@launch
            }

            socket = ProtectLivestreamSocket(
                httpClient = negotiated.client,
                onBytes = pipe::offer,
                onFailure = { cause ->
                    pipe.fail(cause)
                    // The pipe only surfaces this once ExoPlayer next reads;
                    // tell the watchdog straight away so a socket that dies
                    // while the player is idle still triggers a reconnect.
                    scope.launch { listener?.invoke(PlayerEvent.Error) }
                },
            ).also { it.open(negotiated.url) }

            player.setMediaSource(
                ProgressiveMediaSource.Factory(
                    LivestreamDataSource.Factory(pipe),
                    ExtractorsFactory {
                        arrayOf(FragmentedMp4Extractor(DefaultSubtitleParserFactory()))
                    },
                ).createMediaSource(MediaItem.fromUri(LIVESTREAM_URI)),
            )
            player.prepare()
            player.play()
            watchFrames()
        }
    }

    override fun stop() {
        connection?.cancel()
        connection = null
        frameWatch?.cancel()
        frameWatch = null
        socket?.close()
        socket = null
        player.stop()
        player.clearMediaItems()
        renderedFrames = 0
        counters = null
    }

    override fun release() {
        stop()
        player.release()
    }

    /**
     * Reports liveness from frames the decoder actually rendered, not from the
     * playback clock. The clock advances on audio alone, which is exactly how
     * a video-less stream came to display a confident "LIVE" over a black
     * screen; a frame counter cannot tell that lie.
     */
    private fun watchFrames() {
        frameWatch = scope.launch {
            while (isActive) {
                delay(FRAME_POLL_MS)
                val current = player.videoDecoderCounters
                val rendered = current?.renderedOutputBufferCount ?: 0
                when {
                    // A reconnect builds a fresh decoder whose count restarts,
                    // and ExoPlayer swaps the counters on its own thread well
                    // after stop() returns. Comparing across that boundary
                    // either reads the old session's frames as proof this one
                    // is rendering, or — if the old count was higher — pins the
                    // baseline above anything the new decoder can reach, so no
                    // frame ever counts and the stream reconnects forever.
                    current !== counters || rendered < renderedFrames -> {
                        counters = current
                        renderedFrames = rendered
                    }

                    rendered > renderedFrames -> {
                        renderedFrames = rendered
                        listener?.invoke(PlayerEvent.TimeChanged(player.currentPosition))
                    }
                }
            }
        }
    }

    private companion object {
        /** The pipe is the real source; ExoPlayer only needs a stable identity. */
        const val LIVESTREAM_URI = "dozecam://livestream"
        const val FRAME_POLL_MS = 500L
    }
}

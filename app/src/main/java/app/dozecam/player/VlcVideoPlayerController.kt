package app.dozecam.player

import android.net.Uri
import android.view.ViewGroup
import android.widget.FrameLayout
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

/**
 * libVLC-backed player tuned for low-latency LAN RTSP.
 * RTSP-over-TCP avoids UDP packet-reorder artifacts on busy Wi-Fi.
 *
 * One instance per camera on screen; they share [runtime]'s LibVLC. Aspect
 * ratio needs no handling here: [VLCVideoLayout] recomputes a best-fit surface
 * on every layout pass, which letterboxes inside whatever box the tile gives
 * it rather than stretching to fill it.
 */
class VlcVideoPlayerController(runtime: VlcRuntime) : VideoPlayerController {

    private val libVlc = runtime.libVlc
    private val mediaPlayer = MediaPlayer(libVlc)
    private var videoLayout: VLCVideoLayout? = null

    override var listener: ((PlayerEvent) -> Unit)? = null

    init {
        // libVLC already defaults to this, but the picture never stretching is a
        // requirement rather than a happy default — state it here so a library
        // change cannot quietly squeeze the nursery into a portrait tile.
        mediaPlayer.videoScale = MediaPlayer.ScaleType.SURFACE_BEST_FIT
        mediaPlayer.setEventListener { event ->
            val mapped = when (event.type) {
                MediaPlayer.Event.Playing -> PlayerEvent.Playing
                MediaPlayer.Event.Buffering -> PlayerEvent.Buffering
                MediaPlayer.Event.Stopped -> PlayerEvent.Stopped
                MediaPlayer.Event.EncounteredError -> PlayerEvent.Error
                MediaPlayer.Event.TimeChanged -> PlayerEvent.TimeChanged(event.timeChanged)
                else -> null
            }
            mapped?.let { listener?.invoke(it) }
        }
    }

    override fun attach(container: ViewGroup) {
        val layout = VLCVideoLayout(container.context).also { videoLayout = it }
        container.addView(
            layout,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        mediaPlayer.attachViews(layout, null, false, false)
    }

    override fun detach() {
        mediaPlayer.detachViews()
        videoLayout?.let { (it.parent as? ViewGroup)?.removeView(it) }
        videoLayout = null
    }

    /**
     * Only [StreamSource.Rtsp] reaches here: a livestream camera is routed to
     * the Media3 player, which is the transport that can carry AV1.
     */
    override fun play(source: StreamSource) {
        val url = (source as? StreamSource.Rtsp)?.url ?: return
        val media = Media(libVlc, Uri.parse(url)).apply {
            setHWDecoderEnabled(true, false)
        }
        mediaPlayer.media = media
        media.release()
        mediaPlayer.play()
    }

    /**
     * Applied to the media player rather than the media, so it survives the
     * next [play] — a reconnect must not bring a muted tile back audible.
     */
    override fun setMuted(muted: Boolean) {
        mediaPlayer.volume = if (muted) 0 else FULL_VOLUME
    }

    override fun stop() {
        mediaPlayer.stop()
    }

    /** Releases this player only; the shared LibVLC outlives it. */
    override fun release() {
        mediaPlayer.release()
    }

    private companion object {
        /** libVLC's scale is 0..100, unlike Media3's 0..1. */
        const val FULL_VOLUME = 100
    }
}

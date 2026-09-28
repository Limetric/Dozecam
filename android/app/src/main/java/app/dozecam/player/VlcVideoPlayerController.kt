package app.dozecam.player

import android.net.Uri
import android.view.ViewGroup
import android.widget.FrameLayout
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
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
    private var videoEnabled = true

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
                // The earliest moment the decoded picture's dimensions exist:
                // a video output has just come up for the selected track.
                MediaPlayer.Event.Vout -> videoAspect()
                else -> null
            }
            mapped?.let { listener?.invoke(it) }
        }
    }

    /**
     * The playing track's shape, or null while there is none — a Vout event
     * can fire for a session whose track info has already been torn down.
     */
    private fun videoAspect(): PlayerEvent.VideoAspect? {
        val track = mediaPlayer.currentVideoTrack ?: return null
        if (track.width <= 0 || track.height <= 0) return null
        // Sample aspect ratio corrects anamorphic encodes; unset means square.
        val sar = if (track.sarNum > 0 && track.sarDen > 0) {
            track.sarNum.toFloat() / track.sarDen
        } else {
            1f
        }
        val encoded = track.width * sar / track.height
        // Orientation metadata can stand the picture on its side; VLC rotates
        // what it draws, so the shape reported must be the displayed one, not
        // the encode's.
        val transposed = when (track.orientation) {
            IMedia.VideoTrack.Orientation.LeftTop,
            IMedia.VideoTrack.Orientation.LeftBottom,
            IMedia.VideoTrack.Orientation.RightTop,
            IMedia.VideoTrack.Orientation.RightBottom,
            -> true

            else -> false
        }
        return PlayerEvent.VideoAspect(if (transposed) 1f / encoded else encoded)
    }

    override fun attach(container: ViewGroup) {
        // A camera moving from the grid to a screen of its own is attached to
        // its new home and detached from the old one in whichever order Compose
        // applies the two, so attaching has to be able to follow an attach.
        if (videoLayout != null) detach()
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
            // Carried on the media as well as toggled on the player below,
            // because a reconnect builds a new media and libVLC selects its
            // tracks afresh — a camera nobody is watching would otherwise come
            // back from a stall with its decoder running again.
            if (!videoEnabled) addOption(NO_VIDEO)
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

    /**
     * Deselects the video track on the running session, leaving the RTSP
     * connection and its audio untouched. [play] carries the same choice onto
     * any media built after this one.
     */
    override fun setVideoEnabled(enabled: Boolean) {
        if (videoEnabled == enabled) return
        videoEnabled = enabled
        mediaPlayer.setVideoTrackEnabled(enabled)
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

        /** Keeps a new media's video track unselected from the outset. */
        const val NO_VIDEO = ":no-video"
    }
}

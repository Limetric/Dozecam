package app.dozecam.player

import android.content.Context
import android.net.Uri
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

/**
 * libVLC-backed player tuned for low-latency LAN RTSP.
 * RTSP-over-TCP avoids UDP packet-reorder artifacts on busy Wi-Fi.
 */
class VlcVideoPlayerController(context: Context) : VideoPlayerController {

    private val libVlc = LibVLC(
        context.applicationContext,
        arrayListOf(
            "--network-caching=$NETWORK_CACHING_MS",
            "--rtsp-tcp",
            "--drop-late-frames",
            "--skip-frames",
        ),
    )
    private val mediaPlayer = MediaPlayer(libVlc)

    override var listener: ((PlayerEvent) -> Unit)? = null

    init {
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

    override fun attach(layout: VLCVideoLayout) {
        mediaPlayer.attachViews(layout, null, false, false)
    }

    override fun detach() {
        mediaPlayer.detachViews()
    }

    override fun play(url: String) {
        val media = Media(libVlc, Uri.parse(url)).apply {
            setHWDecoderEnabled(true, false)
        }
        mediaPlayer.media = media
        media.release()
        mediaPlayer.play()
    }

    override fun stop() {
        mediaPlayer.stop()
    }

    override fun release() {
        mediaPlayer.release()
        libVlc.release()
    }

    private companion object {
        const val NETWORK_CACHING_MS = 150
    }
}

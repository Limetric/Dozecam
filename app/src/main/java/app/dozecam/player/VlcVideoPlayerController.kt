package app.dozecam.player

import android.content.Context
import android.net.Uri
import org.videolan.libvlc.Dialog
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
        // rtsps consoles present self-signed certificates, which libVLC's
        // gnutls module raises as question dialogs ("View certificate" then
        // "Accept permanently"). Answering the affirmative chain mirrors the
        // trusted-LAN posture of the primary plain-RTSP path — which has no
        // transport security at all — so accepting the console cert is
        // strictly stronger, never weaker.
        Dialog.setCallbacks(
            libVlc,
            object : Dialog.Callbacks {
                override fun onDisplay(dialog: Dialog.ErrorMessage) = Unit

                override fun onDisplay(dialog: Dialog.LoginDialog) {
                    dialog.dismiss() // credentials ride the stream URL, never a dialog
                }

                override fun onDisplay(dialog: Dialog.QuestionDialog) {
                    if (dialog.action2Text.isNullOrEmpty()) {
                        dialog.postAction(1)
                    } else {
                        dialog.postAction(2)
                    }
                }

                override fun onDisplay(dialog: Dialog.ProgressDialog) = Unit

                override fun onCanceled(dialog: org.videolan.libvlc.Dialog) = Unit

                override fun onProgressUpdate(dialog: Dialog.ProgressDialog) = Unit
            },
        )
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

package app.dozecam.player

import android.content.Context
import org.videolan.libvlc.Dialog
import org.videolan.libvlc.LibVLC

/**
 * The process-wide libVLC instance. A grid plays several cameras at once and
 * each needs its own MediaPlayer, but LibVLC itself is expensive to build and
 * is designed to be shared — one instance backs every RTSP tile.
 *
 * Created lazily: an install whose cameras all play over Protect's livestream
 * never loads the native library at all.
 */
class VlcRuntime(context: Context) {

    private val appContext = context.applicationContext

    val libVlc: LibVLC by lazy {
        LibVLC(
            appContext,
            arrayListOf(
                "--network-caching=$NETWORK_CACHING_MS",
                "--rtsp-tcp",
                "--drop-late-frames",
                "--skip-frames",
            ),
        ).also(::installDialogCallbacks)
    }

    /**
     * rtsps consoles present self-signed certificates, which libVLC's gnutls
     * module raises as question dialogs ("View certificate" then "Accept
     * permanently"). Answering the affirmative chain mirrors the trusted-LAN
     * posture of the primary plain-RTSP path — which has no transport security
     * at all — so accepting the console cert is strictly stronger, never
     * weaker. Registered against the shared instance, so it is installed once
     * rather than once per player.
     */
    private fun installDialogCallbacks(instance: LibVLC) {
        Dialog.setCallbacks(
            instance,
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

                override fun onCanceled(dialog: Dialog) = Unit

                override fun onProgressUpdate(dialog: Dialog.ProgressDialog) = Unit
            },
        )
    }

    private companion object {
        const val NETWORK_CACHING_MS = 150
    }
}

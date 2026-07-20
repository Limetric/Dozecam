package app.dozecam.ui.monitor

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import app.dozecam.player.VideoPlayerController
import app.dozecam.player.VlcVideoPlayerController
import org.videolan.libvlc.util.VLCVideoLayout

class MonitorActivity : ComponentActivity() {

    private var player: VideoPlayerController? = null
    private lateinit var videoLayout: VLCVideoLayout
    private lateinit var streamUrl: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(EXTRA_STREAM_URL)
        if (url.isNullOrBlank()) {
            finish()
            return
        }
        streamUrl = url

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        videoLayout = VLCVideoLayout(this)
        setContentView(
            FrameLayout(this).apply {
                addView(
                    videoLayout,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    ),
                )
            },
        )

        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onStart() {
        super.onStart()
        if (isFinishing) return
        val controller = player ?: VlcVideoPlayerController(applicationContext).also { player = it }
        controller.attach(videoLayout)
        controller.play(streamUrl)
    }

    override fun onStop() {
        super.onStop()
        player?.stop()
        player?.detach()
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
    }

    companion object {
        private const val EXTRA_STREAM_URL = "stream_url"

        fun intent(context: Context, streamUrl: String): Intent =
            Intent(context, MonitorActivity::class.java).putExtra(EXTRA_STREAM_URL, streamUrl)
    }
}

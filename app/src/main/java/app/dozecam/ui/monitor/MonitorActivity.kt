package app.dozecam.ui.monitor

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.dozecam.appContainer
import app.dozecam.data.AppSettings
import app.dozecam.data.OrientationLock
import app.dozecam.network.NetworkMonitor
import app.dozecam.player.PlaybackWatchdog
import app.dozecam.player.VideoPlayerController
import app.dozecam.player.VlcVideoPlayerController
import app.dozecam.ui.theme.DozecamTheme
import kotlinx.coroutines.launch
import org.videolan.libvlc.util.VLCVideoLayout

class MonitorActivity : ComponentActivity() {

    private var player: VideoPlayerController? = null
    private lateinit var videoLayout: VLCVideoLayout
    private lateinit var streamUrl: String
    private lateinit var watchdog: PlaybackWatchdog

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(EXTRA_STREAM_URL)
        if (url.isNullOrBlank()) {
            finish()
            return
        }
        streamUrl = url

        // Wake path: the sound alert's full-screen intent must be able to turn
        // the display on and appear over the lock screen.
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        watchdog = PlaybackWatchdog(
            scope = lifecycleScope,
            onReconnect = { restartStream() },
        )

        videoLayout = VLCVideoLayout(this)
        val overlay = ComposeView(this).apply {
            setContent {
                val settings by appContainer.appSettings.settings
                    .collectAsStateWithLifecycle(initialValue = AppSettings())
                DozecamTheme(nightTheme = settings.nightTheme) {
                    val state by watchdog.state.collectAsStateWithLifecycle()
                    val lastFrameAt by watchdog.lastFrameAtMs.collectAsStateWithLifecycle()
                    StatusOverlay(state = state, lastFrameAtMs = lastFrameAt)
                }
            }
        }
        setContentView(
            FrameLayout(this).apply {
                addView(videoLayout, MATCH_PARENT_PARAMS)
                addView(overlay, MATCH_PARENT_PARAMS)
            },
        )

        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }

        val networkMonitor = NetworkMonitor(applicationContext)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                networkMonitor.isOnline.collect { online ->
                    if (online) watchdog.onNetworkAvailable() else watchdog.onNetworkLost()
                }
            }
        }

        lifecycleScope.launch {
            appContainer.appSettings.settings.collect { settings ->
                requestedOrientation = when (settings.orientationLock) {
                    OrientationLock.AUTO -> ActivityInfo.SCREEN_ORIENTATION_SENSOR
                    OrientationLock.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                    OrientationLock.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // singleTask: a wake alert for another camera reuses this activity.
        if (!::streamUrl.isInitialized) return // onCreate bailed; finishing
        val url = intent.getStringExtra(EXTRA_STREAM_URL)
        if (url.isNullOrBlank() || url == streamUrl) return
        setIntent(intent)
        streamUrl = url
        player?.let { controller ->
            watchdog.stop()
            watchdog.start()
            controller.stop()
            controller.play(streamUrl)
        }
    }

    override fun onStart() {
        super.onStart()
        if (isFinishing) return
        val controller = player ?: VlcVideoPlayerController(applicationContext).also { player = it }
        controller.listener = watchdog::onPlayerEvent
        controller.attach(videoLayout)
        watchdog.start()
        controller.play(streamUrl)
    }

    override fun onStop() {
        super.onStop()
        val controller = player ?: return
        watchdog.stop()
        controller.listener = null
        controller.stop()
        controller.detach()
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
    }

    private fun restartStream() {
        val controller = player ?: return
        controller.stop()
        controller.play(streamUrl)
    }

    companion object {
        private const val EXTRA_STREAM_URL = "stream_url"

        private val MATCH_PARENT_PARAMS
            get() = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )

        fun intent(context: Context, streamUrl: String): Intent =
            Intent(context, MonitorActivity::class.java).putExtra(EXTRA_STREAM_URL, streamUrl)
    }
}

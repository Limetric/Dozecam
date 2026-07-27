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
import app.dozecam.player.LivestreamVideoPlayerController
import app.dozecam.player.PlaybackWatchdog
import app.dozecam.player.StreamSource
import app.dozecam.player.VideoPlayerController
import app.dozecam.player.VlcVideoPlayerController
import app.dozecam.ui.theme.DozecamTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MonitorActivity : ComponentActivity() {

    private lateinit var videoContainer: FrameLayout
    private lateinit var watchdog: PlaybackWatchdog

    private val streamUrl = MutableStateFlow("")

    /** Latest known connectivity, re-delivered to each new playback session. */
    private var networkOnline = true

    // Cached per transport rather than per camera: building either player is
    // expensive, and switching cameras only ever needs one of the two.
    private var vlcPlayer: VlcVideoPlayerController? = null
    private var livestreamPlayer: LivestreamVideoPlayerController? = null
    private var active: VideoPlayerController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(EXTRA_STREAM_URL)
        if (url.isNullOrBlank()) {
            finish()
            return
        }
        streamUrl.value = url

        // Wake path: the sound alert's full-screen intent must be able to turn
        // the display on and appear over the lock screen.
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        watchdog = PlaybackWatchdog(
            scope = lifecycleScope,
            onReconnect = { active?.let { it.play(currentSource()) } },
        )

        videoContainer = FrameLayout(this)
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
                addView(videoContainer, MATCH_PARENT_PARAMS)
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
                    networkOnline = online
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

        // One playback session per (foreground window × camera). collectLatest
        // tears the previous one down before starting the next, so a wake alert
        // for another camera reuses this activity without leaking a player.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                streamUrl.collectLatest { current ->
                    val source = sourceFor(current)
                    val controller = controllerFor(source).also { active = it }
                    controller.listener = watchdog::onPlayerEvent
                    controller.attach(videoContainer)
                    watchdog.start()
                    // start() discards whatever was queued while stopped, and
                    // assumes the network is up. Coming to the foreground while
                    // already offline, the collector's NetworkDown lands in that
                    // window — leaving the monitor retrying as "reconnecting"
                    // forever, since NetworkMonitor only speaks up on a change.
                    if (networkOnline) watchdog.onNetworkAvailable() else watchdog.onNetworkLost()
                    controller.play(source)
                    try {
                        awaitCancellation()
                    } finally {
                        watchdog.stop()
                        controller.listener = null
                        controller.stop()
                        controller.detach()
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // singleTask: a wake alert for another camera reuses this activity.
        val url = intent.getStringExtra(EXTRA_STREAM_URL)
        if (url.isNullOrBlank() || url == streamUrl.value) return
        setIntent(intent)
        streamUrl.value = url
    }

    override fun onDestroy() {
        super.onDestroy()
        vlcPlayer?.release()
        vlcPlayer = null
        livestreamPlayer?.release()
        livestreamPlayer = null
        active = null
    }

    /**
     * A camera onboarded through Protect plays over the console's livestream,
     * the only transport that carries an AV1 encode. A URL the user typed has
     * no console behind it, so it stays on RTSP.
     */
    private suspend fun sourceFor(url: String): StreamSource {
        val camera = appContainer.cameras.cameras.first().firstOrNull { it.url == url }
            ?: return StreamSource.Rtsp(url)
        val console = withContext(Dispatchers.IO) {
            appContainer.protectCredentials.load()?.host
        }
        return StreamSource.of(camera, console)
    }

    /**
     * The transport chosen for the running session. A reconnect must reuse it:
     * the camera cannot change without the session being torn down first, and
     * re-deriving it would mean another store read on the reconnect path.
     */
    private fun currentSource(): StreamSource =
        resolvedSource ?: StreamSource.Rtsp(streamUrl.value)

    private var resolvedSource: StreamSource? = null

    private fun controllerFor(source: StreamSource): VideoPlayerController {
        resolvedSource = source
        return when (source) {
            is StreamSource.Livestream -> livestreamPlayer ?: LivestreamVideoPlayerController(
                context = applicationContext,
                scope = lifecycleScope,
                provider = appContainer.protectLivestream,
            ).also { livestreamPlayer = it }

            is StreamSource.Rtsp -> vlcPlayer
                ?: VlcVideoPlayerController(applicationContext).also { vlcPlayer = it }
        }
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

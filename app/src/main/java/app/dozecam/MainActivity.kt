package app.dozecam

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.dozecam.data.AppSettings
import app.dozecam.data.OrientationLock
import app.dozecam.monitoring.MonitoringStarter
import app.dozecam.monitoring.shouldArmMonitoring
import app.dozecam.network.NetworkMonitor
import app.dozecam.permissions.LocalNetworkPermission
import app.dozecam.player.LivestreamVideoPlayerController
import app.dozecam.player.StreamSource
import app.dozecam.player.VideoPlayerController
import app.dozecam.player.VlcVideoPlayerController
import app.dozecam.ui.monitor.MonitorScreen
import app.dozecam.ui.monitor.MonitorViewModel
import app.dozecam.ui.onboarding.OnboardingActivity
import app.dozecam.ui.settings.SettingsActivity
import app.dozecam.ui.theme.DozecamTheme
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * The viewer, and the only screen video ever plays on. It doubles as the wake
 * target: the sound alert's full-screen intent brings it up over the lock
 * screen focused on whichever camera got loud.
 *
 * Everything about *configuring* a camera lives in [SettingsActivity]; this
 * activity shows cameras and arms the monitor, nothing else.
 */
class MainActivity : ComponentActivity() {

    private val networkOnline = MutableStateFlow(true)

    /** Camera to open fullscreen, delivered by a wake alert. */
    private val alertCameraId = MutableStateFlow<String?>(null)

    private val monitoringStarter = MonitoringStarter(this)

    // Nothing in the app can reach the LAN without this, so ask up front rather
    // than letting the first console or stream connection time out.
    private val localNetworkPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* Denial is surfaced where the connection fails, not here. */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null && !LocalNetworkPermission.isGranted(this)) {
            localNetworkPermission.launch(LocalNetworkPermission.name)
        }
        applyAlertIntent(intent)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        enableEdgeToEdge()

        val networkMonitor = NetworkMonitor(applicationContext)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                networkMonitor.isOnline.collect { networkOnline.value = it }
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

        // Always armed: coming to the front with cameras switched on means
        // monitoring should be running, unless the user deliberately stopped it
        // during this process's lifetime.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) { autoArm() }
        }

        setContent {
            val container = appContainer
            val appSettings by container.appSettings.settings
                .collectAsStateWithLifecycle(initialValue = AppSettings())
            DozecamTheme(nightTheme = appSettings.nightTheme) {
                val viewModel: MonitorViewModel = viewModel(
                    factory = MonitorViewModel.factory(
                        container.cameras,
                        container.protectCredentials,
                    ),
                )
                val cameras by viewModel.cameras.collectAsStateWithLifecycle()
                val sources by viewModel.sources.collectAsStateWithLifecycle()
                val unmonitorable by viewModel.unmonitorable.collectAsStateWithLifecycle()
                val disabledOnly by viewModel.hasDisabledOnly.collectAsStateWithLifecycle()
                val online by networkOnline.collectAsStateWithLifecycle()
                val alertCamera by alertCameraId.collectAsStateWithLifecycle()

                // Coming back to the front may mean a different console was
                // signed in while we were away.
                LaunchedEffect(viewModel) {
                    lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                        viewModel.refreshSources()
                    }
                }

                // Watching a camera should hold the display; an empty viewer
                // pointing at console setup has no business doing so.
                LaunchedEffect(cameras.isEmpty()) {
                    if (cameras.isEmpty()) {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }

                MonitorScreen(
                    cameras = cameras,
                    sources = sources,
                    controllerFactory = ::controllerFor,
                    networkOnline = online,
                    unmonitorable = unmonitorable,
                    hasDisabledOnly = disabledOnly,
                    onOpenSettings = { startActivity(SettingsActivity.intent(this)) },
                    onOpenOnboarding = { startActivity(OnboardingActivity.intent(this)) },
                    alertCameraId = alertCamera,
                    onAlertConsumed = { alertCameraId.value = null },
                    onFullscreenChange = ::applyImmersiveMode,
                    onAlertDismissed = ::revokeLockScreenVisibility,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // singleTask: a wake alert reuses this activity rather than stacking a
        // second copy on top of the running viewer.
        setIntent(intent)
        applyAlertIntent(intent)
    }

    /**
     * Opens the camera a wake alert names, and — only then — allows this
     * activity to turn the display on over the lock screen.
     *
     * The token is what makes that safe. This activity is exported, so without
     * it any app could launch it and put the live nursery on the lock screen of
     * a phone it has no business waking; declaring the same thing statically in
     * the manifest would hand out exactly that. The value never leaves the
     * process except inside our own PendingIntent.
     */
    private fun applyAlertIntent(intent: Intent) {
        val cameraId = intent.getStringExtra(EXTRA_ALERT_CAMERA_ID) ?: return
        // Reassigned every time rather than merely granted: singleTask means one
        // long-lived instance, so a privilege left switched on by an earlier
        // alert would let the next forged launch ride in on it. Burning the
        // token as it is spent stops the same intent being replayed — by a
        // second tap on the notification, or by the activity being recreated
        // with its original launch intent.
        val fromOurAlert = intent.getStringExtra(EXTRA_ALERT_TOKEN) == alertToken
        if (fromOurAlert) alertToken = newAlertToken()
        setShowWhenLocked(fromOurAlert)
        setTurnScreenOn(fromOurAlert)

        // Deliberately outside that check: showing a camera to someone already
        // past the lock screen is not the risk, so a second tap on the alert
        // still opens the right camera even though it can no longer wake.
        alertCameraId.value = cameraId
    }

    /**
     * The wake privilege lasts only as long as the alert that earned it. Going
     * away — including the screen being turned off again — ends that, so the
     * viewer cannot reappear over the keyguard the next time the phone is
     * picked up.
     */
    override fun onPause() {
        super.onPause()
        revokeLockScreenVisibility()
    }

    /**
     * Hands back the right to sit over the keyguard. Called the moment the
     * alerted camera stops being the only thing on screen: the alert bought a
     * look at one room, not at the whole house.
     */
    private fun revokeLockScreenVisibility() {
        setShowWhenLocked(false)
        setTurnScreenOn(false)
    }

    /**
     * System bars stay out of the way only while a single camera fills the
     * screen; the grid and pager need their chrome reachable.
     */
    private fun applyImmersiveMode(immersive: Boolean) {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (immersive) {
                hide(WindowInsetsCompat.Type.systemBars())
            } else {
                show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    /**
     * Starts monitoring when there is something to monitor. Silent about the
     * ungranted-notification case: a denial must not block detection, only the
     * screen-waking part of the alert.
     */
    private suspend fun autoArm() {
        if (appContainer.shouldArmMonitoring(this)) monitoringStarter.startWithAlertPermissions()
    }




    /**
     * A camera onboarded through Protect plays over the console's livestream,
     * the only transport that carries an AV1 encode. A URL the user typed has
     * no console behind it, so it stays on RTSP. One controller per tile: they
     * share the process-wide libVLC but never a decoder.
     */
    private fun controllerFor(source: StreamSource): VideoPlayerController = when (source) {
        is StreamSource.Livestream -> LivestreamVideoPlayerController(
            context = applicationContext,
            scope = lifecycleScope,
            provider = appContainer.protectLivestream,
        )

        is StreamSource.Rtsp -> VlcVideoPlayerController(appContainer.vlcRuntime)
    }

    companion object {
        private const val EXTRA_ALERT_CAMERA_ID = "alert_camera_id"
        private const val EXTRA_ALERT_TOKEN = "alert_token"

        /**
         * Proves an alert intent came from this process, once. Never persisted,
         * and replaced as soon as it is spent: another app cannot guess it, and
         * a stale copy — from a previous process, or a second tap on the same
         * notification — simply declines to wake the screen. The alert still
         * arrives as an ordinary notification either way.
         */
        @Volatile
        private var alertToken: String = newAlertToken()

        private fun newAlertToken(): String = UUID.randomUUID().toString()

        /** Full-screen wake target for [app.dozecam.monitoring.MonitoringNotifications]. */
        fun alertIntent(context: Context, cameraId: String): Intent =
            Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_ALERT_CAMERA_ID, cameraId)
                .putExtra(EXTRA_ALERT_TOKEN, alertToken)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

package app.dozecam

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.media.AudioManager
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
import app.dozecam.audio.ViewerAudioFocus
import app.dozecam.data.AppSettings
import app.dozecam.data.OrientationLock
import app.dozecam.monitoring.MonitoringService
import app.dozecam.monitoring.MonitoringStarter
import app.dozecam.monitoring.shouldArmMonitoring
import app.dozecam.network.NetworkMonitor
import app.dozecam.network.NetworkReach
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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

    // Optimistic until the monitor says otherwise: the viewer must not open
    // on a warning it is about to take back a frame later.
    private val networkReach = MutableStateFlow(NetworkReach.LOCAL)

    /** Camera to open fullscreen, delivered by a wake alert. */
    private val alertCameraId = MutableStateFlow<String?>(null)

    private val monitoringStarter = MonitoringStarter(this)

    /**
     * Losing focus for good is treated as the user's own switch being turned
     * off, so the viewer comes back silent rather than jumping back in when
     * whatever took the speaker is finished with it.
     */
    private val audioFocus by lazy { ViewerAudioFocus(this) { setViewerSound(false) } }

    // Nothing in the app can reach the LAN without this, so ask up front rather
    // than letting the first console or stream connection time out.
    private val localNetworkPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        // No arming here, unlike SettingsActivity's: the prompt is an activity,
        // so answering it resumes this one, and the RESUMED autoArm below picks
        // a grant up on its own. Denial is surfaced where the connection fails.
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null && !LocalNetworkPermission.isGranted(this)) {
            localNetworkPermission.launch(LocalNetworkPermission.name)
        }
        applyAlertIntent(intent)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        enableEdgeToEdge()
        applyImmersiveMode()

        // Without this the volume rocker adjusts the ring stream whenever no
        // camera happens to be audible — so the one gesture for "make this
        // quieter" would change the wrong thing at exactly the wrong moment.
        volumeControlStream = AudioManager.STREAM_MUSIC

        val networkMonitor = NetworkMonitor(applicationContext)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                networkMonitor.reach.collect { networkReach.value = it }
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

        // One focus request for the whole viewer, held for exactly as long as
        // it is allowed to make noise. Going to the background silences the
        // players anyway, so holding on past that would leave every other app
        // ducked for a viewer nobody can hear — and so would keeping it while
        // there is no camera on to produce a sound in the first place. The
        // switch itself survives either: turning a camera back on picks the
        // focus up again rather than making the user ask twice.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                try {
                    combine(
                        appContainer.appSettings.settings.map { it.viewerSound },
                        appContainer.cameras.enabledCameras.map { it.isNotEmpty() },
                    ) { soundOn, anyCameras -> soundOn && anyCameras }
                        .distinctUntilChanged()
                        .collect { wanted ->
                            when {
                                !wanted -> audioFocus.release()
                                // Refused means something else owns the
                                // speaker. Switching back off is the honest
                                // answer: a sound button that is on while the
                                // phone stays silent is worse than one that
                                // did not take.
                                !audioFocus.request() -> setViewerSound(false)
                            }
                        }
                } finally {
                    audioFocus.release()
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
                        container.monitoringState,
                        container.detectorSettings,
                    ),
                )
                val cameras by viewModel.cameras.collectAsStateWithLifecycle()
                val sources by viewModel.sources.collectAsStateWithLifecycle()
                val unmonitorable by viewModel.unmonitorable.collectAsStateWithLifecycle()
                val disabledOnly by viewModel.hasDisabledOnly.collectAsStateWithLifecycle()
                val monitoring by viewModel.monitoringRunning.collectAsStateWithLifecycle()
                val canMonitor by viewModel.canMonitor.collectAsStateWithLifecycle()
                val stoppedByUser by viewModel.stoppedByUser.collectAsStateWithLifecycle()
                val audioLevels by viewModel.audioLevels.collectAsStateWithLifecycle()
                val audioThreshold by viewModel.audioThreshold.collectAsStateWithLifecycle()
                val reach by networkReach.collectAsStateWithLifecycle()
                val alertCamera by alertCameraId.collectAsStateWithLifecycle()
                val soundGranted by audioFocus.granted.collectAsStateWithLifecycle()

                // Coming back to the front may mean a different console was
                // signed in while we were away.
                LaunchedEffect(viewModel) {
                    lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                        viewModel.refreshSources()
                    }
                }

                // Watching a camera holds the display, unless the user has
                // switched that off; an empty viewer pointing at console setup
                // has no business doing so either way.
                val keepAwake = cameras.isNotEmpty() && appSettings.keepScreenOn
                LaunchedEffect(keepAwake) {
                    if (keepAwake) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }

                MonitorScreen(
                    cameras = cameras,
                    sources = sources,
                    controllerFactory = ::controllerFor,
                    networkReach = reach,
                    unmonitorable = unmonitorable,
                    hasDisabledOnly = disabledOnly,
                    onOpenSettings = { startActivity(SettingsActivity.intent(this)) },
                    onOpenOnboarding = { startActivity(OnboardingActivity.intent(this)) },
                    monitoringRunning = monitoring,
                    canMonitor = canMonitor,
                    stoppedByUser = stoppedByUser,
                    onStopMonitoring = ::stopMonitoring,
                    onStartMonitoring = ::startMonitoring,
                    soundEnabled = appSettings.viewerSound,
                    onSoundEnabledChange = ::setViewerSound,
                    keepScreenOn = appSettings.keepScreenOn,
                    onKeepScreenOnChange = ::setKeepScreenOn,
                    // The cameras follow the focus we actually hold, not the
                    // switch: a call or a navigation prompt silences them
                    // without touching it, and they come back on their own
                    // once the interruption is over. Sound the user has just
                    // asked for likewise waits for the request to be granted
                    // rather than starting on the strength of the setting.
                    soundGranted = soundGranted,
                    audioLevels = audioLevels,
                    audioThreshold = audioThreshold,
                    alertCameraId = alertCamera,
                    onAlertConsumed = { alertCameraId.value = null },
                    // The whole viewer is immersive, including the grid. A
                    // layout change is another chance to restore that state if
                    // Android exposed its transient system bars meanwhile.
                    onFullscreenChange = { applyImmersiveMode() },
                    onAlertDismissed = ::revokeLockScreenVisibility,
                )
            }
        }
    }

    /**
     * Being looked at is the acknowledgement — but only when a person is doing
     * the looking.
     *
     * The alert's own full-screen intent puts this activity on the lock screen
     * showing the camera that got loud, with nobody awake, so the viewer merely
     * being up cannot be the signal: taken as one it would silence the alarm a
     * second in, before the ramp had climbed at all. A real touch or key press
     * — including the volume rocker someone gropes for in the dark — is the
     * first thing that only a person can produce.
     */
    override fun onUserInteraction() {
        super.onUserInteraction()
        // A no-op unless an alarm is actually sounding, so ordinary use of the
        // viewer costs nothing.
        appContainer.alertSignaler.acknowledge()
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

        // A tap on the notification is a person, and the tap lands in System
        // UI's window rather than ours, so it never reaches onUserInteraction.
        // It carries its own secret rather than a flag anyone could set: this
        // activity is exported, and a camera id is no barrier at all — an
        // install migrated from v0.4 has exactly one camera, called "legacy".
        // Only the content intent of a notification we posted carries this.
        val fromOurTap = intent.getStringExtra(EXTRA_ALERT_TAP_KEY)
            ?.let { it == alertTapKey } == true
        if (fromOurTap) alertTapKey = newAlertToken()

        // Either secret authorises the lock screen, and for the same reason: it
        // proves the launch came from our own alert. Without the tap key here,
        // opening the notification after the full-screen intent had already
        // spent the wake token would *revoke* the privilege and let the keyguard
        // cover the very camera the user had just tapped to see.
        val fromUs = fromOurAlert || fromOurTap
        setShowWhenLocked(fromUs)
        setTurnScreenOn(fromUs)

        // Deliberately outside that check: showing a camera to someone already
        // past the lock screen is not the risk, so a second tap on the alert
        // still opens the right camera even though it can no longer wake.
        alertCameraId.value = cameraId

        // The full-screen launch is unattended by definition and must never be
        // read as anyone having arrived.
        if (fromOurTap) appContainer.alertSignaler.acknowledge()
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

    /** Keeps Android's status and navigation bars out of the whole viewer. */
    private fun applyImmersiveMode() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
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
     * Stopping by hand, from the badge over the cameras.
     *
     * The intent is recorded before the service is asked to go, and for the
     * same reason the settings switch records it: [autoArm] runs on every
     * resume, so without it the next glance at the viewer would start again
     * what the user just ended.
     */
    private fun stopMonitoring() {
        appContainer.monitoringState.userStopped.value = true
        MonitoringService.stop(this)
    }

    /**
     * Back on, through the gate auto-arming uses so a manual start cannot
     * misfire — except that the gate refuses outright without local-network
     * access, which would make this the one control on screen that visibly
     * does nothing when tapped and never says why. Asked for here instead, the
     * way the settings switch asks, with [autoArm] left to the answer.
     */
    private fun startMonitoring() {
        appContainer.monitoringState.userStopped.value = false
        if (LocalNetworkPermission.isGranted(this)) {
            lifecycleScope.launch { autoArm() }
        } else {
            localNetworkPermission.launch(LocalNetworkPermission.name)
        }
    }

    /** Remembered, so the viewer opens the way it was last left. */
    private fun setViewerSound(enabled: Boolean) {
        lifecycleScope.launch {
            appContainer.appSettings.update { it.copy(viewerSound = enabled) }
        }
    }

    /** Remembered for the same reason the sound switch is. */
    private fun setKeepScreenOn(enabled: Boolean) {
        lifecycleScope.launch {
            appContainer.appSettings.update { it.copy(keepScreenOn = enabled) }
        }
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
        private const val EXTRA_ALERT_TAP_KEY = "alert_tap_key"

        /**
         * Proves an alert intent came from this process, once. Never persisted,
         * and replaced as soon as it is spent: another app cannot guess it, and
         * a stale copy — from a previous process, or a second tap on the same
         * notification — simply declines to wake the screen. The alert still
         * arrives as an ordinary notification either way.
         */
        @Volatile
        private var alertToken: String = newAlertToken()

        /**
         * The same idea for the tap, and separate from the wake token because
         * the two are spent at different moments: the full-screen intent burns
         * the wake token the instant Android launches the viewer by itself, and
         * the tap that follows must still be able to prove where it came from.
         */
        @Volatile
        private var alertTapKey: String = newAlertToken()

        private fun newAlertToken(): String = UUID.randomUUID().toString()

        /**
         * The way back in from the ongoing monitoring notification: the viewer
         * as the user left it, and nothing more.
         *
         * Pointedly not an [alertIntent] with the extras left off. It carries
         * no camera to open and neither secret, so the keyguard stays exactly
         * where it is — a notification that is up all night must never be a
         * standing invitation to put the nursery on a locked screen.
         */
        fun viewerIntent(context: Context): Intent =
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        /** Full-screen wake target for [app.dozecam.monitoring.MonitoringNotifications]. */
        fun alertIntent(context: Context, cameraId: String): Intent =
            Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_ALERT_CAMERA_ID, cameraId)
                .putExtra(EXTRA_ALERT_TOKEN, alertToken)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        /**
         * The same target, for the tap on the notification rather than the
         * unattended full-screen launch. It goes straight to this activity
         * rather than through a receiver that would then start it: that shape is
         * the notification trampoline Android 12 blocks outright, and this app
         * starts at 12.
         */
        fun alertTapIntent(context: Context, cameraId: String): Intent =
            alertIntent(context, cameraId).putExtra(EXTRA_ALERT_TAP_KEY, alertTapKey)
    }
}

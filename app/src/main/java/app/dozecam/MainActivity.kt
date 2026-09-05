package app.dozecam

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.app.KeyguardManager
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
import app.dozecam.audio.MediaAudioFocus
import app.dozecam.audio.SoundDetector
import app.dozecam.audio.talkback.ProtectTalkback
import app.dozecam.data.AppSettings
import app.dozecam.data.OrientationLock
import app.dozecam.data.SoundMode
import app.dozecam.monitoring.MonitoringService
import app.dozecam.monitoring.MonitoringStarter
import app.dozecam.monitoring.ReadinessCheck
import app.dozecam.monitoring.ReadinessFinding
import app.dozecam.monitoring.ReadinessPrompt
import app.dozecam.monitoring.shouldArmMonitoring
import app.dozecam.network.NetworkMonitor
import app.dozecam.network.NetworkReach
import app.dozecam.permissions.LocalNetworkPermission
import app.dozecam.permissions.LocalNetworkPermissionRequest
import app.dozecam.permissions.MicrophonePermission
import app.dozecam.protect.ProtectApiException
import app.dozecam.player.LivestreamVideoPlayerController
import app.dozecam.player.StreamSource
import app.dozecam.player.VideoPlayerController
import app.dozecam.player.VlcVideoPlayerController
import app.dozecam.ui.components.FullScreenIntentDialog
import app.dozecam.ui.components.LocalNetworkPermissionDialog
import app.dozecam.ui.monitor.MonitorScreen
import app.dozecam.ui.monitor.MonitorViewModel
import app.dozecam.ui.onboarding.OnboardingActivity
import app.dozecam.ui.settings.SettingsActivity
import app.dozecam.ui.theme.DozecamTheme
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.delay
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

    /**
     * Whether an alerted room is the thing on screen right now.
     *
     * Separate from [alertCameraId], which the viewer consumes the instant it
     * has opened the camera, and from the alarm, which a room playing aloud
     * deliberately never sounds. With several rooms in the mix an alert lights
     * the screen and stays silent — and with neither of the other two left
     * standing, nothing would stop a bedtime prompt appearing over the very
     * room the screen was lit to identify. Cleared when the viewer says the
     * alert is over.
     */
    private val alertShowing = MutableStateFlow(false)

    /**
     * The screen we are on came up because of the bedtime test, not a room.
     * Held until a person dismisses it: this is the one alert whose whole job
     * is to be recognised for what it was.
     */
    private val testAlertShowing = MutableStateFlow(false)

    /**
     * The bedtime checks worth interrupting for, or nothing — which is the case
     * on all but the first viewing of a fresh failure. See [ReadinessPrompt].
     */
    private val readinessPrompt = MutableStateFlow<List<ReadinessFinding>>(emptyList())

    /**
     * Whether this visit has already had its one interruption.
     *
     * The prompt is raised at most once per time the viewer comes to the front,
     * which is what makes clearing it stick. Without this, a prompt displaced
     * by a real alert would be rebuilt by the very next probe emission — a
     * second or two later, on top of the crying room it was displaced for, with
     * its first touch silencing the alarm. Reset on each resume, so a prompt
     * nobody answered is offered again next time.
     */
    private var readinessPromptSpent = false

    /**
     * Whether an alert is what brought the viewer to the front this time.
     *
     * A visit somebody chose gets its one bedtime interruption; a visit an
     * alert caused does not. They came for the room, and once they have seen it
     * they are trying to get back to sleep — a checklist twelve seconds later
     * is the last thing that should be waiting for them.
     */
    private var alertStartedThisVisit = false

    private val monitoringStarter = MonitoringStarter(this)

    /**
     * The app's one owner of the speaker, shared with the monitoring service so
     * listen mode can go on holding it after this activity is gone. The viewer
     * is only ever one of its holders, and lets go as it leaves.
     */
    private val audioFocus by lazy { appContainer.audioFocus }

    // Nothing in the app can reach the LAN without this, so ask up front rather
    // than letting the first console or stream connection time out.
    //
    // No arming on the answer: the prompt is an activity, so answering it
    // resumes this one and the RESUMED autoArm below picks a grant up on its
    // own. A refusal of the ask made on launch is left to the connection that
    // fails; one the user brought on by reaching for the "not monitoring"
    // badge is explained, because that path has no connection left to fail.
    private val localNetwork = LocalNetworkPermissionRequest(this)

    /**
     * Asked for on the first press of a talk-back control and never on the way
     * in. Either answer changes what that control should say, so both re-resolve
     * it rather than only the grant.
     */
    private val microphonePermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { talkback.refresh() }

    /**
     * Talk-back for whichever camera is on screen alone. Nothing is asked of the
     * console until one is.
     */
    private val talkback: ProtectTalkback by lazy {
        val api = appContainer.protectPublicApi
        ProtectTalkback(
            scope = lifecycleScope,
            speakers = {
                api.withClient { client, apiKey ->
                    client.cameras(apiKey).associate { it.id to it.hasSpeaker }
                }.orEmpty()
            },
            session = { cameraId ->
                api.withClient { client, apiKey -> client.talkbackSession(apiKey, cameraId) }
                    ?: throw ProtectApiException("No console is signed in", null)
            },
            consoleHost = api::consoleHost,
            hasApiKey = api::hasApiKey,
            volume = { appContainer.appSettings.settings.first().talkbackVolume },
            microphoneGranted = { MicrophonePermission.isGranted(this) },
            // A permission dialog cannot be answered over a keyguard, so the
            // control says "unlock" rather than firing one at a locked screen.
            locked = { getSystemService(KeyguardManager::class.java).isKeyguardLocked },
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // An exit asked for from the notification while no viewer was up has
        // been carried out already — the service is gone — and must not close
        // the viewer that is opening now. Cleared before it is watched.
        appContainer.monitoringState.exitRequested.value = false
        lifecycleScope.launch {
            appContainer.monitoringState.exitRequested.collect { if (it) exit() }
        }
        // Carried across a recreation of its own, because the intent that
        // raised it cannot carry it twice: the wake token is spent the instant
        // Android launches this activity, so a replayed launch intent proves
        // nothing. Without this the alarm would ring on with the card that
        // explains it gone.
        testAlertShowing.value =
            savedInstanceState?.getBoolean(STATE_TEST_ALERT_SHOWING) == true
        if (savedInstanceState == null && !LocalNetworkPermission.isGranted(this)) {
            localNetwork.ask(explainRefusal = false)
        }
        applyAlertIntent(intent)
        // Re-asserted after the intent has been re-processed, because a
        // recreated activity replays a launch intent whose single-use token was
        // spent the moment Android first opened this window — so the line above
        // hands the keyguard back over the card that is about to be restored,
        // with the test alarm still sounding behind it.
        //
        // Safe to restore where the token is not, and for a different reason:
        // this comes from our own saved state rather than from an intent anyone
        // could forge, and the test card shows no camera at all. It buys back a
        // sentence explaining the noise, not a look at the nursery.
        if (testAlertShowing.value) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
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

        // Whether a camera answers is a fact about the network this device is
        // on, not about whether that network is a LAN — two Wi-Fi networks read
        // alike to `reach` and are deduplicated away. So talk-back forgets what
        // it learned whenever the default network is replaced at all, which is
        // the only signal that fires on a handover between them.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                networkMonitor.defaultNetworkChanges.collect { talkback.refresh() }
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
        // setting itself survives either: turning a camera back on picks the
        // focus up again rather than making the user ask twice.
        val soundOn = appContainer.appSettings.settings.map { it.soundMode != SoundMode.OFF }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                try {
                    combine(
                        soundOn,
                        appContainer.cameras.enabledCameras.map { it.isNotEmpty() },
                    ) { on, anyCameras -> on && anyCameras }
                        .distinctUntilChanged()
                        .collect { wanted ->
                            when {
                                !wanted -> audioFocus.release(MediaAudioFocus.Client.VIEWER)
                                // Refused means something else owns the
                                // speaker. Switching back off is the honest
                                // answer: a sound button that is on while the
                                // phone stays silent is worse than one that
                                // did not take.
                                //
                                // Losing it for good is treated the same way,
                                // so the viewer comes back silent rather than
                                // jumping back in when whatever took the
                                // speaker is finished with it.
                                !audioFocus.request(MediaAudioFocus.Client.VIEWER) {
                                    setSoundMode(SoundMode.OFF)
                                } -> setSoundMode(SoundMode.OFF)
                            }
                        }
                } finally {
                    audioFocus.release(MediaAudioFocus.Client.VIEWER)
                }
            }
        }

        // Listen mode stands down while the viewer is making noise of its own.
        // Reported from here rather than inferred by the service, because only
        // this side knows all three parts of it: the setting, the speaker, and
        // whether the viewer is on screen at all.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                try {
                    combine(soundOn, audioFocus.granted) { on, granted -> on && granted }
                        .distinctUntilChanged()
                        .collect { appContainer.monitoringState.viewerAudible.value = it }
                } finally {
                    // Backgrounded silences the players whatever the setting
                    // says, so the nursery must be free to come back at once —
                    // this is the moment listen mode exists for.
                    appContainer.monitoringState.viewerAudible.value = false
                }
            }
        }

        // Always armed: coming to the front with cameras switched on means
        // monitoring should be running. There is no switch to have left off;
        // monitoring ends with the app, and only then.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) { autoArm() }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) { watchReadiness() }
        }

        // A room is crying, and nothing this app was in the middle of saying
        // matters beside it.
        //
        // Watched here rather than left to the alert intent, because on a phone
        // that is already awake there is no intent: Android shows the alert as
        // a heads-up and never launches the full-screen target, so
        // applyAlertIntent is not called at all. The dialogs would then still be
        // standing when the user reached for them — and reaching for one is a
        // touch, which onUserInteraction reads as "a person is here" and
        // silences the alarm with, on this screen, the alerted room never shown.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(
                    appContainer.alertSignaler.alarmingCameraId,
                    // The detectors as well as the alarm, because the two part
                    // company exactly where it matters: a room already playing
                    // aloud raises its alert with no sound and — when it is the
                    // only room in the mix — no full-screen launch either. Read
                    // off the alarm alone, that alert would arrive with nothing
                    // to clear the test card, which would go on insisting
                    // nothing had happened in the nursery. The map is what makes
                    // this affordable: the camera states churn with every
                    // decoded buffer, and whether anything is triggered does not.
                    appContainer.monitoringState.cameras
                        .map { states ->
                            states.values.any { it.phase == SoundDetector.Phase.TRIGGERED }
                        }
                        .distinctUntilChanged(),
                ) { alarming, anyTriggered ->
                    anyTriggered ||
                        (alarming != null && alarming != MonitoringService.TEST_CAMERA_ID)
                }
                    .distinctUntilChanged()
                    .collect { crying -> if (crying) clearStandingDialogs() }
            }
        }

        setContent {
            val container = appContainer
            val appSettings by container.appSettings.settings
                .collectAsStateWithLifecycle(initialValue = AppSettings())
            // Keep camera overlays and viewer controls dark even in system light mode.
            DozecamTheme(nightTheme = appSettings.nightTheme, darkTheme = true) {
                val viewModel: MonitorViewModel = viewModel(
                    factory = MonitorViewModel.factory(
                        container.cameras,
                        container.protectCredentials,
                        container.monitoringState,
                        container.detectorSettings,
                        container.readiness.findings,
                    ),
                )
                val cameras by viewModel.cameras.collectAsStateWithLifecycle()
                val sources by viewModel.sources.collectAsStateWithLifecycle()
                val unmonitorable by viewModel.unmonitorable.collectAsStateWithLifecycle()
                val disabledOnly by viewModel.hasDisabledOnly.collectAsStateWithLifecycle()
                val monitoring by viewModel.monitoringRunning.collectAsStateWithLifecycle()
                val failures by viewModel.failures.collectAsStateWithLifecycle()
                val canMonitor by viewModel.canMonitor.collectAsStateWithLifecycle()
                val audioLevels by viewModel.audioLevels.collectAsStateWithLifecycle()
                val audioThreshold by viewModel.audioThreshold.collectAsStateWithLifecycle()
                val reach by networkReach.collectAsStateWithLifecycle()
                val alertCamera by alertCameraId.collectAsStateWithLifecycle()
                val soundGranted by audioFocus.granted.collectAsStateWithLifecycle()
                val localNetworkDenial by localNetwork.denial.collectAsStateWithLifecycle()
                val readiness by viewModel.readiness.collectAsStateWithLifecycle()
                val prompt by readinessPrompt.collectAsStateWithLifecycle()
                val testAlert by testAlertShowing.collectAsStateWithLifecycle()
                val explainFullScreenIntent by monitoringStarter.explainFullScreenIntent
                    .collectAsStateWithLifecycle()

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
                    monitoringFailures = failures,
                    canMonitor = canMonitor,
                    onStartMonitoring = ::startMonitoring,
                    soundMode = appSettings.soundMode,
                    onSoundModeChange = ::setSoundMode,
                    alertsEnabled = appSettings.alertsEnabled,
                    onAlertsEnabledChange = ::setAlertsEnabled,
                    onExit = ::exit,
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
                    onAlertDismissed = ::onAlertDismissed,
                    talkback = talkback,
                    onRequestMicrophone = {
                        microphonePermission.launch(MicrophonePermission.name)
                    },
                    readiness = readiness,
                    readinessPrompt = prompt,
                    onReadinessPromptOpen = {
                        acknowledgeReadinessPrompt()
                        startActivity(SettingsActivity.intent(this))
                    },
                    onReadinessPromptDismiss = ::acknowledgeReadinessPrompt,
                    testAlertShowing = testAlert,
                    onTestAlertDismissed = {
                        testAlertShowing.value = false
                        // The test's alarm, and only the test's. A room can
                        // start crying in the frame between this card being
                        // told to go and it going, and "Got it" on a card about
                        // a test must never be what silences a nursery.
                        if (appContainer.alertSignaler.alarmingCameraId.value ==
                            MonitoringService.TEST_CAMERA_ID
                        ) {
                            appContainer.alertSignaler.stop()
                        }
                        revokeLockScreenVisibility()
                    },
                )
                // Over the viewer rather than in it: the badge asked for this
                // grant, and its refusal leaves nothing on the grid to read as
                // a cause — every camera goes on playing exactly as it was.
                localNetworkDenial?.let { denial ->
                    LocalNetworkPermissionDialog(
                        denial = denial,
                        onAllow = localNetwork::resolve,
                        onDismiss = localNetwork::dismiss,
                    )
                }
                if (explainFullScreenIntent) {
                    FullScreenIntentDialog(
                        onOpenSettings = {
                            acknowledgeReadinessCheck(ReadinessCheck.WAKE_SCREEN)
                            monitoringStarter.openFullScreenIntentSettings()
                        },
                        onDismiss = {
                            acknowledgeReadinessCheck(ReadinessCheck.WAKE_SCREEN)
                            monitoringStarter.dismissFullScreenIntentExplanation()
                        },
                    )
                }
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

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_TEST_ALERT_SHOWING, testAlertShowing.value)
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
        val cameraId = intent.getStringExtra(EXTRA_ALERT_CAMERA_ID)
        // A monitoring failure wakes the viewer with no room to open: what it
        // has to show is the failure notice, and every tile as it stands.
        if (cameraId == null && !intent.getBooleanExtra(EXTRA_ALERT_FAILURE, false)) return
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

        // A monitoring failure names no room either: it wakes the viewer onto
        // the grid, every tile as it stands, with the failure notice saying
        // what is wrong. Nothing to open, so nothing to hand to the grid.
        if (cameraId == null) {
            if (fromOurTap) appContainer.alertSignaler.acknowledge()
            return
        }

        // The bedtime test names no room, and must not be handed to the viewer
        // as though it did. Its camera id belongs to no camera, so the grid
        // would find nothing to show and take the alert straight back down —
        // handing back the right to sit over the keyguard in the same breath,
        // which would let the lock screen slide over the very card explaining
        // why the phone just lit up, with the alarm still sounding behind it.
        //
        // Only from our own alert, like the wake privilege itself: an exported
        // activity must not let another app put a Dozecam-branded card over the
        // viewer, however harmless the words on it.
        // Recognised by its camera id as well as by its extra, and outside the
        // `fromUs` gate on purpose. The id belongs to no camera anyone can add,
        // so treating it as "not a room" is always the right answer — including
        // on a recreated activity replaying its original launch intent, where
        // the single-use token has long since been spent and `fromUs` is false.
        // Without that, the synthetic id would take the real-alert path, find
        // no camera, and hand back the lock screen over a still-sounding alarm.
        if (cameraId == MonitoringService.TEST_CAMERA_ID ||
            intent.getBooleanExtra(EXTRA_ALERT_TEST, false)
        ) {
            // Saying it was a test is still only ours to say: this activity is
            // exported, and a Dozecam-branded card is not something another app
            // gets to put on screen.
            if (fromUs) testAlertShowing.value = true
            // A tap on the notification is still a person arriving, test or not.
            if (fromOurTap) appContainer.alertSignaler.acknowledge()
            return
        }

        // A room is actually crying, and it outranks everything this app might
        // have been in the middle of saying. Any of the three dialogs could be
        // standing when the alert lands — the viewer is where the bedtime
        // prompt and the full-screen-access explanation appear, and the test
        // card is left up until a person answers it — and a modal over the
        // alerted camera is worse than a nuisance: the first touch aimed at it
        // reaches onUserInteraction and silences the alarm before anyone has
        // seen the room, and the test card would go on insisting nothing had
        // happened in the nursery while it did.
        clearStandingDialogs()
        alertStartedThisVisit = true

        // Deliberately outside the check above: showing a camera to someone
        // already past the lock screen is not the risk, so a second tap on the
        // alert still opens the right camera even though it can no longer wake.
        alertCameraId.value = cameraId
        alertShowing.value = true

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
    /**
     * The two questions talk-back cannot answer for itself — whether the
     * microphone was granted, and whether the phone is locked — are both settled
     * outside this app and can both have changed while it was away.
     */
    override fun onResume() {
        super.onResume()
        talkback.refresh()
    }

    override fun onPause() {
        // Talk-back lives only while the viewer is on screen — that is the
        // promise the manifest makes, and the reason there is no microphone
        // foreground-service type to fall back on. A finger still down as the
        // activity goes away must not leave one open behind it.
        talkback.release()
        super.onPause()
        revokeLockScreenVisibility()
    }

    /**
     * Hands back the right to sit over the keyguard. Called the moment the
     * alerted camera stops being the only thing on screen: the alert bought a
     * look at one room, not at the whole house.
     */
    /**
     * The alerted room is no longer the only thing on screen — dismissed, or
     * gone before it could be shown. The wake privilege goes back, and the
     * viewer stops counting itself as showing an alert, which is what lets the
     * bedtime prompt speak again.
     */
    private fun onAlertDismissed() {
        alertShowing.value = false
        revokeLockScreenVisibility()
    }

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
     * Says once, out loud, when something that has to work tonight has stopped
     * working — and then never again for that same failure.
     *
     * The wait is not politeness. Arming the monitor is the moment every camera
     * is briefly unheard: nothing has connected, nothing has decoded, and a
     * check run against that instant would report the truth about a state that
     * lasts two seconds. A warning that is usually wrong is not read at all by
     * the night it is right, so this lets the monitor settle first.
     */
    private suspend fun watchReadiness() {
        // A fresh visit gets its one interruption back — unless an alert is
        // what caused it, in which case it has already been spent on the room.
        readinessPromptSpent = alertStartedThisVisit
        alertStartedThisVisit = false
        delay(READINESS_SETTLE_MS)
        appContainer.readiness.findings.collect { findings ->
            val acknowledged = appContainer.appSettings.settings.first()
                .acknowledgedReadinessChecks
            // A check that has started passing again is forgotten, so the next
            // time it breaks it is worth saying again. Written only when it
            // actually moved: this collects several times a second.
            val remembered = ReadinessPrompt.remembered(findings, acknowledged)
            if (remembered != acknowledged) {
                // Recomputed inside the transform rather than written from the
                // snapshot above: an acknowledgement can commit between the
                // read and this edit, and writing the older set back would
                // spend the one interruption it had just recorded.
                appContainer.appSettings.update {
                    it.copy(
                        acknowledgedReadinessChecks = ReadinessPrompt.remembered(
                            findings,
                            it.acknowledgedReadinessChecks,
                        ),
                    )
                }
            }
            // Raised at most once, and then left standing until a person
            // answers it. Both halves matter: rebuilding it as the findings
            // churn would swap the dialog out from under a finger already on
            // its way to a button, and raising a second one would undo every
            // reason there was to take the first one down — a real alert
            // arriving is exactly such a reason.
            // Never over a room that is crying. Resuming the viewer resets the
            // one-per-visit rule above — a resume is a new visit — but the
            // resume that matters most is the one the alert itself caused, and
            // twelve seconds later a checklist would be sitting on top of the
            // nursery with its first touch silencing the alarm.
            if (!readinessPromptSpent && !alertInFlight() && !aDialogIsUp()) {
                val fresh = ReadinessPrompt.unannounced(findings, remembered)
                if (fresh.isNotEmpty()) {
                    readinessPrompt.value = fresh
                    readinessPromptSpent = true
                }
            }
        }
    }

    /**
     * Whether an alert is the reason this viewer is on screen: an alarm
     * sounding, or a wake alert whose camera has not been shown yet. Either way
     * the room is the only thing worth looking at.
     */
    private fun alertInFlight(): Boolean =
        appContainer.alertSignaler.isAlarming ||
            alertCameraId.value != null ||
            alertShowing.value

    /**
     * Whether some dialog of ours already has the screen. One question at a
     * time is a rule the viewer keeps everywhere — and the full-screen-access
     * explanation is about the very thing the prompt would most likely be
     * raising, so following "Not now" with a second warning about the same
     * missing grant would be nagging in two windows.
     */
    private fun aDialogIsUp(): Boolean =
        testAlertShowing.value ||
            appContainer.monitoringState.explainFullScreenIntent.value ||
            localNetwork.denial.value != null

    /**
     * Takes down everything of ours that could be sitting over the cameras: the
     * card saying the last alert was only a test, the bedtime prompt, and the
     * full-screen-access explanation. None of them is worth a second of a
     * parent's attention while a room is actually crying, and each of them is a
     * thing a finger would land on instead of the room.
     *
     * The prompt is marked as spent rather than merely cleared, because the
     * probe emits every couple of seconds and would otherwise put it straight
     * back up. It is offered again the next time the viewer comes to the front.
     */
    private fun clearStandingDialogs() {
        testAlertShowing.value = false
        readinessPrompt.value = emptyList()
        readinessPromptSpent = true
        appContainer.monitoringState.explainFullScreenIntent.value = false
    }

    /**
     * Records that one check has been said out loud by something other than the
     * prompt — the full-screen-access explanation, which is a whole dialog
     * about the very thing [ReadinessCheck.WAKE_SCREEN] reports. Without this,
     * answering it would be followed moments later by a second modal saying the
     * same thing in fewer words.
     */
    private fun acknowledgeReadinessCheck(check: ReadinessCheck) {
        lifecycleScope.launch {
            appContainer.appSettings.update {
                it.copy(
                    acknowledgedReadinessChecks = ReadinessPrompt.acknowledging(
                        check,
                        it.acknowledgedReadinessChecks,
                    ),
                )
            }
        }
    }

    /** Dismissing is the acknowledgement: it was read, and it is not worth saying twice. */
    private fun acknowledgeReadinessPrompt() {
        val shown = readinessPrompt.value
        readinessPrompt.value = emptyList()
        lifecycleScope.launch {
            appContainer.appSettings.update {
                it.copy(
                    acknowledgedReadinessChecks = ReadinessPrompt.acknowledging(
                        shown,
                        it.acknowledgedReadinessChecks,
                    ),
                )
            }
        }
    }

    /**
     * The whole of Dozecam, ended: the monitor, its notification, the speaker,
     * and this viewer with its task. Monitoring has no switch of its own — it
     * runs for as long as the app does — so this is the one way to stop it,
     * and the next open arms it again. The stored settings are left exactly as
     * they were; that is what "remembered" means.
     */
    private fun exit() {
        // Marked as well as done, so any other screen of ours still alive
        // finishes too and nothing re-arms the monitor on its way out; the
        // next viewer to open clears it.
        appContainer.monitoringState.exitRequested.value = true
        MonitoringService.stop(this)
        finishAndRemoveTask()
    }

    /**
     * Trying again, from the "not monitoring" badge, through the gate
     * auto-arming uses so a manual start cannot misfire — except that the gate
     * refuses outright without local-network access, which would make this
     * the one control on screen that visibly does nothing when tapped and
     * never says why. Asked for here instead, with [autoArm] left to a grant
     * and a dialog to a refusal — including the refusal Android answers
     * instantly, with no prompt of its own, once the permission is permanently
     * denied.
     */
    private fun startMonitoring() {
        if (LocalNetworkPermission.isGranted(this)) {
            lifecycleScope.launch { autoArm() }
        } else {
            localNetwork.ask()
        }
    }

    /**
     * Remembered, so the viewer opens the way it was last left. One setting
     * for the viewer and the monitoring service both: the third mode is the
     * service's listen mode, and it reads the same value.
     */
    private fun setSoundMode(mode: SoundMode) {
        lifecycleScope.launch {
            appContainer.appSettings.update { it.copy(soundMode = mode) }
        }
    }

    /** Remembered for the same reason. */
    private fun setAlertsEnabled(enabled: Boolean) {
        lifecycleScope.launch {
            appContainer.appSettings.update { it.copy(alertsEnabled = enabled) }
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
        /**
         * How long after coming to the front the bedtime check may speak up.
         *
         * Long enough for a monitor that is only just arming to have connected
         * and decoded something — every camera reads as unheard until it has,
         * and a prompt fired into that gap would be a warning that is wrong
         * almost every time it appears.
         */
        private const val READINESS_SETTLE_MS = 12_000L

        private const val EXTRA_ALERT_CAMERA_ID = "alert_camera_id"
        private const val EXTRA_ALERT_TOKEN = "alert_token"
        private const val EXTRA_ALERT_TAP_KEY = "alert_tap_key"
        private const val EXTRA_ALERT_TEST = "alert_test"
        private const val EXTRA_ALERT_FAILURE = "alert_failure"
        private const val STATE_TEST_ALERT_SHOWING = "test_alert_showing"

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
        fun alertIntent(context: Context, cameraId: String, test: Boolean = false): Intent =
            Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_ALERT_CAMERA_ID, cameraId)
                .putExtra(EXTRA_ALERT_TOKEN, alertToken)
                // Says so on the screen it just woke. The notification names
                // itself a test, but the whole point of a full-screen intent is
                // that it arrives *instead* of a notification anyone reads, and
                // a viewer that lit up at bedtime with no explanation would be
                // the same fright the test was run to avoid.
                .putExtra(EXTRA_ALERT_TEST, test)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        /**
         * The same target, for the tap on the notification rather than the
         * unattended full-screen launch. It goes straight to this activity
         * rather than through a receiver that would then start it: that shape is
         * the notification trampoline Android 12 blocks outright, and this app
         * starts at 12.
         */
        fun alertTapIntent(context: Context, cameraId: String, test: Boolean = false): Intent =
            alertIntent(context, cameraId, test).putExtra(EXTRA_ALERT_TAP_KEY, alertTapKey)

        /**
         * The wake target for a monitoring failure: the viewer over the lock
         * screen, opened on nothing in particular, carrying the same secret
         * the sound alert does — the keyguard is no more anyone else's to lift
         * for a failure than for a cry.
         */
        fun failureIntent(context: Context): Intent =
            Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_ALERT_FAILURE, true)
                .putExtra(EXTRA_ALERT_TOKEN, alertToken)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        /** The tap on a failure card, told apart from the unattended launch as [alertTapIntent] is. */
        fun failureTapIntent(context: Context): Intent =
            failureIntent(context).putExtra(EXTRA_ALERT_TAP_KEY, alertTapKey)
    }
}

package app.dozecam.ui.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.dozecam.R
import app.dozecam.appContainer
import app.dozecam.monitoring.AlarmSound
import app.dozecam.monitoring.MonitoringService
import app.dozecam.monitoring.MonitoringStarter
import app.dozecam.monitoring.ReadinessCheck
import app.dozecam.monitoring.ReadinessPrompt
import app.dozecam.monitoring.ReadinessRemedies
import app.dozecam.monitoring.ReadinessRemedy
import app.dozecam.monitoring.roomIsCrying
import app.dozecam.monitoring.shouldArmMonitoring
import app.dozecam.monitoring.shouldStopMonitoring
import app.dozecam.permissions.LocalNetworkPermission
import app.dozecam.permissions.LocalNetworkPermissionRequest
import app.dozecam.ui.components.FullScreenIntentDialog
import app.dozecam.ui.components.LocalNetworkPermissionDialog
import app.dozecam.ui.onboarding.OnboardingActivity
import app.dozecam.ui.theme.DozecamTheme
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class SettingsActivity : ComponentActivity() {

    private val monitoringStarter = MonitoringStarter(this)

    // Switching monitoring on is the moment LAN access stops being optional:
    // without it every RTSP connection is dropped as a timeout, which looks
    // like a broken camera rather than a missing permission. A grant re-arms
    // through the collector below, which watches this alongside the cameras;
    // a refusal is explained rather than left to flip the switch back in
    // silence.
    private val localNetwork = LocalNetworkPermissionRequest(this)

    /**
     * Asked for from the readiness card rather than on the way in. The system
     * dialog only appears while Android is still willing to show it; once it is
     * not, the launcher returns a refusal immediately and the card goes on
     * saying so, with its remedy now pointing at the app's notification
     * settings — which is the only place left that can change the answer.
     */
    /**
     * Whether asking Android for the notification permission has already come
     * to nothing this visit.
     *
     * There is no way to ask Android whether it *would* show its sheet, and the
     * three ways of not getting the permission are indistinguishable from the
     * result alone: a refusal it will ask about again, a sheet swiped away
     * without an answer, and a permanent denial where no sheet is drawn at all.
     * Navigating on any of them would take someone to a settings screen they
     * did not ask for; navigating on none of them leaves the button doing
     * nothing forever.
     *
     * So the first press asks, and a second press — a person pressing again
     * because the first did nothing — opens the one page that can still change
     * the answer. Nobody is sent anywhere they did not ask to go twice.
     */
    private var askingDidNotWork = false

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        askingDidNotWork = !granted
    }

    private val alertSoundPicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        // Null only comes back for "Silent", which the picker is not asked to
        // offer — but if a device offers it anyway, it has to mean the phone's
        // own alarm sound rather than an alert nobody can hear.
        val picked = result.data?.let {
            IntentCompat.getParcelableExtra(
                it,
                RingtoneManager.EXTRA_RINGTONE_PICKED_URI,
                Uri::class.java,
            )
        }
        // A tone the user added themselves comes back as a bare media URI with
        // no grant attached, and would fail silently at 3am. Refused here, awake
        // and with the picker still fresh in mind, rather than stored and
        // discovered later by not going off.
        if (picked != null && !AlarmSound.isPlayable(this, picked)) {
            Toast.makeText(this, R.string.alert_sound_unreadable, Toast.LENGTH_LONG).show()
            return@registerForActivityResult
        }
        lifecycleScope.launch {
            appContainer.appSettings.update { it.copy(alertSoundUri = picked?.toString()) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Switching a camera on — or adding one — is exactly when there becomes
        // something to listen to again. Without this, doing so here and then
        // leaving the app from settings would keep wake-on-sound off, because
        // the service stops itself when idle and only the viewer re-arms it.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Both signals matter: the camera set changing is the usual
                // trigger, and serviceRunning going false is how a service that
                // had stopped itself reports that it is finally gone — without
                // which a camera switched off and straight back on could land in
                // that gap and leave monitoring off.
                // Local-network access joins them because granting it is the
                // other way a refused arm becomes an armable one — and it can
                // be granted outside the app entirely, in Android's settings,
                // with nothing else here changing to notice.
                combine(
                    appContainer.cameras.enabledCameras,
                    appContainer.monitoringState.serviceRunning,
                    localNetwork.granted,
                ) { _, _, _ -> }.collect {
                    if (appContainer.shouldArmMonitoring(this@SettingsActivity)) {
                        monitoringStarter.startWithAlertPermissions()
                    } else if (appContainer.shouldStopMonitoring()) {
                        // Switching off the last listenable camera is what ends
                        // monitoring; the service deliberately never stops
                        // itself, so this is where it has to happen.
                        MonitoringService.stop(this@SettingsActivity)
                    }
                }
            }
        }
        // An exit asked for from the notification while this screen is up:
        // the whole task goes, not just this screen. finish() alone would hand
        // the task to the viewer underneath — which the system may already
        // have destroyed, in which case Android would recreate it, and a
        // fresh viewer arms the monitor the user just ended.
        lifecycleScope.launch {
            appContainer.monitoringState.exitRequested.collect { if (it) finishAffinity() }
        }
        setContent {
            val container = appContainer
            val settingsViewModel: SettingsViewModel = viewModel(
                factory = SettingsViewModel.factory(
                    container.appSettings,
                    container.cameras,
                    container.detectorSettings,
                    container.monitoringState,
                    container.protectCredentials,
                    container.readiness.findings,
                ),
            )
            val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
            val cameras by settingsViewModel.cameras.collectAsStateWithLifecycle()
            val detector by settingsViewModel.detector.collectAsStateWithLifecycle()
            val form by settingsViewModel.form.collectAsStateWithLifecycle()
            val monitoringRunning by settingsViewModel.monitoringRunning
                .collectAsStateWithLifecycle()
            val canMonitor by settingsViewModel.canMonitor.collectAsStateWithLifecycle()
            val hasLocalNetwork by localNetwork.granted.collectAsStateWithLifecycle()
            val localNetworkDenial by localNetwork.denial.collectAsStateWithLifecycle()
            val audioLevel by settingsViewModel.audioLevel.collectAsStateWithLifecycle()
            val readiness by settingsViewModel.readiness.collectAsStateWithLifecycle()
            val explainFullScreenIntent by monitoringStarter.explainFullScreenIntent
                .collectAsStateWithLifecycle()
            DozecamTheme(nightTheme = settings.nightTheme) {
                SettingsScreen(
                    settings = settings,
                    onSettingsChange = settingsViewModel::update,
                    monitoringRunning = monitoringRunning,
                    canMonitor = canMonitor,
                    localNetworkGranted = hasLocalNetwork,
                    audioLevel = audioLevel,
                    readiness = readiness,
                    onReadinessRemedy = ::applyReadinessRemedy,
                    onTestAlert = ::sendTestAlert,
                    cameras = cameras,
                    onCameraEnabled = settingsViewModel::setCameraEnabled,
                    onEditCamera = settingsViewModel::startEdit,
                    onDeleteCamera = settingsViewModel::deleteCamera,
                    form = form,
                    onFormName = settingsViewModel::onFormName,
                    onFormUrl = settingsViewModel::onFormUrl,
                    onFormSave = settingsViewModel::saveCamera,
                    onFormCancel = settingsViewModel::cancelEdit,
                    detector = detector,
                    onDetectorChange = settingsViewModel::onDetectorChange,
                    onOpenOnboarding = { startActivity(OnboardingActivity.intent(this)) },
                    onBack = { finish() },
                    onPickAlertSound = { pickAlertSound(settings.alertSoundUri) },
                    onPreviewAlertSound = { appContainer.alertSignaler.preview(settings) },
                )
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
                            acknowledgeWakeScreen()
                            monitoringStarter.openFullScreenIntentSettings()
                        },
                        onDismiss = {
                            acknowledgeWakeScreen()
                            monitoringStarter.dismissFullScreenIntentExplanation()
                        },
                    )
                }
            }
        }
    }


    /**
     * The explanation was read. The viewer's prompt has no business raising a
     * second modal about the same missing grant afterwards; the card here goes
     * on saying it for as long as it is true, which is the durable statement.
     */
    private fun acknowledgeWakeScreen() {
        lifecycleScope.launch {
            appContainer.appSettings.update {
                it.copy(
                    acknowledgedReadinessChecks = ReadinessPrompt.acknowledging(
                        ReadinessCheck.WAKE_SCREEN,
                        it.acknowledgedReadinessChecks,
                    ),
                )
            }
        }
    }

    /**
     * Carries out one row of the bedtime check.
     *
     * Three of these are ours to do outright — a setting, the service, and a
     * permission Android still has an answer for — and the rest are switches in
     * Android's own settings that only the user can throw. Either way the card
     * behind this asks again a second later and reports what actually happened,
     * so nothing here has to assume it worked.
     */
    private fun applyReadinessRemedy(remedy: ReadinessRemedy) {
        when (remedy) {
            // Below Android 13 there is no such permission to request: the
            // switch that turned notifications off lives in Android's own
            // settings, and asking would be a dialog the system cannot draw.
            ReadinessRemedy.REQUEST_NOTIFICATIONS ->
                if (Build.VERSION.SDK_INT < 33 || askingDidNotWork) {
                    ReadinessRemedies.openAppNotifications(this)
                } else {
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            ReadinessRemedy.TURN_ALERTS_ON ->
                lifecycleScope.launch {
                    appContainer.appSettings.update { it.copy(alertsEnabled = true) }
                }
            // The chime rather than both: one of the two is enough to make the
            // alert perceptible, and the sound is the half that wakes people.
            ReadinessRemedy.TURN_CHIME_ON ->
                lifecycleScope.launch {
                    appContainer.appSettings.update { it.copy(alertChime = true) }
                }
            // Through the same gate the viewer's "not monitoring" badge uses,
            // never straight at the starter. Without local-network access every
            // RTSP connection is dropped as a timeout, so arming would buy
            // nothing but a foreground service holding a wake lock while it
            // reconnects all night — and the readiness card would then blame
            // the cameras for it. A grant re-arms through the collector in
            // onCreate; a refusal is explained rather than left silent.
            ReadinessRemedy.START_MONITORING -> if (LocalNetworkPermission.isGranted(this)) {
                lifecycleScope.launch {
                    if (appContainer.shouldArmMonitoring(this@SettingsActivity)) {
                        monitoringStarter.startWithAlertPermissions()
                    }
                }
            } else {
                localNetwork.ask()
            }
            // The one place that knows how to ask, and how to explain a refusal
            // Android answers instantly once the permission is permanently
            // denied. A grant re-arms through the collector in onCreate.
            ReadinessRemedy.GRANT_LOCAL_NETWORK -> localNetwork.ask()
            // Handled inside the settings screen, which owns its own navigation.
            ReadinessRemedy.CAMERA_SETTINGS, ReadinessRemedy.NONE -> Unit
            else -> if (!ReadinessRemedies.open(this, remedy)) {
                Toast.makeText(this, R.string.readiness_remedy_unavailable, Toast.LENGTH_LONG)
                    .show()
            }
        }
    }

    /**
     * The real alert, from the real service. Everything about what that means
     * is explained by the dialog the user has just answered.
     */
    private fun sendTestAlert() {
        // The service refuses a test while a room is crying, and it is right
        // to — but a toast saying "sent" over a test that never fired would be
        // the exact false reassurance this whole card exists to remove. Asked
        // of the same rule the service applies, so the two cannot disagree.
        if (appContainer.roomIsCrying()) {
            Toast.makeText(this, R.string.readiness_test_busy, Toast.LENGTH_LONG).show()
            return
        }
        // The message describes the request, not the outcome. Raising the alert
        // is the service's to do and a moment away, and in that moment a room
        // can start crying and have the test rightly refused — so claiming it
        // had fired would be the same false reassurance, arrived at by a
        // narrower road. The alert itself is the confirmation.
        MonitoringService.testAlert(this)
        Toast.makeText(this, R.string.readiness_test_sent, Toast.LENGTH_SHORT).show()
    }

    /**
     * A touch stops the *test* alarm, and only the test alarm.
     *
     * The test is raised from this screen, and the one failure it exists to
     * expose is the one that would otherwise trap you: with notifications
     * denied, [MonitoringNotifications.postAlert] posts nothing, so there is no
     * card to swipe away and no full-screen intent to land on the viewer, while
     * the alarm rings for its full five minutes with nothing offering to stop
     * it. A touch stops it, which is what the dialog promises.
     *
     * Deliberately narrower than the viewer's rule, which acknowledges any
     * alarm on any touch. The viewer earns that by *showing* the room that got
     * loud; settings shows a preferences list, and a real cry arriving here —
     * on an unlocked phone it is only a heads-up notification — must not be
     * silenced by someone scrolling past it who never knew it was there.
     */
    override fun onUserInteraction() {
        super.onUserInteraction()
        if (appContainer.alertSignaler.alarmingCameraId.value ==
            MonitoringService.TEST_CAMERA_ID
        ) {
            appContainer.alertSignaler.stop()
        }
    }

    /** A preview belongs to this screen; leaving it takes the sound with it. */
    override fun onStop() {
        super.onStop()
        appContainer.alertSignaler.stopPreview()
    }

    /** Not every device ships a picker; without one the current sound stays as it was. */
    private fun pickAlertSound(current: String?) {
        runCatching { alertSoundPicker.launch(AlarmSound.pickerIntent(this, current)) }
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, SettingsActivity::class.java)
    }
}

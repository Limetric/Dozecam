package app.dozecam.ui.settings

import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
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
import app.dozecam.monitoring.shouldArmMonitoring
import app.dozecam.monitoring.shouldStopMonitoring
import app.dozecam.permissions.LocalNetworkPermissionRequest
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
            DozecamTheme(nightTheme = settings.nightTheme) {
                SettingsScreen(
                    settings = settings,
                    onSettingsChange = settingsViewModel::update,
                    monitoringRunning = monitoringRunning,
                    canMonitor = canMonitor,
                    localNetworkGranted = hasLocalNetwork,
                    audioLevel = audioLevel,
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
            }
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

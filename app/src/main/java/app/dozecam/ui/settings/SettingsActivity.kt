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
import app.dozecam.monitoring.AlertDnd
import app.dozecam.monitoring.MonitoringService
import app.dozecam.monitoring.MonitoringStarter
import app.dozecam.monitoring.shouldArmMonitoring
import app.dozecam.monitoring.shouldStopMonitoring
import app.dozecam.permissions.LocalNetworkPermission
import app.dozecam.ui.onboarding.OnboardingActivity
import app.dozecam.ui.theme.DozecamTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class SettingsActivity : ComponentActivity() {

    private val monitoringStarter = MonitoringStarter(this)

    private val alertDnd by lazy { AlertDnd(this) }

    /** Held by the system, not by us, and revocable there at any time. */
    private val dndGranted = MutableStateFlow(false)

    // Switching monitoring on is the moment LAN access stops being optional:
    // without it every RTSP connection is dropped as a timeout, which looks
    // like a broken camera rather than a missing permission.
    private val localNetworkPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) armIfNeeded() }

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

    private val dndGrant = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        // The result code says nothing; the grant itself is the answer.
        dndGranted.value = alertDnd.isGranted
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
                combine(
                    appContainer.cameras.enabledCameras,
                    appContainer.monitoringState.serviceRunning,
                ) { _, _ -> }.collect {
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
            val audioLevel by settingsViewModel.audioLevel.collectAsStateWithLifecycle()
            val dndAccess by dndGranted.collectAsStateWithLifecycle()
            DozecamTheme(nightTheme = settings.nightTheme) {
                SettingsScreen(
                    settings = settings,
                    onSettingsChange = settingsViewModel::update,
                    monitoringRunning = monitoringRunning,
                    canMonitor = canMonitor,
                    onToggleMonitoring = { enabled ->
                        settingsViewModel.onMonitoringIntent(enabled)
                        if (enabled) {
                            // Ask rather than fail silently: without LAN access
                            // arming does nothing, and a switch that flips back
                            // with no explanation is the worst of both.
                            if (LocalNetworkPermission.isGranted(this)) {
                                armIfNeeded()
                            } else {
                                localNetworkPermission.launch(LocalNetworkPermission.name)
                            }
                        } else {
                            MonitoringService.stop(this)
                        }
                    },
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
                    dndGranted = dndAccess,
                    onRequestDndGrant = {
                        runCatching { dndGrant.launch(AlertDnd.grantIntent()) }
                    },
                )
            }
        }
    }


    override fun onResume() {
        super.onResume()
        // Revocable in system settings without ever coming back through us.
        dndGranted.value = alertDnd.isGranted
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

    /** Through the same gate as auto-arming, so a manual start cannot misfire. */
    private fun armIfNeeded() {
        lifecycleScope.launch {
            if (appContainer.shouldArmMonitoring(this@SettingsActivity)) {
                monitoringStarter.startWithAlertPermissions()
            }
        }
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, SettingsActivity::class.java)
    }
}

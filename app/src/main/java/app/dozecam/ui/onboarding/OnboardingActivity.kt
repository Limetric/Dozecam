package app.dozecam.ui.onboarding

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.dozecam.appContainer
import app.dozecam.data.AppSettings
import app.dozecam.monitoring.MonitoringStarter
import app.dozecam.monitoring.shouldArmMonitoring
import app.dozecam.permissions.LocalNetworkPermission
import app.dozecam.ui.theme.DozecamTheme
import kotlinx.coroutines.launch

class OnboardingActivity : ComponentActivity() {

    // Console setup is the first thing to touch the LAN, and it can be reached
    // without passing through the viewer's request.
    private val localNetworkPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* Denial is surfaced on the connect attempt, which explains it. */ }

    private val monitoringStarter = MonitoringStarter(this)

    /**
     * Set once the user has finished. Saved because the permission prompt can
     * outlive this instance — rotating while it is open recreates the activity,
     * and the callback that would have closed us belongs to the dead one.
     */
    private var finishing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        finishing = savedInstanceState?.getBoolean(STATE_FINISHING) == true
        if (savedInstanceState == null && !LocalNetworkPermission.isGranted(this)) {
            localNetworkPermission.launch(LocalNetworkPermission.name)
        }
        enableEdgeToEdge()
        setContent {
            val container = appContainer
            val appSettings by container.appSettings.settings
                .collectAsStateWithLifecycle(initialValue = AppSettings())
            val onboardingViewModel: OnboardingViewModel = viewModel(
                factory = OnboardingViewModel.factory(
                    container.cameras,
                    container.tofuTrustStore,
                    container.protectCredentials,
                    // Application context: the view model outlives this activity.
                    localNetworkGranted = {
                        LocalNetworkPermission.isGranted(applicationContext)
                    },
                    onConsoleChanged = { container.monitoringState.consoleGeneration.value++ },
                ),
            )
            val state by onboardingViewModel.state.collectAsStateWithLifecycle()
            DozecamTheme(nightTheme = appSettings.nightTheme) {
                OnboardingScreen(
                    state = state,
                    onHost = onboardingViewModel::onHost,
                    onUsername = onboardingViewModel::onUsername,
                    onPassword = onboardingViewModel::onPassword,
                    onConnect = onboardingViewModel::connect,
                    onConfirmFingerprint = onboardingViewModel::confirmFingerprint,
                    onRejectFingerprint = { finish() },
                    onToggleCamera = onboardingViewModel::toggleCamera,
                    onImport = onboardingViewModel::import,
                    onClose = { finish() },
                    onFinish = ::finishOnboarding,
                )
            }
        }
    }

    /**
     * Completing onboarding starts monitoring, which is the whole point of
     * having just added cameras. It starts the service here rather than leaving
     * it to the viewer's auto-arm: onboarding can be reached from settings, and
     * finishing there returns to settings, not to the viewer.
     *
     * Clearing the deliberate-stop flag makes this work for a user who had
     * switched monitoring off before adding a console. Backing out of the flow
     * goes through [finish] instead and leaves that decision alone.
     */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_FINISHING, finishing)
    }

    override fun onResume() {
        super.onResume()
        // Recreated while the permission prompt was up: close now that it is
        // answered, rather than leaving the user staring at a finished screen.
        if (finishing) finish()
    }

    private fun finishOnboarding() {
        finishing = true
        appContainer.monitoringState.userStopped.value = false
        lifecycleScope.launch {
            // Asks for the alert grants on the way, which is why this happens
            // here: cameras are in, so the request finally has obvious context.
            // Closing only once the service is actually started, because the
            // permission prompt is asynchronous and finishing first would
            // destroy the launcher waiting for its answer.
            if (appContainer.shouldArmMonitoring(this@OnboardingActivity)) {
                monitoringStarter.startWithAlertPermissions(onStarted = ::finish)
            } else {
                finish()
            }
        }
    }


    companion object {
        private const val STATE_FINISHING = "finishing"

        fun intent(context: Context): Intent = Intent(context, OnboardingActivity::class.java)
    }
}

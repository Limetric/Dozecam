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
import androidx.lifecycle.viewmodel.compose.viewModel
import app.dozecam.appContainer
import app.dozecam.data.AppSettings
import app.dozecam.permissions.LocalNetworkPermission
import app.dozecam.ui.theme.DozecamTheme

class OnboardingActivity : ComponentActivity() {

    // Console setup is the first thing to touch the LAN, and it can be reached
    // without passing through the home screen's request.
    private val localNetworkPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* Denial is surfaced on the connect attempt, which explains it. */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
                )
            }
        }
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, OnboardingActivity::class.java)
    }
}

package app.dozecam.ui.onboarding

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.dozecam.appContainer
import app.dozecam.data.AppSettings
import app.dozecam.ui.theme.DozecamTheme

class OnboardingActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

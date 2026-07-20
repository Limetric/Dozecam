package app.dozecam

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.dozecam.data.AppSettings
import app.dozecam.monitoring.MonitoringService
import app.dozecam.ui.home.HomeRoute
import app.dozecam.ui.home.HomeViewModel
import app.dozecam.ui.monitor.MonitorActivity
import app.dozecam.ui.onboarding.OnboardingActivity
import app.dozecam.ui.settings.SettingsActivity
import app.dozecam.ui.theme.DozecamTheme

class MainActivity : ComponentActivity() {

    private var pendingMonitoringUrl: String? = null

    // Alerts need POST_NOTIFICATIONS; monitoring starts either way, but the
    // full-screen wake only works when the user grants it.
    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        pendingMonitoringUrl?.let { url -> startMonitoring(url) }
        pendingMonitoringUrl = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Survive recreation (e.g. rotation) while the permission dialog is up.
        pendingMonitoringUrl = savedInstanceState?.getString(STATE_PENDING_URL)
        enableEdgeToEdge()
        setContent {
            val container = appContainer
            val appSettings by container.appSettings.settings
                .collectAsStateWithLifecycle(initialValue = AppSettings())
            DozecamTheme(nightTheme = appSettings.nightTheme) {
                val viewModel: HomeViewModel = viewModel(
                    factory = HomeViewModel.factory(
                        container.cameras,
                        container.detectorSettings,
                        container.monitoringState,
                    ),
                )
                HomeRoute(
                    viewModel = viewModel,
                    onWatch = { url -> startActivity(MonitorActivity.intent(this, url)) },
                    onToggleMonitoring = ::setMonitoring,
                    onOpenSettings = { startActivity(SettingsActivity.intent(this)) },
                    onOpenOnboarding = { startActivity(OnboardingActivity.intent(this)) },
                )
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_PENDING_URL, pendingMonitoringUrl)
    }

    private fun setMonitoring(enabled: Boolean, streamUrl: String) {
        if (!enabled) {
            MonitoringService.stop(this)
            return
        }
        if (streamUrl.isBlank()) return
        val needsPermission = Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        if (needsPermission) {
            pendingMonitoringUrl = streamUrl
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startMonitoring(streamUrl)
        }
    }

    private fun startMonitoring(streamUrl: String) {
        ensureFullScreenIntentAccess()
        MonitoringService.start(this, streamUrl)
    }

    /**
     * Android 14+ gates full-screen intents behind special app access that
     * Play-installed apps may not receive by default. Without it the sound
     * alert degrades to a heads-up notification and cannot wake the screen,
     * so send the user straight to the grant screen.
     */
    private fun ensureFullScreenIntentAccess() {
        if (Build.VERSION.SDK_INT < 34) return
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.canUseFullScreenIntent()) return
        startActivity(
            Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
                .setData(Uri.fromParts("package", packageName, null)),
        )
    }

    private companion object {
        const val STATE_PENDING_URL = "pending_monitoring_url"
    }
}

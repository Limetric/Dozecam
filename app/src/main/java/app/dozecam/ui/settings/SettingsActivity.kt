package app.dozecam.ui.settings

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
import app.dozecam.ui.theme.DozecamTheme

class SettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val settingsViewModel: SettingsViewModel = viewModel(
                factory = SettingsViewModel.factory(appContainer.appSettings),
            )
            val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
            DozecamTheme(nightTheme = settings.nightTheme) {
                SettingsScreen(
                    settings = settings,
                    onSettingsChange = settingsViewModel::update,
                    onBack = { finish() },
                )
            }
        }
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, SettingsActivity::class.java)
    }
}

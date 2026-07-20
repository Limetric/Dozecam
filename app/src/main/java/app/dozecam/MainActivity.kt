package app.dozecam

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import app.dozecam.ui.home.HomeRoute
import app.dozecam.ui.home.HomeViewModel
import app.dozecam.ui.monitor.MonitorActivity
import app.dozecam.ui.theme.DozecamTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DozecamTheme {
                val viewModel: HomeViewModel = viewModel(
                    factory = HomeViewModel.factory(appContainer.streamSettings),
                )
                HomeRoute(
                    viewModel = viewModel,
                    onWatch = { url -> startActivity(MonitorActivity.intent(this, url)) },
                )
            }
        }
    }
}

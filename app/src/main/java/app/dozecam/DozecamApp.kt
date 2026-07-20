package app.dozecam

import android.app.Application
import android.content.Context
import app.dozecam.data.AppSettingsRepository
import app.dozecam.data.CameraRepository
import app.dozecam.data.DetectorSettingsRepository
import app.dozecam.data.MonitoringPrefs
import app.dozecam.data.dozecamDataStore
import app.dozecam.monitoring.MonitoringState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class DozecamApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

class AppContainer(context: Context) {
    private val dataStore = context.dozecamDataStore()

    /** For writes that must outlive a component's own scope (e.g. service teardown). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val cameras = CameraRepository(dataStore)
    val detectorSettings = DetectorSettingsRepository(dataStore)
    val appSettings = AppSettingsRepository(dataStore)
    val monitoringPrefs = MonitoringPrefs(dataStore)
    val monitoringState = MonitoringState()
}

val Context.appContainer: AppContainer
    get() = (applicationContext as DozecamApp).container

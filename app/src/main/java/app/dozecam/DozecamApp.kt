package app.dozecam

import android.app.Application
import android.content.Context
import app.dozecam.data.DetectorSettingsRepository
import app.dozecam.data.StreamSettingsRepository
import app.dozecam.data.dozecamDataStore
import app.dozecam.monitoring.MonitoringState

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
    val streamSettings = StreamSettingsRepository(dataStore)
    val detectorSettings = DetectorSettingsRepository(dataStore)
    val monitoringState = MonitoringState()
}

val Context.appContainer: AppContainer
    get() = (applicationContext as DozecamApp).container

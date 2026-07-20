package app.dozecam

import android.app.Application
import android.content.Context
import app.dozecam.data.AppSettingsRepository
import app.dozecam.data.CameraRepository
import app.dozecam.data.DetectorSettingsRepository
import app.dozecam.data.MonitoringPrefs
import app.dozecam.data.dozecamDataStore
import app.dozecam.monitoring.MonitoringState
import app.dozecam.protect.EncryptedCredentialsStore
import app.dozecam.protect.TofuTrustStore
import app.dozecam.protect.securePreferences

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

    /** Stream-token-bearing values live here, encrypted at rest. */
    private val securePrefs = securePreferences(context.applicationContext, "dozecam_secure")
    val cameras = CameraRepository(securePrefs, dataStore)
    val detectorSettings = DetectorSettingsRepository(dataStore)
    val appSettings = AppSettingsRepository(dataStore)
    val monitoringPrefs = MonitoringPrefs(securePrefs)
    val monitoringState = MonitoringState()
    val tofuTrustStore = TofuTrustStore(dataStore)
    val protectCredentials by lazy { EncryptedCredentialsStore(context.applicationContext) }
}

val Context.appContainer: AppContainer
    get() = (applicationContext as DozecamApp).container

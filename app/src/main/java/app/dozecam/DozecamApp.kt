package app.dozecam

import android.app.Application
import android.content.Context
import app.dozecam.audio.MediaAudioFocus
import app.dozecam.data.AppSettingsRepository
import app.dozecam.data.CameraRepository
import app.dozecam.data.DetectorSettingsRepository
import app.dozecam.data.dozecamDataStore
import app.dozecam.monitoring.AlertSignaler
import app.dozecam.monitoring.MonitoringState
import app.dozecam.player.VlcRuntime
import app.dozecam.protect.EncryptedCredentialsStore
import app.dozecam.protect.ProtectLivestreamProvider
import app.dozecam.protect.ProtectPublicApiAccess
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
    val monitoringState = MonitoringState()
    val tofuTrustStore = TofuTrustStore(dataStore)

    /**
     * App-scoped rather than service-owned: the viewer has to be able to
     * acknowledge an alarm the service started, and settings has to be able to
     * preview the sound with no service running at all.
     */
    val alertSignaler by lazy { AlertSignaler(context.applicationContext) }

    /**
     * App-scoped for the same reason [alertSignaler] is, and one degree more
     * so: the viewer and the monitoring service both make noise, they outlive
     * each other in both directions, and two focus requests from one process
     * arrive at each other as losses.
     */
    val audioFocus by lazy { MediaAudioFocus(context.applicationContext) }

    /** Shared by every RTSP tile on screen; builds its native instance on first use. */
    val vlcRuntime = VlcRuntime(context.applicationContext)
    val protectCredentials by lazy { EncryptedCredentialsStore(context.applicationContext) }
    val protectLivestream by lazy {
        ProtectLivestreamProvider(protectCredentials, tofuTrustStore)
    }

    /** The documented Integration API, which is all talk-back needs. */
    val protectPublicApi by lazy {
        ProtectPublicApiAccess(protectCredentials, tofuTrustStore)
    }
}

val Context.appContainer: AppContainer
    get() = (applicationContext as DozecamApp).container

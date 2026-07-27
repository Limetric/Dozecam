package app.dozecam.monitoring

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import app.dozecam.R
import app.dozecam.appContainer
import app.dozecam.audio.SoundDetector
import app.dozecam.data.AppSettings
import app.dozecam.data.Camera
import app.dozecam.data.DetectorSettings
import app.dozecam.data.StreamUrlValidator
import app.dozecam.network.NetworkMonitor
import app.dozecam.player.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Continuous audio monitoring with the display off. Every enabled camera gets
 * its own [CameraAudioMonitor]: an audio-only RTSP session whose decoded PCM
 * levels feed a per-camera [SoundDetector], firing a full-screen wake alert
 * naming whichever camera got loud.
 *
 * The set of monitors follows [app.dozecam.data.CameraStore.enabledCameras]
 * live, so switching a camera on or off in settings takes effect without
 * restarting the service.
 */
class MonitoringService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var signaler: AlertSignaler
    private var appSettings = AppSettings()
    private var detectorSettings = DetectorSettings()
    private var networkOnline = true

    private val monitors = mutableMapOf<String, CameraAudioMonitor>()

    override fun onCreate() {
        super.onCreate()
        MonitoringNotifications.ensureChannels(this)
        ServiceCompat.startForeground(
            this,
            MonitoringNotifications.STATUS_NOTIFICATION_ID,
            MonitoringNotifications.statusNotification(
                this,
                getString(R.string.monitoring_status_starting),
            ),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )

        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dozecam:monitoring")
            .apply { acquire() }

        signaler = AlertSignaler(this)
        appContainer.monitoringState.serviceRunning.value = true
        start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Nothing to carry in the intent: which cameras are monitored is
        // Camera.enabled, which is already durable, so a sticky restart after a
        // process kill resumes exactly the same set.
        return START_STICKY
    }

    private fun start() {
        val container = appContainer

        scope.launch {
            container.appSettings.settings.collect { appSettings = it }
        }

        scope.launch {
            container.detectorSettings.settings.collect { settings ->
                detectorSettings = settings
                monitors.values.forEach { it.updateSettings(settings) }
            }
        }

        val networkMonitor = NetworkMonitor(applicationContext)
        scope.launch {
            networkMonitor.isOnline.collect { online ->
                networkOnline = online
                monitors.values.forEach {
                    if (online) it.onNetworkAvailable() else it.onNetworkLost()
                }
            }
        }

        scope.launch {
            container.cameras.enabledCameras
                .map { cameras -> cameras.filter { StreamUrlValidator.isMonitorable(it.url) } }
                .collect(::reconcile)
        }

        scope.launch {
            combine(
                container.monitoringState.cameras,
                container.cameras.enabledCameras,
            ) { states, enabled -> statusText(states.values, enabled.size) }
                // The camera map changes on every decoded audio buffer, so
                // without this the foreground notification would be rebuilt and
                // reposted hundreds of times a second to say the same thing.
                .distinctUntilChanged()
                .collect(::updateStatusNotification)
        }
    }

    /**
     * Brings the running monitors in line with [wanted]. The decision itself
     * lives in [MonitorPlan]; this only carries it out.
     */
    private fun reconcile(wanted: List<Camera>) {
        val state = appContainer.monitoringState
        val plan = MonitorPlan.of(monitors.mapValues { it.value.camera }, wanted)

        plan.stop.forEach { stopMonitor(it) }

        plan.start.forEach { camera ->
            state.put(CameraMonitorState(cameraId = camera.id, name = camera.name))
            val monitor = CameraAudioMonitor(
                context = this,
                camera = camera,
                scope = scope,
                detectorSettings = detectorSettings,
                onLevel = { rms -> state.update(camera.id) { it.copy(level = rms) } },
                onPhase = { phase -> state.update(camera.id) { it.copy(phase = phase) } },
                onConnection = { connection ->
                    state.update(camera.id) { it.copy(connection = connection) }
                },
                onTrigger = { onTrigger(camera) },
            )
            monitors[camera.id] = monitor
            monitor.start()
            if (!networkOnline) monitor.onNetworkLost()
        }

        // A rename does not disturb a running monitor, but the label the status
        // line and the alert use comes from here, so it still has to follow.
        wanted.filter { it.id in monitors }.forEach { camera ->
            state.update(camera.id) { it.copy(name = camera.name) }
        }

        // Nothing left to listen to (every camera switched off, or none
        // monitorable): drop the wake lock, but stay alive. Stopping here would
        // race a camera switched straight back on — the new set would arrive at
        // a service already on its way to onDestroy, and monitoring would
        // silently stay off. Settings stops the service outright when it is the
        // one that emptied the set.
        holdWakeLock(monitors.isNotEmpty())
    }

    /** Held only while there is audio to decode; a monitor with no cameras costs nothing. */
    private fun holdWakeLock(held: Boolean) {
        val lock = wakeLock ?: return
        if (held && !lock.isHeld) lock.acquire()
        if (!held && lock.isHeld) lock.release()
    }

    private fun stopMonitor(cameraId: String) {
        monitors.remove(cameraId)?.stop()
        appContainer.monitoringState.remove(cameraId)
    }

    private fun onTrigger(camera: Camera) {
        val state = appContainer.monitoringState
        state.lastAlertAtMs.value = System.currentTimeMillis()
        state.lastAlertCameraId.value = camera.id
        // Current name rather than the one captured when this monitor started,
        // so an alert never names a camera by a name the user has since changed.
        val name = state.cameras.value[camera.id]?.name ?: camera.name
        MonitoringNotifications.postAlert(this, camera.id, name)
        signaler.signal(appSettings)
    }

    /**
     * One line for the whole nursery. A triggered camera outranks everything,
     * then the worst connection state wins — a status that said "listening"
     * while a camera was actually offline would be a lie in the one direction
     * that matters.
     */
    private fun statusText(states: Collection<CameraMonitorState>, enabledCount: Int): String {
        if (monitors.isEmpty()) return getString(R.string.monitoring_status_nothing)
        if (states.isEmpty()) return getString(R.string.monitoring_status_starting)
        states.firstOrNull { it.phase == SoundDetector.Phase.TRIGGERED }?.let {
            return getString(R.string.monitoring_status_alerting, it.name)
        }
        val offline = states.filter { it.connection is ConnectionState.Offline }
        if (offline.isNotEmpty()) return getString(R.string.monitoring_status_offline)
        val reconnecting = states.filter { it.connection is ConnectionState.Reconnecting }
        if (reconnecting.isNotEmpty()) {
            return resources.getQuantityString(
                R.plurals.monitoring_status_reconnecting_cameras,
                reconnecting.size,
                reconnecting.size,
            )
        }
        if (states.any { it.connection is ConnectionState.Connecting }) {
            return getString(R.string.monitoring_status_starting)
        }
        return resources.getQuantityString(
            R.plurals.monitoring_status_listening_cameras,
            states.size,
            states.size,
        ).let { text ->
            // A camera that is enabled but not monitorable is silently absent
            // from the listening count; say so rather than overstate coverage.
            if (enabledCount > states.size) {
                getString(R.string.monitoring_status_partial, text, enabledCount - states.size)
            } else {
                text
            }
        }
    }

    private fun updateStatusNotification(text: String) {
        try {
            NotificationManagerCompat.from(this).notify(
                MonitoringNotifications.STATUS_NOTIFICATION_ID,
                MonitoringNotifications.statusNotification(this, text),
            )
        } catch (_: SecurityException) {
            // FGS notification stays as posted at startForeground time.
        }
    }

    override fun onDestroy() {
        val state = appContainer.monitoringState
        state.serviceRunning.value = false
        monitors.values.forEach { it.stop() }
        monitors.clear()
        state.clear()
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        fun start(context: Context) {
            context.startForegroundService(Intent(context, MonitoringService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MonitoringService::class.java))
        }
    }
}

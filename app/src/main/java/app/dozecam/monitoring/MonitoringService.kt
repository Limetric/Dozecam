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
import app.dozecam.audio.MediaAudioFocus
import app.dozecam.audio.SoundDetector
import app.dozecam.data.AppSettings
import app.dozecam.data.Camera
import app.dozecam.data.DetectorSettings
import app.dozecam.network.NetworkMonitor
import app.dozecam.player.StreamSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Continuous audio monitoring with the display off. Every enabled camera gets
 * its own [CameraAudioMonitor]: an audio-only session whose decoded PCM levels
 * feed a per-camera [SoundDetector], firing a full-screen wake alert naming
 * whichever camera got loud.
 *
 * A camera is monitored if there is any way at all to hear it — see
 * [MonitorTransports] — and skipped, visibly, if there is not.
 *
 * The set of monitors follows [app.dozecam.data.CameraStore.enabledCameras]
 * live, so switching a camera on or off in settings takes effect without
 * restarting the service.
 *
 * Listen mode is the same decoding, turned up. One monitor at a time may play
 * its camera out of the speaker so the nursery stays audible with the display
 * off — which is what makes this service's `mediaPlayback` type an honest
 * description of it rather than a borderline one. The speaker is held here
 * rather than by the viewer for the same reason: the viewer is the thing that
 * is not there at 3am.
 */
class MonitoringService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wakeLock: PowerManager.WakeLock? = null
    private var appSettings = AppSettings()
    private var detectorSettings = DetectorSettings()
    private var networkOnline = true

    private val monitors = mutableMapOf<String, CameraAudioMonitor>()

    private val heartbeat = StatusHeartbeat()

    /** What each running monitor was built with, to notice when that stops being true. */
    private val monitorTransports = mutableMapOf<String, List<StreamSource>>()

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
            combine(
                container.cameras.enabledCameras,
                container.monitoringState.consoleGeneration,
            ) { cameras, _ -> cameras }
                .map { cameras ->
                    // Resolved per emission rather than once: signing in to a
                    // different console while the service runs changes which
                    // cameras have a livestream to fall back on.
                    val usable = transportsFor(cameras, container.protectCredentials)
                    cameras.filter { it.id in usable } to usable
                }
                .collect { (cameras, usable) -> reconcile(cameras, usable) }
        }

        // Listen mode's switch, and the speaker it needs. Asked for here rather
        // than in the viewer because the whole promise is that it outlives the
        // viewer.
        scope.launch {
            container.monitoringState.listenRequest
                // Only whether a room is wanted, not which: moving the speaker
                // from one camera to another is not a reason to hand the focus
                // back and ask the system for it again.
                .map { it != null }
                .distinctUntilChanged()
                .collect { wanted ->
                    if (!wanted) {
                        container.audioFocus.release(MediaAudioFocus.Client.LISTEN)
                        return@collect
                    }
                    // Losing it later is treated the same way as being refused
                    // it now: the switch goes back off rather than standing on
                    // next to a phone that has gone quiet.
                    val granted = container.audioFocus.request(MediaAudioFocus.Client.LISTEN) {
                        container.monitoringState.stopListening()
                    }
                    if (!granted) container.monitoringState.stopListening()
                }
        }

        // Who is actually audible, recomputed from every reason it could stop
        // being true. Only one camera is ever named, so moving the target moves
        // the sound instead of adding to it.
        scope.launch {
            combine(
                // The room and the switch are one value, so the service cannot
                // learn that listening has begun before it learns what to play.
                container.monitoringState.listenRequest,
                container.audioFocus.granted,
                container.monitoringState.viewerAudible,
                // The map itself churns with every decoded buffer; which
                // cameras are in it does not.
                container.monitoringState.cameras
                    .map { it.keys }
                    .distinctUntilChanged(),
            ) { request, granted, viewerAudible, monitored ->
                ListenTarget.of(request, granted, viewerAudible, monitored)
            }
                .distinctUntilChanged()
                .collect(::setListenTarget)
        }

        scope.launch {
            combine(
                container.monitoringState.cameras,
                container.cameras.enabledCameras,
                container.monitoringState.listeningCameraId,
                // The camera map changes on almost every decoded audio buffer —
                // but not on every one: a run of identical levels (digital
                // silence, exactly 0f) is conflated away by the StateFlow, and
                // with no emissions the minute stamp would never roll over. The
                // tick keeps evaluation time-driven as well, which is the whole
                // point of a heartbeat; a wedged process runs no ticker, so it
                // still cannot fake one.
                heartbeatTicks(),
            ) { states, enabled, aloudCameraId, _ ->
                MonitoringStatus.of(
                    context = this@MonitoringService,
                    anyMonitors = monitors.isNotEmpty(),
                    states = states.values,
                    enabledCount = enabled.size,
                    // A phone quietly broadcasting a bedroom is exactly the
                    // thing a persistent notification exists to disclose, so
                    // the line says so whatever else it has to report.
                    aloudCameraId = aloudCameraId,
                )
            }
                // Unmetered this would rebuild and repost the foreground
                // notification hundreds of times a second. The heartbeat lets
                // through text changes at once, level motion a few seconds
                // apart, and — when the room is silent — one post a minute, so
                // the shade visibly breathes without ever lying about it.
                .collect { status ->
                    heartbeat.offer(status.text, status.level)?.let(::updateStatusNotification)
                }
        }
    }

    private fun heartbeatTicks() = flow {
        while (true) {
            emit(Unit)
            delay(StatusHeartbeat.MIN_INTERVAL_MS)
        }
    }

    /**
     * Brings the running monitors in line with [wanted]. The decision itself
     * lives in [MonitorPlan]; this only carries it out.
     */
    private fun reconcile(wanted: List<Camera>, transports: Map<String, List<StreamSource>>) {
        val state = appContainer.monitoringState

        // A camera can keep every field it has and still need a new monitor:
        // signing in to another console takes the livestream away from it, and
        // a monitor holding the old list would go on negotiating a camera id
        // that console has never heard of. Retired here so the plan below sees
        // them as absent and builds them again with what is true now.
        monitors.keys
            .filter { transports[it] != monitorTransports[it] }
            .toList()
            .forEach { stopMonitor(it) }

        val plan = MonitorPlan.of(monitors.mapValues { it.value.camera }, wanted)

        plan.stop.forEach { stopMonitor(it) }

        plan.start.forEach { camera ->
            state.put(CameraMonitorState(cameraId = camera.id, name = camera.name))
            val monitor = CameraAudioMonitor(
                context = this,
                camera = camera,
                transports = transports[camera.id].orEmpty(),
                livestreamProvider = appContainer.protectLivestream,
                scope = scope,
                detectorSettings = detectorSettings,
                onLevel = { rms -> state.update(camera.id) { it.copy(level = rms) } },
                onPhase = { phase -> state.update(camera.id) { it.copy(phase = phase) } },
                onConnection = { connection ->
                    state.update(camera.id) { it.withConnection(connection) }
                },
                onTrigger = { onTrigger(camera) },
            )
            monitors[camera.id] = monitor
            monitorTransports[camera.id] = transports[camera.id].orEmpty()
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

        // The set just changed underneath listen mode. A monitor rebuilt onto
        // another transport comes back with a fresh, silent player, and the
        // flow above would not fire for it — the camera ids it watches are the
        // same ones. Re-applied here so the speaker follows the target across a
        // rebuild rather than going quiet mid-night with the switch still on.
        applyListenTarget()
    }

    /**
     * Points the speaker at [cameraId], or at nothing. Recorded before it is
     * applied, because everything that discloses listen mode — the status line,
     * the offer to stop, the decision not to light the screen for an alert —
     * reads the record rather than the players.
     */
    private fun setListenTarget(cameraId: String?) {
        appContainer.monitoringState.listeningCameraId.value = cameraId
        applyListenTarget()
    }

    /**
     * Exactly one monitor audible, every other one silent — stated over the
     * whole set rather than as a hand-off, so no path through here can leave
     * two rooms coming out of the speaker at once.
     */
    private fun applyListenTarget() {
        val target = appContainer.monitoringState.listeningCameraId.value
        monitors.forEach { (id, monitor) -> monitor.setAudible(id == target) }
    }

    /** Held only while there is audio to decode; a monitor with no cameras costs nothing. */
    private fun holdWakeLock(held: Boolean) {
        val lock = wakeLock ?: return
        if (held && !lock.isHeld) lock.acquire()
        if (!held && lock.isHeld) lock.release()
    }

    private fun stopMonitor(cameraId: String) {
        monitorTransports.remove(cameraId)
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
        // Notification first, always: the full-screen intent is the fastest
        // signal there is, and sound is for the person whose eyes are shut.
        //
        // Except for the room already coming out of the speaker. Whoever
        // switched listen mode on is being told about that room continuously,
        // in the most direct way there is; lighting a bedroom at 3am on top of
        // it wakes the parent who is already listening, and the one beside
        // them. The chime and the vibration still go, because the point of an
        // alert is the person whose eyes are shut — and every *other* camera
        // still wakes the screen, because a room nobody can hear is exactly
        // what the full-screen view is for.
        val alreadyHeard = state.listeningCameraId.value == camera.id
        MonitoringNotifications.postAlert(this, camera.id, name, wakeScreen = !alreadyHeard)
        appContainer.alertSignaler.signal(camera.id, appSettings)
    }

    private fun updateStatusNotification(display: StatusHeartbeat.Display) {
        try {
            NotificationManagerCompat.from(this).notify(
                MonitoringNotifications.STATUS_NOTIFICATION_ID,
                MonitoringNotifications.statusNotification(
                    this,
                    display.text,
                    display.levelBucket,
                    display.checkedAtMs,
                    aloud = appContainer.monitoringState.listeningCameraId.value != null,
                ),
            )
        } catch (_: SecurityException) {
            // FGS notification stays as posted at startForeground time.
        }
    }

    override fun onDestroy() {
        val state = appContainer.monitoringState
        state.serviceRunning.value = false
        // The speaker goes with the monitor that was feeding it. Released here
        // rather than left to the flow above, which is about to be cancelled
        // with the scope — an abandoned focus request would leave every other
        // app on the phone ducked for a service that no longer exists.
        state.stopListening()
        appContainer.audioFocus.release(MediaAudioFocus.Client.LISTEN)
        // Monitoring ending takes its alert with it: an alarm still sounding for
        // a camera nobody is listening to any more has nothing left to mean —
        // and neither does the card offering to open a live view of it.
        appContainer.alertSignaler.stop()
        NotificationManagerCompat.from(this)
            .cancel(MonitoringNotifications.ALERT_NOTIFICATION_ID)
        monitors.values.forEach { it.stop() }
        monitors.clear()
        monitorTransports.clear()
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

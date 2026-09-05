package app.dozecam.monitoring

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.dozecam.R
import app.dozecam.appContainer
import app.dozecam.audio.MediaAudioFocus
import app.dozecam.audio.SoundDetector
import app.dozecam.data.AppSettings
import app.dozecam.data.Camera
import app.dozecam.data.DetectorSettings
import app.dozecam.data.SoundMode
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
 * Listen mode is the same decoding, turned up. Every monitor may play its
 * camera out of the speaker at once so the whole house stays audible with the
 * display off — which is what makes this service's `mediaPlayback` type an
 * honest description of it rather than a borderline one. The speaker is held
 * here rather than by the viewer for the same reason: the viewer is the thing
 * that is not there at 3am.
 */
class MonitoringService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wakeLock: PowerManager.WakeLock? = null
    private var appSettings = AppSettings()
    private var detectorSettings = DetectorSettings()
    private var networkOnline = true

    private val monitors = mutableMapOf<String, CameraAudioMonitor>()

    /** What was being heard when [escalateUnheard] last looked; the diff is the escalation. */
    private var heardBefore: Set<String> = emptySet()

    /**
     * A room can stop being heard without the aloud set moving at all: the
     * media volume goes to zero, or the stream is muted. Nothing upstream
     * re-evaluates on that, so this does.
     */
    private val volumeChanged = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = escalateUnheard()
    }

    private val heartbeat = StatusHeartbeat()

    /** What each running monitor was built with, to notice when that stops being true. */
    private val monitorTransports = mutableMapOf<String, List<StreamSource>>()

    override fun onCreate() {
        super.onCreate()
        MonitoringNotifications.ensureChannels(this)
        ContextCompat.registerReceiver(
            this,
            volumeChanged,
            IntentFilter().apply {
                addAction(ACTION_VOLUME_CHANGED)
                addAction(ACTION_STREAM_MUTE_CHANGED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
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
        // Which cameras are monitored is Camera.enabled, which is already
        // durable, so a sticky restart after a process kill resumes exactly the
        // same set — and a redelivered null intent is what makes the one action
        // below safe to carry here: a test alert is a thing a person asked for
        // once, and must never be replayed at 3am by a service coming back.
        // Raised here and now, from the same field and down the same call as a
        // room that actually got loud. Reading the stored settings afresh first
        // would be more careful and less honest: the 3am path does not do that,
        // and a test that behaves differently from the thing it is testing has
        // nothing to say about it.
        if (intent?.action == ACTION_TEST_ALERT) {
            // Never over a room that is actually crying. There is one alert
            // card and one alarm, and a test that took them would replace a
            // real notification with the word "test" and hand the parent a
            // dismissal that acknowledges an alert they never saw. The card in
            // settings is not going anywhere; the room is the urgent thing.
            if (!appContainer.roomIsCrying()) {
                raiseAlert(TEST_CAMERA_ID, getString(R.string.alert_test_title), test = true)
            }
        }
        return START_STICKY
    }

    private fun start() {
        val container = appContainer

        scope.launch {
            container.appSettings.settings.collect { settings ->
                appSettings = settings
                // Alerts switched off while one was sounding: the alarm and the
                // card offering to open the room have nothing left to mean, so
                // they go now rather than ringing on for a setting that says
                // nobody wants them.
                if (!settings.alertsEnabled) dropAlert()
            }
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
        val listenRequested = container.appSettings.settings
            .map { it.soundMode == SoundMode.ALL_ALOUD }
            .distinctUntilChanged()
        scope.launch {
            listenRequested.collect { wanted ->
                if (!wanted) {
                    container.audioFocus.release(MediaAudioFocus.Client.LISTEN)
                    return@collect
                }
                // Losing it later is treated the same way as being refused it
                // now: the switch goes back off rather than standing on next
                // to a phone that has gone quiet.
                val granted = container.audioFocus.request(MediaAudioFocus.Client.LISTEN) {
                    stopListening()
                }
                if (!granted) stopListening()
            }
        }

        // Who is actually audible, recomputed from every reason it could stop
        // being true.
        scope.launch {
            combine(
                listenRequested,
                container.audioFocus.granted,
                container.monitoringState.viewerAudible,
                // Only rooms with audio actually coming through: a live
                // stream that has decoded at least one buffer on it. A monitor
                // still connecting, reconnecting, or offline has nothing to
                // turn up, and neither does a transport that plays but never
                // yields a sample — which is what an undecodable stream looks
                // like until the fallback abandons it. Counting either would
                // make everything downstream overstate: the notification would
                // claim two rooms aloud with one of them dark, and an alert
                // from the one room that *is* playing would light the screen
                // to name it, as though there were another it could be confused
                // with. The map itself churns with every decoded buffer; which
                // cameras are audible does not.
                container.monitoringState.cameras
                    .map { states -> states.filterValues { it.isAudible }.keys }
                    .distinctUntilChanged(),
            ) { requested, granted, viewerAudible, monitored ->
                ListenTarget.of(requested, granted, viewerAudible, monitored)
            }
                .distinctUntilChanged()
                .collect(::setListenTarget)
        }

        scope.launch {
            combine(
                container.monitoringState.cameras,
                container.cameras.enabledCameras,
                container.monitoringState.listeningCameraIds,
                container.appSettings.settings.map { it.alertsEnabled }.distinctUntilChanged(),
                // The camera map changes on almost every decoded audio buffer —
                // but not on every one: a run of identical levels (digital
                // silence, exactly 0f) is conflated away by the StateFlow, and
                // with no emissions the minute stamp would never roll over. The
                // tick keeps evaluation time-driven as well, which is the whole
                // point of a heartbeat; a wedged process runs no ticker, so it
                // still cannot fake one.
                heartbeatTicks(),
            ) { states, enabled, aloudCameraIds, alertsEnabled, _ ->
                MonitoringStatus.of(
                    context = this@MonitoringService,
                    anyMonitors = monitors.isNotEmpty(),
                    states = states.values,
                    enabledCount = enabled.size,
                    // A phone quietly broadcasting a bedroom is exactly the
                    // thing a persistent notification exists to disclose, so
                    // the line says so whatever else it has to report — and so
                    // is a monitor that will not wake anyone.
                    aloudCameraIds = aloudCameraIds,
                    alertsEnabled = alertsEnabled,
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

    /**
     * The moment a buffer was decoded, rounded down to the second.
     *
     * A room in digital silence emits an unbroken run of identical levels, and
     * the StateFlow conflates those away — which is what keeps the status
     * notification from being rebuilt hundreds of times a second, and is stated
     * as a load-bearing fact where the heartbeat is set up below. A raw
     * millisecond stamp alongside the level would make every one of those
     * emissions distinct and quietly undo it. A second is far finer than
     * [Readiness.AUDIO_STALE_MS] needs and coarse enough to keep the conflation.
     */
    private fun coarse(atMs: Long): Long = atMs - atMs % 1_000L

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
                onLevel = { rms, atMs ->
                    state.update(camera.id) {
                        it.copy(level = rms, lastAudioAtMs = coarse(atMs))
                    }
                },
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

        // Nothing left to hear does not switch the sound mode off. It is the
        // viewer's setting as much as this service's, and the viewer can play
        // rooms this service cannot listen to; the notification, which reads
        // [MonitoringState.listeningCameraIds] rather than the ask, already
        // admits to nothing being aloud.

        // The set just changed underneath listen mode. A monitor rebuilt onto
        // another transport comes back with a fresh, silent player, and the
        // flow above would not fire for it — the camera ids it watches are the
        // same ones. Re-applied here so the speaker follows the target across a
        // rebuild rather than going quiet mid-night with the switch still on.
        applyListenTarget()
    }

    /**
     * Points the speaker at [cameraIds], or at nothing. Recorded before it is
     * applied, because everything that discloses listen mode — the status line,
     * the offer to stop, the decision whether to light the screen for an alert
     * — reads the record rather than the players.
     */
    private fun setListenTarget(cameraIds: Set<String>) {
        appContainer.monitoringState.listeningCameraIds.value = cameraIds
        applyListenTarget()
        escalateUnheard()
    }

    /**
     * A room that was being heard when its cry began had its alarm withheld.
     * If it is no longer being heard — the speaker gone to a call, the viewer,
     * or a stream down; the volume turned to zero — and the cry has not paused
     * long enough for the detector to re-arm, no second trigger is coming; the
     * withheld alarm is raised here instead, or an unheard room could stay
     * silent for as long as the crying lasts.
     *
     * Diffed against the service's own record rather than the shared one:
     * stopListening() clears that synchronously from wherever the speaker was
     * lost, before this gets its turn, and a diff against an already-empty set
     * would find nothing to escalate.
     */
    private fun escalateUnheard() {
        val state = appContainer.monitoringState
        val heard = heardAloud()
        val lost = heardBefore - heard
        heardBefore = heard
        lost.forEach { id ->
            val camera = state.cameras.value[id] ?: return@forEach
            if (camera.phase == SoundDetector.Phase.TRIGGERED) raiseAlert(id, camera.name)
        }
    }

    /**
     * The speaker was refused or taken away for good. The switch goes back to
     * off rather than standing on next to a phone that has gone quiet — and
     * the record is emptied at once, before the setting has travelled back
     * down its flow, so nothing reads a room as aloud in between.
     */
    private fun stopListening() {
        appContainer.monitoringState.listeningCameraIds.value = emptySet()
        scope.launch {
            appContainer.appSettings.update { it.copy(soundMode = SoundMode.OFF) }
        }
    }

    /**
     * Stated over the whole set rather than per change, so no path through
     * here can leave a monitor audible that the record says is silent.
     */
    private fun applyListenTarget() {
        val target = appContainer.monitoringState.listeningCameraIds.value
        monitors.forEach { (id, monitor) -> monitor.setAudible(id in target) }
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

    private fun onTrigger(camera: Camera) = raiseAlert(camera.id, camera.name)

    /**
     * The rooms being heard right now, for an alert to weigh itself against:
     * the aloud set, unless the media stream it plays on is at zero or muted,
     * in which case nobody is hearing any of it. See [ListenTarget.heard].
     */
    private fun heardAloud(): Set<String> {
        val audio = getSystemService(AudioManager::class.java)
        val silenced = audio.getStreamVolume(AudioManager.STREAM_MUSIC) == 0 ||
            audio.isStreamMute(AudioManager.STREAM_MUSIC)
        return ListenTarget.heard(appContainer.monitoringState.listeningCameraIds.value, silenced)
    }

    /**
     * [test] changes nothing about how the alert is raised, and everything
     * about what it says. That is the whole point of the bedtime test: it fires
     * the real notification, through the real channel, with the real
     * full-screen intent and the real alarm, so whichever of the readiness
     * checks is quietly failing fails here too, in daylight, where it can be
     * fixed. Only the words differ — see [MonitoringNotifications.alertNotification]
     * — because an alert nobody can tell from a real one is a bad way to spend
     * a parent's adrenaline.
     *
     * It needs no special case in any of the rules below: [TEST_CAMERA_ID] is
     * no room the speaker is playing, so listen mode neither silences it nor
     * withholds it, which is exactly the treatment a test should get.
     */
    private fun raiseAlert(cameraId: String, fallbackName: String, test: Boolean = false) {
        // Nothing reaches anyone: no card, no screen, no alarm. The detector
        // still ran — the meters and the status line say the room is loud —
        // but the user asked not to be told, and this is where that is kept.
        if (!appSettings.alertsEnabled) return
        val state = appContainer.monitoringState
        val aloud = heardAloud()
        // A room being heard does not displace the alarm of one that is not:
        // there is one alert card, and clearing it acknowledges whatever alarm
        // is sounding. See ListenTarget.alertYields.
        //
        // A test alarm earns none of that protection. It is a thing somebody
        // asked for in daylight, standing in front of the phone; yielding a
        // real room's alert to it would drop the alert entirely, which is the
        // exact opposite of what this rule exists to do.
        val alarming = appContainer.alertSignaler.alarmingCameraId.value
            ?.takeIf { it != TEST_CAMERA_ID }
        if (ListenTarget.alertYields(cameraId, aloud, alarming)) {
            return
        }
        state.lastAlertAtMs.value = System.currentTimeMillis()
        state.lastAlertCameraId.value = cameraId
        // Current name rather than the one captured when this monitor started,
        // so an alert never names a camera by a name the user has since changed.
        val name = state.cameras.value[cameraId]?.name ?: fallbackName
        // Notification first, always: the full-screen intent is the fastest
        // signal there is, and sound is for the person whose eyes are shut.
        //
        // Whether it also lights the screen, and whether the alarm sounds at
        // all, depend on what the speaker is already saying — see
        // ListenTarget.alertWakesScreen and ListenTarget.alertSounds. A room
        // playing aloud is being heard by someone awake; the alarm is for the
        // person whose eyes are shut.
        // The test is over: a real room has the card now. Its alarm would
        // otherwise ring on behind a notification naming a nursery — and where
        // that room is playing aloud, no alarm is due at all, so nothing would
        // replace the test's and it would simply run to its cap.
        if (!test && appContainer.alertSignaler.alarmingCameraId.value == TEST_CAMERA_ID) {
            appContainer.alertSignaler.stop()
        }
        val wakeScreen = ListenTarget.alertWakesScreen(cameraId, aloud)
        MonitoringNotifications.postAlert(
            context = this,
            cameraId = cameraId,
            cameraName = name,
            wakeScreen = wakeScreen,
            test = test,
        )
        if (ListenTarget.alertSounds(cameraId, aloud)) {
            appContainer.alertSignaler.signal(cameraId, appSettings)
        }
    }

    /** Silences whatever alert is up and takes its card down. */
    private fun dropAlert() {
        appContainer.alertSignaler.stop()
        NotificationManagerCompat.from(this)
            .cancel(MonitoringNotifications.ALERT_NOTIFICATION_ID)
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
                    aloud = appContainer.monitoringState.listeningCameraIds.value.isNotEmpty(),
                ),
            )
        } catch (_: SecurityException) {
            // FGS notification stays as posted at startForeground time.
        }
    }

    override fun onDestroy() {
        unregisterReceiver(volumeChanged)
        val state = appContainer.monitoringState
        state.serviceRunning.value = false
        // The speaker goes with the monitor that was feeding it. Released here
        // rather than left to the flow above, which is about to be cancelled
        // with the scope — an abandoned focus request would leave every other
        // app on the phone ducked for a service that no longer exists. The
        // setting itself is left alone: this is an exit, and the sound mode is
        // meant to be found again the way it was left.
        state.listeningCameraIds.value = emptySet()
        appContainer.audioFocus.release(MediaAudioFocus.Client.LISTEN)
        // Monitoring ending takes its alert with it: an alarm still sounding for
        // a camera nobody is listening to any more has nothing left to mean —
        // and neither does the card offering to open a live view of it.
        dropAlert()
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
        /** AudioManager's own, not in the public API. */
        private const val ACTION_VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION"
        private const val ACTION_STREAM_MUTE_CHANGED = "android.media.STREAM_MUTE_CHANGED_ACTION"

        private const val ACTION_TEST_ALERT = "app.dozecam.action.TEST_ALERT"

        /**
         * The camera id a test alert is raised for. Deliberately one no camera
         * can have — ids are UUIDs, or `protect-…` — so nothing that keys off a
         * camera id can confuse the test with a room: the viewer finds no such
         * camera and takes the alert straight back down, and listen mode, which
         * decides what to silence by id, never counts it as a room being heard.
         */
        const val TEST_CAMERA_ID = "dozecam-test-alert"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, MonitoringService::class.java))
        }

        /**
         * Fires the bedtime test: the real alert, from the real service, down
         * the same path a crying room takes.
         *
         * Sent to the service rather than posted from the caller's screen
         * because the path that has to work at 3am starts in the service, and a
         * test that skipped it would prove the wrong thing — settings holds no
         * wake lock, has no foreground-service notification, and would be gone
         * by the time the alarm was due to repeat.
         */
        fun testAlert(context: Context) {
            context.startForegroundService(
                Intent(context, MonitoringService::class.java).setAction(ACTION_TEST_ALERT),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MonitoringService::class.java))
        }
    }
}

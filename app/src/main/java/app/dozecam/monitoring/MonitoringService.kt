package app.dozecam.monitoring

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import app.dozecam.R
import app.dozecam.appContainer
import app.dozecam.audio.PcmRms
import app.dozecam.audio.SoundDetector
import app.dozecam.data.AppSettings
import app.dozecam.data.DetectorSettings
import app.dozecam.data.StreamUrlValidator
import app.dozecam.player.ConnectionState
import app.dozecam.player.PlayerEvent
import app.dozecam.network.NetworkMonitor
import app.dozecam.player.PlaybackWatchdog
import java.nio.ByteBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Continuous audio monitoring with the display off: consumes the RTSP
 * stream's audio track only, feeds decoded PCM levels into [SoundDetector],
 * and fires a full-screen wake alert when the nursery gets loud.
 */
class MonitoringService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var player: ExoPlayer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var watchdog: PlaybackWatchdog
    private lateinit var detector: SoundDetector
    private lateinit var signaler: AlertSignaler
    private var appSettings = AppSettings()
    private var streamUrl: String = ""

    // Audio callbacks land on the playback thread; ship (levelRms, atMs)
    // pairs to the main thread for the detector and UI.
    private val levels = MutableSharedFlow<Pair<Float, Long>>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private val levelSink = object : TeeAudioProcessor.AudioBufferSink {
        override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) = Unit

        override fun handleBuffer(buffer: ByteBuffer) {
            levels.tryEmit(PcmRms.of(buffer) to SystemClock.elapsedRealtime())
        }
    }

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

        detector = SoundDetector(DetectorSettings())
        signaler = AlertSignaler(this)
        watchdog = PlaybackWatchdog(
            scope = scope,
            onReconnect = { restartStream() },
        )

        appContainer.monitoringState.serviceRunning.value = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (player == null) {
            // START_STICKY restarts redeliver a null intent; fall back to the
            // persisted URL then.
            val urlExtra = intent?.getStringExtra(EXTRA_STREAM_URL)
            scope.launch { startMonitoring(urlExtra) }
        }
        return START_STICKY
    }

    private suspend fun startMonitoring(urlExtra: String?) {
        val container = appContainer
        streamUrl = urlExtra?.takeIf { it.isNotBlank() }
            ?: run {
                // Ensure the legacy-storage migration has run before reading
                // the persisted URL, then resume the camera this service was
                // watching — not whatever the user selected in the meantime.
                container.cameras.cameras.first()
                container.monitoringPrefs.activeMonitoringUrl()
                    ?: container.cameras.selectedCamera.first()?.url.orEmpty()
            }
        if (!StreamUrlValidator.isMonitorable(streamUrl)) {
            // Blank, or an rtsps camera: Media3's RTSP stack cannot do TLS.
            stopSelf()
            return
        }
        container.monitoringPrefs.setActiveMonitoringUrl(streamUrl)

        scope.launch {
            container.detectorSettings.settings.collect { detector.updateSettings(it) }
        }

        scope.launch {
            container.appSettings.settings.collect { appSettings = it }
        }

        val networkMonitor = NetworkMonitor(applicationContext)
        scope.launch {
            networkMonitor.isOnline.collect { online ->
                if (online) watchdog.onNetworkAvailable() else watchdog.onNetworkLost()
            }
        }

        scope.launch {
            levels.collect { (rms, atMs) ->
                container.monitoringState.audioLevel.value = rms
                watchdog.onPlayerEvent(PlayerEvent.TimeChanged(atMs))
                if (detector.onLevel(rms, atMs)) {
                    container.monitoringState.lastAlertAtMs.value = System.currentTimeMillis()
                    MonitoringNotifications.postAlert(this@MonitoringService, streamUrl)
                    signaler.signal(appSettings)
                }
                container.monitoringState.detectorPhase.value = detector.phase
            }
        }

        scope.launch {
            combine(
                watchdog.state,
                container.monitoringState.detectorPhase,
            ) { connection, phase -> statusText(connection, phase) }
                .collect { text ->
                    container.monitoringState.connection.value = watchdog.state.value
                    updateStatusNotification(text)
                }
        }

        player = buildPlayer().also { exo ->
            exo.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (isPlaying) watchdog.onPlayerEvent(PlayerEvent.Playing)
                }

                override fun onPlayerError(error: PlaybackException) {
                    watchdog.onPlayerEvent(PlayerEvent.Error)
                }
            })
            watchdog.start()
            exo.setMediaSource(mediaSourceFor(streamUrl))
            exo.prepare()
            exo.play()
        }
    }

    private fun buildPlayer(): ExoPlayer {
        val renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink = DefaultAudioSink.Builder(context)
                .setAudioProcessors(arrayOf<AudioProcessor>(TeeAudioProcessor(levelSink)))
                .build()
        }
        return ExoPlayer.Builder(this, renderersFactory).build().apply {
            trackSelectionParameters = trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                .build()
            volume = 0f // monitor silently; the wake alert surfaces the live view
        }
    }

    private fun mediaSourceFor(url: String) =
        RtspMediaSource.Factory()
            .setForceUseRtpTcp(true)
            .createMediaSource(MediaItem.fromUri(url))

    private fun restartStream() {
        val exo = player ?: return
        exo.stop()
        exo.setMediaSource(mediaSourceFor(streamUrl))
        exo.prepare()
        exo.play()
    }

    private fun statusText(connection: ConnectionState, phase: SoundDetector.Phase): String =
        when (connection) {
            is ConnectionState.Live -> when (phase) {
                SoundDetector.Phase.TRIGGERED -> getString(R.string.monitoring_status_alerting)
                else -> getString(R.string.monitoring_status_listening)
            }
            is ConnectionState.Connecting -> getString(R.string.monitoring_status_starting)
            is ConnectionState.Reconnecting -> getString(R.string.monitoring_status_reconnecting)
            is ConnectionState.Offline -> getString(R.string.monitoring_status_offline)
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
        // Deliberate stop: a later sticky restart must not resurrect this URL.
        // (After a process kill onDestroy never runs, which is exactly when
        // the persisted URL should survive.)
        appContainer.monitoringPrefs.clearActiveMonitoringUrl()
        appContainer.monitoringState.serviceRunning.value = false
        appContainer.monitoringState.audioLevel.value = 0f
        watchdog.stop()
        player?.release()
        player = null
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val EXTRA_STREAM_URL = "stream_url"

        fun start(context: Context, streamUrl: String) {
            context.startForegroundService(
                Intent(context, MonitoringService::class.java)
                    .putExtra(EXTRA_STREAM_URL, streamUrl),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MonitoringService::class.java))
        }
    }
}

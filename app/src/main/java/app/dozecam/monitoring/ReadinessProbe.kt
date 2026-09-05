package app.dozecam.monitoring

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibratorManager
import androidx.core.app.NotificationManagerCompat
import app.dozecam.data.AppSettingsStore
import app.dozecam.data.Camera
import app.dozecam.data.CameraStore
import app.dozecam.permissions.LocalNetworkPermission
import app.dozecam.protect.CredentialsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.delay

/**
 * Where the bedtime check gets its facts. [Readiness] decides what they mean;
 * this only looks.
 *
 * Split that way because almost none of this is observable: a permission, a
 * ringer, a Do Not Disturb profile and a battery optimisation exemption are all
 * changed in Android's own settings, with nothing delivered back to us when
 * they are. So the device half is *read*, on a slow tick and again whenever the
 * app-side half moves, rather than collected — which also means the card
 * corrects itself a second or two after the user returns from the very settings
 * screen a remedy sent them to.
 */
class ReadinessProbe(
    private val context: Context,
    private val monitoringState: MonitoringState,
    private val appSettings: AppSettingsStore,
    private val cameras: CameraStore,
    /**
     * Only read when some camera's answer could depend on it — see
     * [consoleHostFor] — so a house of plain RTSP cameras never decrypts
     * anything to answer this.
     */
    private val credentials: CredentialsStore,
    /**
     * How often the unobservable half is re-read. Slow enough to cost nothing
     * while a settings screen is open, quick enough that coming back from
     * Android's settings does not look like the remedy failed.
     */
    private val tickMs: Long = TICK_MS,
) {

    /**
     * The whole checklist, live for as long as it is collected.
     *
     * The per-camera map churns with every decoded audio buffer, so only the
     * two things this cares about are taken from it — and its timestamps are
     * already quantised to the second (see [CameraMonitorState.lastAudioAtMs])
     * — which is what keeps a settings screen from recomposing hundreds of
     * times a second behind a row that says the same thing all night.
     */
    val findings: Flow<List<ReadinessFinding>> = combine(
        appSettings.settings,
        enabledAndMonitorable(),
        monitoringState.serviceRunning,
        monitoringState.cameras
            .map { states -> states.mapValues { (_, it) -> it.isLive to it.lastAudioAtMs } }
            .distinctUntilChanged(),
        ticks(),
    ) { settings, (enabled, anyMonitorable), running, heard, _ ->
        Readiness.of(
            ReadinessFacts(
                notificationsAllowed = notificationsAllowed(),
                alertChannelEnabled = alertChannelEnabled(),
                alertChannelWakesScreen = alertChannelWakesScreen(),
                fullScreenIntentAllowed = fullScreenIntentAllowed(),
                alertsEnabled = settings.alertsEnabled,
                alertChime = settings.alertChime,
                alertVibrate = settings.alertVibrate,
                alarmVolume = alarmVolume(),
                hasVibrator = hasVibrator(),
                alarmsMuted = alarmsMuted(),
                alarmsSuppressed = alarmsSuppressed(),
                dndFiltering = dndFiltering(),
                monitoringRunning = running,
                batteryOptimised = batteryOptimised(),
                charging = charging(),
                batteryPercent = batteryPercent(),
                cameras = enabled.map { camera -> audibility(camera, heard) },
                anyMonitorable = anyMonitorable,
                localNetworkGranted = LocalNetworkPermission.isGranted(context),
                nowMs = SystemClock.elapsedRealtime(),
            ),
        )
    }

    /**
     * The switched-on cameras, and whether there is any way to listen to one of
     * them — asked of the same rule the service and the viewer use, so the card
     * can never offer to start a monitor those two would refuse to arm.
     *
     * Its own flow because answering it means decrypting the credentials store
     * to find the signed-in console, and the flow it feeds emits every second
     * or two all the while a screen is open. Recomputed only when the camera
     * list moves, or when the console does — signing in to a different one
     * changes the answer without touching the list, which is exactly what
     * [MonitoringState.consoleGeneration] exists to say.
     */
    private fun enabledAndMonitorable(): Flow<Pair<List<Camera>, Boolean>> = combine(
        cameras.enabledCameras,
        monitoringState.consoleGeneration,
    ) { enabled, _ -> enabled }
        .map { enabled -> enabled to monitorable(enabled, credentials).isNotEmpty() }

    /**
     * An enabled camera the monitor has no entry for is not being heard — it is
     * either not monitorable at all or has not been built yet, and neither is a
     * room anyone is listening to.
     */
    private fun audibility(
        camera: Camera,
        heard: Map<String, Pair<Boolean, Long?>>,
    ): CameraAudibility {
        val (live, lastAudioAtMs) = heard[camera.id] ?: (false to null)
        return CameraAudibility(
            cameraId = camera.id,
            name = camera.name,
            live = live,
            lastAudioAtMs = lastAudioAtMs,
        )
    }

    private fun ticks(): Flow<Unit> = flow {
        while (true) {
            emit(Unit)
            delay(tickMs)
        }
    }

    private fun notificationsAllowed(): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    /**
     * The alert channel switched off by hand. Absent means the service has
     * never run in this install, so the channel is about to be created with the
     * importance we ask for — nothing to report.
     */
    private fun alertChannelEnabled(): Boolean {
        val channel = NotificationManagerCompat.from(context)
            .getNotificationChannelCompat(MonitoringNotifications.ALERT_CHANNEL_ID)
            ?: return true
        return channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    /**
     * Whether the channel is still important enough to light a screen.
     *
     * Android honours a full-screen intent only for a notification whose
     * channel is at [NotificationManager.IMPORTANCE_HIGH]; turned down to
     * "Default" by hand, the alert goes on posting and stops waking anyone.
     * Absent means the channel is about to be created at the importance we ask
     * for, which is high.
     */
    private fun alertChannelWakesScreen(): Boolean {
        val channel = NotificationManagerCompat.from(context)
            .getNotificationChannelCompat(MonitoringNotifications.ALERT_CHANNEL_ID)
            ?: return true
        return channel.importance >= NotificationManager.IMPORTANCE_HIGH
    }

    /** No gate at all below Android 14, so nothing there can be refused. */
    private fun fullScreenIntentAllowed(): Boolean = Build.VERSION.SDK_INT < 34 ||
        context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()

    private fun alarmVolume(): Int =
        context.getSystemService(AudioManager::class.java).getStreamVolume(AudioManager.STREAM_ALARM)

    /** The same vibrator [SystemAlarmVibrator] would pulse, asked whether it exists. */
    private fun hasVibrator(): Boolean = context
        .getSystemService(VibratorManager::class.java)
        .defaultVibrator
        .hasVibrator()

    private fun alarmsMuted(): Boolean =
        context.getSystemService(AudioManager::class.java).isStreamMute(AudioManager.STREAM_ALARM)

    /**
     * Whether Do Not Disturb is silencing an alarm outright. Only total silence
     * does that for certain; it stops sound and alarm-usage vibration alike.
     */
    private fun alarmsSuppressed(): Boolean =
        interruptionFilter() == NotificationManager.INTERRUPTION_FILTER_NONE

    /**
     * Whether Do Not Disturb is on and filtering by priority — the state whose
     * effect on an alarm cannot be read from here.
     *
     * Alarms are among priority mode's allowed categories by default and can be
     * taken out of them, and the policy that would say which is readable only
     * by an app holding notification-policy access. Dozecam has no business
     * holding that for one row of a checklist, so it reports the honest answer
     * — that it cannot tell — rather than guessing the permissive one, which is
     * how a card comes to promise a night it cannot deliver.
     */
    private fun dndFiltering(): Boolean =
        interruptionFilter() == NotificationManager.INTERRUPTION_FILTER_PRIORITY

    private fun interruptionFilter(): Int =
        context.getSystemService(NotificationManager::class.java).currentInterruptionFilter

    /** Subject to battery optimisation — that is, *not* on the exempt list. */
    private fun batteryOptimised(): Boolean = !context.getSystemService(PowerManager::class.java)
        .isIgnoringBatteryOptimizations(context.packageName)

    private fun charging(): Boolean =
        context.getSystemService(BatteryManager::class.java).isCharging

    /**
     * Null where the device will not say. A fuel gauge that does not support
     * this property answers `Integer.MIN_VALUE`, which taken at face value
     * would read as a flat battery on every phone that has one — the sort of
     * warning that is wrong every single time it appears.
     */
    private fun batteryPercent(): Int? = context.getSystemService(BatteryManager::class.java)
        .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        .takeIf { it in 0..100 }

    private companion object {
        const val TICK_MS = 2_000L
    }
}

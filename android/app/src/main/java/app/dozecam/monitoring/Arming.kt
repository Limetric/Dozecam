package app.dozecam.monitoring

import android.content.Context
import app.dozecam.AppContainer
import app.dozecam.audio.SoundDetector
import app.dozecam.permissions.LocalNetworkPermission
import kotlinx.coroutines.flow.first

/**
 * Whether monitoring should be running right now. Asked from every screen that
 * can change the answer — the viewer on resume, onboarding on completion, and
 * settings when a camera is switched on or added — because the service stops
 * itself when it has nothing to listen to, and only these know when that
 * stopped being true.
 *
 * Counts cameras there is some way to listen to rather than merely enabled
 * ones: a watch-only camera would otherwise start a service that immediately
 * stops again. That question is [monitorable]'s to answer, so this gate and the
 * service cannot disagree about which cameras count.
 */
suspend fun AppContainer.shouldArmMonitoring(
    context: Context,
    localNetworkGranted: Boolean = LocalNetworkPermission.isGranted(context),
): Boolean {
    // Without local-network access every RTSP connection is dropped as a
    // timeout, so arming would buy nothing but a foreground service holding a
    // wake lock while it reconnects all night. The viewer asks for the grant on
    // launch; arming resumes on the next resume after it is given.
    if (!localNetworkGranted) return false
    val armable = monitorable(cameras.enabledCameras.first(), protectCredentials).size
    return monitoringState.shouldAutoArm(armable)
}

/**
 * The mirror of [shouldArmMonitoring]: a running monitor with nothing left to
 * listen to should be stopped by whoever emptied the set. The service does not
 * stop itself, so that a camera switched straight back on cannot land on a
 * service already on its way out.
 */
suspend fun AppContainer.shouldStopMonitoring(): Boolean {
    if (!monitoringState.serviceRunning.value) return false
    return monitorable(cameras.enabledCameras.first(), protectCredentials).isEmpty()
}

/**
 * Whether any room's alert is live right now.
 *
 * Asked of the detectors as well as the alarm, because an alert and an alarm
 * are not the same thing: a room playing aloud raises its card with no sound at
 * all (see [ListenTarget.alertSounds]), so a test that checked only whether
 * something was ringing would happily overwrite the card of a room that is
 * crying.
 *
 * Shared between the service, which refuses a bedtime test while it is true,
 * and the screen that offers that test — because a button that quietly did
 * nothing while a toast said it had fired is exactly the false reassurance this
 * whole feature exists to remove.
 */
fun AppContainer.roomIsCrying(): Boolean =
    alertSignaler.alarmingCameraId.value
        ?.let { it != MonitoringService.TEST_CAMERA_ID } == true ||
        monitoringState.cameras.value.values
            .any { it.phase == SoundDetector.Phase.TRIGGERED }

package app.dozecam.monitoring

import android.content.Context
import app.dozecam.AppContainer
import app.dozecam.data.StreamUrlValidator
import app.dozecam.permissions.LocalNetworkPermission
import kotlinx.coroutines.flow.first

/**
 * Whether monitoring should be running right now. Asked from every screen that
 * can change the answer — the viewer on resume, onboarding on completion, and
 * settings when a camera is switched on or added — because the service stops
 * itself when it has nothing to listen to, and only these know when that
 * stopped being true.
 *
 * Counts monitorable cameras rather than merely enabled ones: a watch-only
 * camera would otherwise start a service that immediately stops again.
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
    val armable = cameras.enabledCameras.first()
        .count { StreamUrlValidator.isMonitorable(it.url) }
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
    return cameras.enabledCameras.first()
        .none { StreamUrlValidator.isMonitorable(it.url) }
}

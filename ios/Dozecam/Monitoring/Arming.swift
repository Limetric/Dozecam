/// Whether monitoring should be running right now: the port of Android's
/// `MonitoringState.shouldAutoArm`, `shouldArmMonitoring` and
/// `shouldStopMonitoring` (shared/spec/monitoring-lifecycle.md#always-on).
/// Pure over plain facts, so the viewer on becoming active, onboarding on
/// completion and settings when a camera is switched on or added all ask the
/// same question the same way.
///
/// The camera counts come from `MonitorTransports`, never from the enabled
/// cameras alone: a camera there is no way to listen to would arm a monitor
/// that has nothing to do, and the gate and the monitor must agree on which
/// cameras count.
enum Arming {
    /// The "always armed" rule: arm unless there is nothing to listen to or
    /// monitoring is already running. There is no switch to have left off;
    /// monitoring ends only when the app is exited, and the next open arms it
    /// again.
    ///
    /// Except while an exit is in flight: settings re-arms the moment it sees
    /// the monitor go, and without this the monitor would be back before the
    /// exit had finished. The next viewer to open clears the request.
    ///
    /// `monitorableCount` is the number of enabled, unpaused cameras with some
    /// way to listen to them (`MonitorTransports.monitorable`).
    static func shouldAutoArm(monitorableCount: Int, running: Bool, exitRequested: Bool) -> Bool {
        monitorableCount > 0 && !running && !exitRequested
    }

    /// `shouldAutoArm`, and not without local-network access: every
    /// connection would be dropped, so arming would buy nothing but a monitor
    /// reconnecting all night. Arming resumes on the next resume after the
    /// grant.
    static func shouldArmMonitoring(
        monitorableCount: Int, running: Bool, exitRequested: Bool, localNetworkGranted: Bool
    ) -> Bool {
        localNetworkGranted
            && shouldAutoArm(monitorableCount: monitorableCount, running: running, exitRequested: exitRequested)
    }

    /// The mirror of `shouldArmMonitoring`: a running monitor left with no
    /// enabled camera that can be listened to is stopped by whoever emptied
    /// the set (settings). Paused cameras still count here: a monitor with
    /// every room paused stays running but idle, so resuming one is picked up
    /// rather than landing on a monitor on its way out.
    ///
    /// `enabledMonitorableCount` is the number of enabled cameras, paused or
    /// not, with some way to listen to them.
    static func shouldStopMonitoring(running: Bool, enabledMonitorableCount: Int) -> Bool {
        running && enabledMonitorableCount == 0
    }
}

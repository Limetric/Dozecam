/// What the monitor must change to match a new set of monitored cameras: the
/// port of Android's `MonitorPlan`
/// (shared/spec/monitoring-lifecycle.md#which-cameras-are-monitored). Pure,
/// and separate from the monitor, because the interesting part is the
/// decision, not the players it results in.
struct MonitorPlan: Equatable, Sendable {
    /// Camera ids whose monitor must be torn down.
    let stop: Set<String>
    /// Cameras that need a monitor started, in the order they were wanted.
    let start: [Camera]

    var isEmpty: Bool { stop.isEmpty && start.isEmpty }

    /// A camera keeps its running monitor, and so its detector phase and
    /// reconnect backoff, unless it is gone or its URL changed under the same
    /// id. Renaming a camera or editing another one must never re-arm a
    /// detector that was mid-way through a refractory window.
    static func of(running: [String: Camera], wanted: [Camera]) -> MonitorPlan {
        let byId = Dictionary(wanted.map { ($0.id, $0) }, uniquingKeysWith: { _, last in last })
        let stale = Set(running.filter { id, camera in byId[id].map { $0.url != camera.url } ?? false }.keys)
        let stop = Set(running.keys).subtracting(byId.keys).union(stale)
        let start = wanted.filter { running[$0.id] == nil || stale.contains($0.id) }
        return MonitorPlan(stop: stop, start: start)
    }

    /// As `of(running:wanted:)`, and a camera whose transports changed is
    /// rebuilt as well: signing in to another console takes the livestream
    /// away from a camera that kept every field it had, and a monitor holding
    /// the old list would go on negotiating a camera id that console has never
    /// heard of (shared/spec/connection-state.md#the-monitors-transports).
    /// Android's `MonitoringService.reconcile` does this by retiring those
    /// monitors before planning; here it is part of the plan.
    static func of(
        running: [String: Camera], runningTransports: [String: [StreamSource]], wanted: [Camera],
        transports: [String: [StreamSource]]
    ) -> MonitorPlan {
        // Planned as though they were not running, so a wanted one is started
        // afresh with what is true now.
        let retransported = Set(running.keys.filter { runningTransports[$0] != transports[$0] })
        let plan = of(running: running.filter { !retransported.contains($0.key) }, wanted: wanted)
        return MonitorPlan(stop: plan.stop.union(retransported), start: plan.start)
    }
}

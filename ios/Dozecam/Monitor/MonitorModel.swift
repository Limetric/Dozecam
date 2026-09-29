import Observation

/// The viewer's state: the camera grid, connection state and monitoring
/// (#66, #67). A stub until then.
@MainActor
@Observable
final class MonitorModel {
    private(set) var cameraNames: [String] = []
}

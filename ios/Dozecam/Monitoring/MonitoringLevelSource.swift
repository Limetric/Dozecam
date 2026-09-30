/// The detection settings' meter: the loudest monitored room's level, as
/// Android's meter reads `MonitoringState`. Nil (unknown) until a room has
/// decoded a buffer on a live connection, and whenever the monitor is off.
///
/// Sampled ten times a second rather than on every change: a room's level
/// moves with every batch of buffers, and a meter needs no more than the eye
/// can follow.
struct MonitoringLevelSource: LevelSource {
    let monitoring: MonitoringService

    func levelUpdates() -> AsyncStream<Float?> {
        let (stream, continuation) = AsyncStream.makeStream(of: Float?.self)
        let monitoring = monitoring
        let sampling = Task { @MainActor in
            var last: Float?? = .none
            while !Task.isCancelled {
                let level = monitoring.peakLevel
                if last != .some(level) {
                    continuation.yield(level)
                    last = .some(level)
                }
                try? await Task.sleep(for: .milliseconds(100))
            }
            continuation.finish()
        }
        continuation.onTermination = { _ in sampling.cancel() }
        return stream
    }
}

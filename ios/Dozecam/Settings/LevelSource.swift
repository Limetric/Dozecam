/// The live audio level the detection page's meter shows: the loudest
/// monitored camera's normalised RMS (0...1), as on Android, where the meter
/// reads `MonitoringState`.
///
/// `nil` is *unknown*: no buffer has been decoded on a live connection yet.
/// shared/spec/alerts-and-sound-modes.md says a meter must never show that as
/// 0, so the meter shows no bar for it.
protocol LevelSource: Sendable {
    /// The current level, then every change.
    func levelUpdates() -> AsyncStream<Float?>
}

/// A level that never changes. Monitoring arrives in #67; until then settings
/// is handed one of these, which by default reports the level as unknown.
struct StaticLevelSource: LevelSource {
    let level: Float?

    init(level: Float? = nil) {
        self.level = level
    }

    func levelUpdates() -> AsyncStream<Float?> {
        let (stream, continuation) = AsyncStream.makeStream(of: Float?.self)
        continuation.yield(level)
        continuation.finish()
        return stream
    }
}

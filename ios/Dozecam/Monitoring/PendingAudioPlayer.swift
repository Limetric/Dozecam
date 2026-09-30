/// An audio player that never connects: the monitor's default where nothing
/// real is wired in (tests, previews, the fake-camera debug launch), the
/// counterpart of `PendingLivePlayers` for the viewer.
@MainActor
final class PendingAudioPlayer: AudioPlayer {
    var onEvent: ((AudioPlayerEvent) -> Void)?

    func play(_ source: StreamSource) {}
    func stop() {}
    func release() {}

    static func make(_ cameraId: String, _ sink: SpeakerSink) -> any AudioPlayer { PendingAudioPlayer() }
}

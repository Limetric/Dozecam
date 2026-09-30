import UIKit
import VLCKit

/// Plays into a view with libVLC, and translates what it does into
/// `PlayerEvent`s. Both controllers are built on it: they differ only in
/// where the media comes from (an RTSP URL, or the livestream pipe).
///
/// **Frame liveness.** `.timeChanged` is emitted when the count of pictures
/// VLC has actually displayed goes up, polled from the media's statistics,
/// the way Android's livestream player counts rendered frames. VLCKit 4's own
/// `mediaPlayerTimeChanged` cannot be used: it is a once-a-second timer on
/// the main run loop that extrapolates the last clock point, so it keeps
/// ticking over a frozen picture. For the same reason `.playing` is emitted
/// at the first displayed picture of each session, not when VLC's state says
/// playing, which it does before anything has been decoded.
///
/// **One `VLCMediaPlayer` per session.** VLCKit 4.0.0a24 deadlocks when a
/// playing player is given a new media (the old input's decoder waits
/// forever for a picture slot while the video output is handed over), so a
/// reconnect gets a fresh player rather than reusing this one, as Android's
/// does. Players are retired off the main thread: releasing one joins
/// libVLC's threads, which may need the main thread to finish.
@MainActor
final class VlcPlayerCore {
    var onEvent: ((PlayerEvent) -> Void)?
    let view: UIView

    private let library: VLCLibrary
    private var player: VLCMediaPlayer?
    private var relay: VlcEventRelay?
    private let undecodable: UndecodableCodecs
    private var frameWatch: Task<Void, Never>?

    /// The media the displayed-picture count belongs to; a new one restarts
    /// its count from zero.
    private weak var countedMedia: VLCMedia?
    private var displayedPictures: UInt64 = 0
    /// Whether this session has shown a picture yet.
    private var painted = false
    private var reportedUnsupported = false
    private var lastAspect: Double?
    private(set) var videoEnabled = true
    private var muted = false
    private var released = false

    static let framePollInterval: Duration = .milliseconds(250)

    /// Where retired players are released.
    private static let retirement = DispatchQueue(label: "app.dozecam.player.retirement")

    init(runtime: VlcRuntime) {
        library = runtime.library
        undecodable = runtime.undecodableCodecs
        view = VlcVideoView()
    }

    /// Plays `media` as a new session, on a new player. The mute and video
    /// choices already made carry over.
    func play(_ media: VLCMedia) {
        guard !released else { return }
        retirePlayer()
        resetSession()
        // A reconnect builds a new media, and libVLC selects its tracks
        // afresh: a camera nobody is watching would otherwise come back from
        // a stall with its decoder running again.
        if !videoEnabled { media.addOption(":no-video") }
        let player = VLCMediaPlayer(library: library)
        let relay = VlcEventRelay()
        // The picture is letterboxed inside whatever box the tile gives it,
        // never stretched or cropped: a requirement, not a default.
        player.videoFitMode = .smaller
        player.drawable = view
        relay.core = self
        player.delegate = relay
        self.player = player
        self.relay = relay
        player.media = media
        player.play()
        applyMute()
        watchFrames()
    }

    func setMuted(_ muted: Bool) {
        self.muted = muted
        applyMute()
    }

    /// Deselects the video track on the running session, leaving the stream
    /// and its audio untouched; `play` carries the choice onto later media.
    func setVideoEnabled(_ enabled: Bool) {
        guard videoEnabled != enabled, !released else { return }
        videoEnabled = enabled
        if enabled {
            player?.selectTrack(at: 0, type: .video)
        } else {
            player?.deselectAllVideoTracks()
        }
        // The next picture is a new one to wait for, not the old session's.
        painted = false
    }

    func stop() {
        guard !released else { return }
        frameWatch?.cancel()
        frameWatch = nil
        retirePlayer()
    }

    /// Unusable afterwards. Anything still queued from VLC's threads is
    /// dropped.
    func release() {
        guard !released else { return }
        stop()
        released = true
        onEvent = nil
    }

    /// Stops the current player and lets it go: its late events are dropped,
    /// and its last reference is released on a background queue.
    private func retirePlayer() {
        // No local strong reference: the main thread must not be left
        // holding the last one when the queue lets go of its own.
        guard player != nil else { return }
        relay?.core = nil
        player?.delegate = nil
        player?.stop()
        let retiring = RetiringPlayer(player!)
        player = nil
        relay = nil
        Self.retirement.async { retiring.drop() }
    }

    private func resetSession() {
        frameWatch?.cancel()
        countedMedia = nil
        displayedPictures = 0
        painted = false
        reportedUnsupported = false
    }

    private func applyMute() {
        player?.audio?.isMuted = muted
    }

    private func emit(_ event: PlayerEvent) {
        onEvent?(event)
    }

    // MARK: Events relayed from VLC's threads

    fileprivate func handle(_ event: VlcEventRelay.Event) {
        guard !released else { return }
        switch event {
        case .state(let state):
            switch state {
            case .error: emit(.error)
            case .stopped: emit(.stopped)
            case .opening: emit(.buffering)
            default: break
            }
        case .buffering(let progress):
            if progress < 100 { emit(.buffering) }
        case .videoTrackChanged:
            reportAspect()
            checkDecodable()
        }
    }

    // MARK: Frames

    private func watchFrames() {
        frameWatch = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: Self.framePollInterval)
                guard let self, !Task.isCancelled else { return }
                self.pollFrames()
            }
        }
    }

    private func pollFrames() {
        guard let player, let media = player.media else { return }
        if media !== countedMedia {
            countedMedia = media
            displayedPictures = 0
        }
        let displayed = media.statistics.displayedPictures
        if displayed < displayedPictures {
            // Never expected for one media; rebase rather than go silent.
            displayedPictures = displayed
        } else if displayed > displayedPictures {
            displayedPictures = displayed
            if videoEnabled {
                if !painted {
                    painted = true
                    emit(.playing)
                    reportAspect()
                }
                emit(.timeChanged(milliseconds: Int64(player.time.intValue)))
            }
        }
        checkDecodable()
    }

    // MARK: Picture shape

    private func reportAspect() {
        guard let aspect = currentAspect(), aspect != lastAspect else { return }
        lastAspect = aspect
        emit(.videoAspect(aspect))
    }

    /// The displayed picture's width over height: anamorphic pixels applied,
    /// and turned on its side when orientation metadata makes VLC rotate it.
    private func currentAspect() -> Double? {
        guard let track = selectedVideoTrack(), let video = track.video, video.width > 0, video.height > 0 else {
            let size = player?.videoSize ?? .zero
            return size.width > 0 && size.height > 0 ? size.width / size.height : nil
        }
        let sar =
            video.sourceAspectRatio > 0 && video.sourceAspectRatioDenominator > 0
            ? Double(video.sourceAspectRatio) / Double(video.sourceAspectRatioDenominator) : 1
        let encoded = Double(video.width) * sar / Double(video.height)
        switch video.orientation {
        case .leftTop, .leftBottom, .rightTop, .rightBottom: return 1 / encoded
        default: return encoded
        }
    }

    private func selectedVideoTrack() -> VLCMediaPlayer.Track? {
        guard let tracks = player?.videoTracks else { return nil }
        return tracks.first(where: \.isSelected) ?? tracks.first
    }

    // MARK: Codec support

    /// Says once per session that the picture will never come, when libVLC
    /// has reported no decoder for this stream's video codec.
    private func checkDecodable() {
        guard !reportedUnsupported, let track = selectedVideoTrack() else { return }
        let fourcc = Self.fourcc(track.codec)
        guard undecodable.contains(fourcc) else { return }
        reportedUnsupported = true
        VlcRuntime.log.notice("no decoder for video codec \(fourcc, privacy: .public)")
        emit(.unsupportedCodec(Self.codecName(fourcc: fourcc, track: track)))
    }

    /// A FourCC as libVLC prints it: its four bytes in memory order.
    nonisolated static func fourcc(_ value: UInt32) -> String {
        let bytes = [0, 8, 16, 24].map { UInt8(truncatingIfNeeded: value >> $0) }
        return String(decoding: bytes, as: UTF8.self).trimmingCharacters(in: .whitespaces)
    }

    /// The name a tile shows: the common one for the codecs cameras send,
    /// else VLC's description.
    nonisolated static func codecName(fourcc: String) -> String? {
        switch fourcc {
        case "av01": "AV1"
        case "hevc": "HEVC"
        case "h264": "H.264"
        case "mjpg": "MJPEG"
        case "vp09": "VP9"
        default: nil
        }
    }

    private static func codecName(fourcc: String, track: VLCMedia.Track) -> String {
        if let name = codecName(fourcc: fourcc) { return name }
        let description = VLCMedia.codecName(forFourCC: track.codec, trackType: .video)
        return description.isEmpty ? fourcc : description
    }
}

/// Carries a stopped player to the retirement queue, where its last
/// reference goes. Unchecked: nothing touches the player after the hand-off
/// but its release.
private final class RetiringPlayer: @unchecked Sendable {
    private var player: VLCMediaPlayer?

    init(_ player: VLCMediaPlayer) { self.player = player }

    func drop() { player = nil }
}

/// The view VLC draws into; black where the picture is not.
private final class VlcVideoView: UIView {
    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = .black
        clipsToBounds = true
        isUserInteractionEnabled = false
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError("init(coder:) is not supported") }
}

/// Receives `VLCMediaPlayer`'s delegate calls, which VLCKit 4 makes
/// synchronously on libVLC's own threads, and hands them to the main actor in
/// order. Nonisolated, and its closures are created here, never in MainActor
/// code: one that inherited MainActor isolation would trap on VLC's thread
/// (#58).
private final class VlcEventRelay: NSObject, VLCMediaPlayerDelegate, @unchecked Sendable {
    enum Event: Sendable {
        case state(VLCMediaPlayerState)
        case buffering(Float)
        case videoTrackChanged
    }

    /// Written and read on the main thread only.
    weak var core: VlcPlayerCore?

    func mediaPlayerStateChanged(_ newState: VLCMediaPlayerState) {
        deliver(.state(newState))
    }

    func mediaPlayerBufferingChanged(_ progress: Float) {
        deliver(.buffering(progress))
    }

    func mediaPlayerTrackAdded(_ trackId: String, with trackType: VLCMedia.TrackType) {
        if trackType == .video { deliver(.videoTrackChanged) }
    }

    func mediaPlayerTrackUpdated(_ trackId: String, with trackType: VLCMedia.TrackType) {
        if trackType == .video { deliver(.videoTrackChanged) }
    }

    func mediaPlayerTrackSelected(_ trackType: VLCMedia.TrackType, selectedId: String, unselectedId: String) {
        if trackType == .video { deliver(.videoTrackChanged) }
    }

    private func deliver(_ event: Event) {
        // The main queue, not a Task: it keeps VLC's order.
        DispatchQueue.main.async { [weak self] in
            MainActor.assumeIsolated { self?.core?.handle(event) }
        }
    }
}

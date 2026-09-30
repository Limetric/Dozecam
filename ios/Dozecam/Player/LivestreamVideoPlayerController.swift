import UIKit

/// Plays a Protect camera over the console's livestream WebSocket: negotiate
/// (`ProtectLivestreamProvider`), open the socket, decode Protect's framing
/// into fMP4 (`LivestreamDecoder`), and feed it through a bounded pipe into
/// libVLC's MP4 demuxer (`LivestreamMedia`). The counterpart of Android's
/// `LivestreamVideoPlayerController`, with libVLC where Android has Media3.
/// Unlike RTSP, the livestream carries whatever the camera encodes: AV1
/// decodes through dav1d, in software where the device has no AV1 hardware.
///
/// One connection per `play`: the console mints a single-use token per
/// negotiation, so a reconnect negotiates again. Reconnecting is the
/// watchdog's job; every failure here surfaces as `.error`.
@MainActor
final class LivestreamVideoPlayerController: VideoPlayerController {
    /// Negotiates a livestream: `ProtectLivestreamProvider.connect`, or a
    /// stand-in in tests.
    typealias Connect =
        @Sendable (_ cameraId: String, _ channel: Int) async throws -> ProtectLivestreamProvider.Connection

    private let core: VlcPlayerCore
    private let connect: Connect
    private let maxPendingSegments: Int

    private var negotiation: Task<Void, Never>?
    private var feeding: Task<Void, Never>?
    private var pipe: LivestreamPipe?
    /// Bumped by every `play` and `stop`, so a late result of an abandoned
    /// session cannot act on the current one.
    private var session = 0

    init(
        runtime: VlcRuntime = .shared, maxPendingSegments: Int = LivestreamPipe.defaultMaxPendingSegments,
        connect: @escaping Connect
    ) {
        core = VlcPlayerCore(runtime: runtime)
        self.connect = connect
        self.maxPendingSegments = maxPendingSegments
    }

    var onEvent: ((PlayerEvent) -> Void)? {
        get { core.onEvent }
        set { core.onEvent = newValue }
    }

    var view: UIView { core.view }

    /// Only `.livestream` plays here; RTSP belongs to
    /// `VlcVideoPlayerController`.
    func play(_ source: StreamSource) {
        guard case .livestream(let cameraId, let channel) = source else { return }
        stop()
        let session = self.session
        let connect = self.connect
        negotiation = Task { [weak self] in
            let connection: ProtectLivestreamProvider.Connection
            do {
                connection = try await connect(cameraId, channel)
            } catch {
                guard !Task.isCancelled else { return }  // an abandoned attempt is not a failure
                VlcRuntime.log.warning("livestream negotiation failed: \(error, privacy: .public)")
                self?.fail(session)
                return
            }
            guard !Task.isCancelled else { return }
            self?.start(connection, session: session)
        }
    }

    /// The socket stays open and keeps feeding the demuxer; only the video
    /// decoder goes.
    func setVideoEnabled(_ enabled: Bool) { core.setVideoEnabled(enabled) }

    func stop() {
        session += 1
        negotiation?.cancel()
        negotiation = nil
        feeding?.cancel()
        feeding = nil
        // Before the player stops: its input thread may be blocked reading.
        pipe?.close()
        pipe = nil
        core.stop()
    }

    func release() {
        stop()
        core.release()
    }

    private func start(_ connection: ProtectLivestreamProvider.Connection, session: Int) {
        guard session == self.session else { return }
        let pipe = LivestreamPipe(maxPendingSegments: maxPendingSegments)
        guard let media = LivestreamMedia.make(reading: pipe) else {
            VlcRuntime.log.error("libVLC refused the livestream media")
            fail(session)
            return
        }
        self.pipe = pipe
        feeding = Self.feed(
            connection, into: pipe,
            onFailure: Self.failureHandler(for: self, session: session)
        )
        core.play(media)
    }

    private func fail(_ session: Int) {
        guard session == self.session else { return }
        core.onEvent?(.error)
    }

    /// Tells the watchdog straight away that the socket died, rather than
    /// when the demuxer next runs dry, so a failure while the player idles
    /// still triggers a reconnect. Built here, nonisolated, because it runs
    /// on the feeding task's thread (#58).
    private nonisolated static func failureHandler(
        for controller: LivestreamVideoPlayerController, session: Int
    ) -> @Sendable () -> Void {
        { [weak controller] in
            DispatchQueue.main.async {
                MainActor.assumeIsolated { controller?.fail(session) }
            }
        }
    }

    /// Opens the socket and turns its messages into the fMP4 byte stream the
    /// pipe carries: the initialisation segment, then one fragment at a time.
    /// Runs off the main actor; cancelling it closes the socket. Shared with
    /// the monitor's audio-only players (`AudioOnlyPlayer`).
    nonisolated static func feed(
        _ connection: ProtectLivestreamProvider.Connection, into pipe: LivestreamPipe,
        onFailure: @escaping @Sendable () -> Void
    ) -> Task<Void, Never> {
        Task.detached {
            let messages = ProtectLivestreamSocket(urlSession: connection.urlSession).open(connection.url)
            var decoder = LivestreamDecoder()
            do {
                for try await message in messages {
                    for segment in try decoder.decode(message) {
                        guard pipe.offer(bytes(of: segment)) else {
                            throw pipe.failureCause ?? LivestreamPipe.Failure.overflow
                        }
                    }
                }
                // The socket never ends cleanly on its own: only cancellation
                // gets here.
                pipe.finish()
            } catch {
                guard !Task.isCancelled else { return }
                VlcRuntime.log.warning("livestream socket failed: \(error, privacy: .public)")
                pipe.fail(error)
                onFailure()
            }
        }
    }

    private nonisolated static func bytes(of segment: LivestreamSegment) -> Data {
        switch segment {
        case .initialization(let data, let codec):
            VlcRuntime.log.info("livestream codecs: \(codec, privacy: .public)")
            // Media3 needed this. libVLC plays Protect's bare av1C without it
            // (LivestreamPlaybackTests), but the repair is spec-valid, so both
            // platforms feed their demuxers the same bytes.
            return Av1ConfigRepair.repair(data)
        case .media(let data):
            return data
        }
    }
}

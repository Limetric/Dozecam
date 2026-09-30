import Foundation
import Synchronization

/// Builds the player for a camera's stream: VLCKit over RTSP, or the Protect
/// livestream negotiated with the console currently signed in. The screen
/// holds one and asks it for a controller per camera session.
@MainActor
final class LivePlayers {
    private let runtime: VlcRuntime
    private let provider: ProtectLivestreamProvider
    private let sessions: LivestreamSessions

    init(dependencies: AppDependencies, runtime: VlcRuntime = .shared) {
        self.runtime = runtime
        let credentials = dependencies.credentials
        let sessions = LivestreamSessions(factory: PinnedSessionFactory(store: dependencies.trust))
        self.sessions = sessions
        provider = ProtectLivestreamProvider(
            signIn: {
                try credentials.load().map {
                    ProtectLivestreamProvider.SignIn(host: $0.host, username: $0.username, password: $0.password)
                }
            },
            consoleSession: { _ in sessions.console },
            mediaSession: { _ in
                // The socket's URL came back over the pinned console session,
                // so that console vouches for the media port's certificate.
                guard let host = try credentials.load()?.host,
                    let baseURL = ProtectApiClient.baseURL(for: host),
                    let console = TofuEndpoint(url: baseURL)
                else { throw ProtectAPIError.notSignedIn("This camera needs a Protect console sign-in") }
                return sessions.media(vouchedBy: console)
            }
        )
    }

    func make(for source: StreamSource) -> any VideoPlayerController {
        switch source {
        case .rtsp:
            return VlcVideoPlayerController(runtime: runtime)
        case .livestream:
            let provider = self.provider
            return LivestreamVideoPlayerController(runtime: runtime) { cameraId, channel in
                try await provider.connect(cameraId: cameraId, channel: channel)
            }
        }
    }

    deinit {
        sessions.invalidate()
    }
}

/// The pinned sessions every livestream shares: one for the console's REST
/// API and one per console for the media port it vouches for. Made once,
/// because a `URLSession` holds its delegate until it is invalidated.
private final class LivestreamSessions: Sendable {
    private let factory: PinnedSessionFactory
    private let consoleSession: PinnedSession
    private let mediaSessions = Mutex<[TofuEndpoint: PinnedSession]>([:])

    init(factory: PinnedSessionFactory) {
        self.factory = factory
        consoleSession = factory.consoleSession()
    }

    var console: URLSession { consoleSession.urlSession }

    func media(vouchedBy console: TofuEndpoint) -> URLSession {
        mediaSessions.withLock { sessions in
            if let session = sessions[console] { return session.urlSession }
            let session = factory.mediaSession(vouchedBy: console)
            sessions[console] = session
            return session.urlSession
        }
    }

    func invalidate() {
        consoleSession.invalidate()
        mediaSessions.withLock { sessions in
            for session in sessions.values { session.invalidate() }
            sessions.removeAll()
        }
    }
}

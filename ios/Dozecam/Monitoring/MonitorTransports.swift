/// Where a camera's audio can be listened to, best first: the port of
/// Android's `MonitorTransports`
/// (shared/spec/connection-state.md#the-monitors-transports).
///
/// RTSP leads wherever it works: the monitor asks for the audio track alone,
/// so it costs a few kilobits a second and can run all night on battery. The
/// livestream carries the camera's video whether or not anything looks at it,
/// which is why it follows rather than leads. But it carries every codec, and
/// it is the only way in for a stale `rtsps://` camera: VLCKit's RTSP has no
/// TLS, and until the in-app TLS proxy lands (#59, #64) iOS listens over plain
/// `rtsp://` only, as Android does (shared/spec/protect.md#stream-urls).
///
/// This only describes the transports; `TransportFallback` decides when to
/// move between them.
enum MonitorTransports {
    /// `source` is what the viewer resolved for this camera
    /// (`StreamSource.of`): a livestream identity only exists for a Protect
    /// camera whose console is the one signed in.
    ///
    /// Empty means there is no way to listen to this camera at all, which the
    /// caller must report rather than quietly leave a room uncovered.
    static func of(_ camera: Camera, source: StreamSource, consoleHost: String?) -> [StreamSource] {
        var transports: [StreamSource] = []
        if StreamUrlValidator.isMonitorable(camera.url) { transports.append(.rtsp(url: camera.url)) }
        // A livestream is negotiated against a signed-in console. Without one
        // every attempt fails, and a transport that can only fail is a loop
        // that would also count the camera as monitored and silence the notice
        // saying otherwise. Cameras stored before the console host was
        // recorded, and legacy `protect-<id>-<channel>` ids, both resolve to a
        // livestream with nothing to check against, so this is the check.
        if case .livestream = source, consoleHost != nil { transports.append(source) }
        return transports
    }

    /// The transports for every monitored camera there is some way to listen
    /// to, keyed by camera id; cameras with no way in are absent.
    ///
    /// Monitored means enabled and not in `pausedIds`
    /// (shared/spec/monitoring-lifecycle.md#which-cameras-are-monitored).
    /// `consoleHost` is the signed-in console's host, or nil when nobody is
    /// signed in. One place for the answer, because every gate that decides
    /// whether monitoring is worth arming has to reach the same one as the
    /// monitor that carries it out.
    static func transportsFor(
        _ cameras: [Camera], pausedIds: Set<String> = [], consoleHost: String?
    ) -> [String: [StreamSource]] {
        var result: [String: [StreamSource]] = [:]
        for camera in monitored(cameras, pausedIds: pausedIds) {
            let transports = of(
                camera, source: StreamSource.of(camera, consoleHost: consoleHost), consoleHost: consoleHost)
            if !transports.isEmpty { result[camera.id] = transports }
        }
        return result
    }

    /// The monitored cameras there is some way to listen to, in list order:
    /// what the monitor should be running (the `wanted` of `MonitorPlan.of`).
    static func monitorable(_ cameras: [Camera], pausedIds: Set<String> = [], consoleHost: String?) -> [Camera] {
        let usable = transportsFor(cameras, pausedIds: pausedIds, consoleHost: consoleHost)
        return monitored(cameras, pausedIds: pausedIds).filter { usable[$0.id] != nil }
    }

    /// Enabled and not paused: what the monitor listens to. The viewer keeps
    /// the paused ones on screen as placeholders, and nothing else.
    static func monitored(_ cameras: [Camera], pausedIds: Set<String>) -> [Camera] {
        cameras.filter { $0.enabled && !pausedIds.contains($0.id) }
    }
}

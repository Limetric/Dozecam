import Foundation

/// A camera offered in the onboarding picker, from whichever API discovered
/// it.
struct DiscoveredCamera: Equatable, Identifiable, Sendable {
    /// The console's camera id, not the stored `Camera.id`.
    let id: String
    let name: String
    /// The quality that would be imported, e.g. "Medium".
    let detail: String
}

/// How a camera discovered on a Protect console becomes a stored `Camera`
/// (shared/spec/protect.md, "Camera ids" and "Stream URLs"). The onboarding
/// flow that drives this, and its session and key handling, is #65; these
/// are the rules both APIs must agree on, kept apart so they can be held to
/// the shared fixtures and to Android's `OnboardingViewModel`.
enum ProtectCameraImport {
    /// Protect numbers a camera's channels High, Medium, Low, so the medium
    /// quality the public API names is channel 1 on the legacy one. Keeping
    /// that in the camera id means a console that switches APIs between runs
    /// updates its existing entry instead of adding a duplicate.
    static let mediumChannel = 1
    static let mediumLabel = "Medium"
    /// The name of the API key onboarding mints, so a user can find it on
    /// the console.
    static let apiKeyName = "Dozecam"
    /// Shown for a camera the console has no name for.
    static let unnamedCamera = "Camera"

    static func displayName(_ name: String?) -> String {
        guard let name, !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return unnamedCamera }
        return name
    }

    /// The console a camera belongs to, in the form the credentials store
    /// keeps it: the address as the user typed it, trimmed. Playback compares
    /// it with the signed-in console to decide whether the livestream may be
    /// used for this camera.
    static func consoleHost(forInput hostInput: String) -> String {
        hostInput.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    // MARK: - Picker rows

    static func discovered(_ camera: PublicCamera) -> DiscoveredCamera {
        DiscoveredCamera(id: camera.id, name: displayName(camera.name), detail: mediumLabel)
    }

    static func discovered(_ camera: ProtectCamera) -> DiscoveredCamera {
        DiscoveredCamera(
            id: camera.id,
            name: displayName(camera.name),
            detail: camera.preferredChannel?.name ?? ""
        )
    }

    // MARK: - Stored cameras

    /// A camera from the public API: always the Medium channel, streamed from
    /// `streamURL`, the plain `rtsp://` URL `ProtectPublicApiClient` made of
    /// the console's `rtsps://` one.
    static func camera(
        _ camera: PublicCamera,
        streamURL: String,
        consoleHost: String,
        existing: [Camera]
    ) -> Camera {
        stored(
            cameraId: camera.id,
            channel: mediumChannel,
            name: displayName(camera.name),
            url: streamURL,
            consoleHost: consoleHost,
            existing: existing
        )
    }

    /// A camera from the legacy API, on its preferred channel (Medium, or
    /// the first one when there is no Medium).
    static func camera(
        _ camera: ProtectCamera,
        channel: ProtectChannel,
        streamURL: String,
        consoleHost: String,
        existing: [Camera]
    ) -> Camera {
        stored(
            cameraId: camera.id,
            channel: channel.id,
            name: displayName(camera.name),
            url: streamURL,
            consoleHost: consoleHost,
            existing: existing
        )
    }

    // MARK: - Importing

    /// Imports one camera over the public API. A stream the console already
    /// serves is reused, and one is enabled only when there is none, so
    /// re-running onboarding does not churn the console's stream settings.
    static func importCamera(
        _ camera: PublicCamera,
        api: ProtectPublicApiClient,
        apiKey: String,
        consoleHost: String,
        existing: [Camera]
    ) async throws -> Camera {
        let quality = ProtectPublicApiClient.qualityMedium
        var rtsps = try await api.rtspsStreams(apiKey: apiKey, cameraId: camera.id)[quality]
        if rtsps == nil {
            rtsps = try await api.createRtspsStreams(apiKey: apiKey, cameraId: camera.id, qualities: [quality])[
                quality
            ]
        }
        guard let rtsps else {
            throw ProtectAPIError.invalidResponse(
                "Console did not return a \(quality) stream for \(displayName(camera.name))"
            )
        }
        guard let url = api.streamURL(forRtsps: rtsps) else {
            throw ProtectAPIError.invalidResponse("Could not read the stream alias for \(displayName(camera.name))")
        }
        return self.camera(camera, streamURL: url, consoleHost: consoleHost, existing: existing)
    }

    /// Imports one camera over the legacy API, or nil for a camera with no
    /// channels, which has nothing to stream. An RTSP alias already served
    /// is reused; otherwise `enableRtsp` turns it on and returns the updated
    /// camera. It is a closure because the caller owns the login session and
    /// renews it once if it expired while the picker sat open.
    static func importCamera(
        isolation: isolated (any Actor)? = #isolation,
        _ camera: ProtectCamera,
        api: ProtectApiClient,
        consoleHost: String,
        existing: [Camera],
        enableRtsp: (_ cameraId: String, _ channelId: Int) async throws -> ProtectCamera
    ) async throws -> Camera? {
        guard let channel = camera.preferredChannel else { return nil }
        let alias: String
        if channel.isRtspEnabled, let served = channel.rtspAlias {
            alias = served
        } else {
            let updated = try await enableRtsp(camera.id, channel.id)
            guard let enabled = updated.channels.first(where: { $0.id == channel.id })?.rtspAlias else {
                throw ProtectAPIError.invalidResponse(
                    "Console did not return an RTSP alias for \(displayName(camera.name))"
                )
            }
            alias = enabled
        }
        return self.camera(
            camera,
            channel: channel,
            streamURL: api.rtspURL(forAlias: alias),
            consoleHost: consoleHost,
            existing: existing
        )
    }

    /// Re-importing keeps the stored enabled setting: enabled is the user's
    /// choice, not the console's. A camera new to the list arrives enabled.
    private static func stored(
        cameraId: String,
        channel: Int,
        name: String,
        url: String,
        consoleHost: String,
        existing: [Camera]
    ) -> Camera {
        let id = Camera.protectID(cameraId: cameraId, channel: channel)
        return Camera(
            id: id,
            name: name,
            url: url,
            protect: ProtectStream(cameraId: cameraId, channel: channel, consoleHost: consoleHost),
            enabled: existing.first { $0.id == id }?.enabled ?? true
        )
    }
}

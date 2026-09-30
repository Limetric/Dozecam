import Foundation

/// The cameras, the counterpart of Android's `CameraStore`: the same four
/// operations and the same enabled-subset rule.
protocol CameraStore: Sendable {
    /// Every camera, in the order they were added.
    var cameras: [Camera] { get }

    /// The enabled subset, in list order: what the viewer shows and the monitor
    /// listens to.
    var enabledCameras: [Camera] { get }

    /// `cameras` now, then after every change.
    func cameraUpdates() -> AsyncStream<[Camera]>

    /// `enabledCameras` now, then after every change to it.
    func enabledCameraUpdates() -> AsyncStream<[Camera]>

    /// Replaces the camera with the same id where it stands, or appends it.
    func upsert(_ camera: Camera) async throws
    func remove(id: String) async throws
    func setEnabled(id: String, _ enabled: Bool) async throws
}

/// The camera list at rest: JSON in one file in Application Support.
///
/// Stream URLs embed Protect's bearer-style stream tokens (shared/spec/privacy.md),
/// which Android keeps encrypted. Here the file is written with Data Protection
/// `completeUntilFirstUserAuthentication`, encrypted at rest until the first
/// unlock after a boot and readable after it, so the monitor can read it while
/// the phone is locked; and it is excluded from backup, so the tokens never
/// leave the device.
///
/// Writes are serialised by the actor and durable before they return, and
/// only then published, as Android's `CameraRepository.mutate` does.
actor CameraRepository: CameraStore {
    /// `Application Support/cameras.json`.
    static var defaultFileURL: URL {
        URL.applicationSupportDirectory.appending(path: "cameras.json", directoryHint: .notDirectory)
    }

    enum StorageError: Error {
        /// The file exists but cannot be read now: before the first unlock
        /// after a boot, say. Writing then would replace the real list.
        case unreadable(any Error)
    }

    /// Replaced atomically, never half-written; readable from the first unlock
    /// after a boot, so the monitor can read it with the phone locked.
    static let writeOptions: Data.WritingOptions = [.atomic, .completeFileProtectionUntilFirstUserAuthentication]

    private let fileURL: URL
    private let all: Broadcast<[Camera]>
    private let enabled: Broadcast<[Camera]>
    /// False while the file exists but could not be read, so a later write
    /// reads it first instead of overwriting cameras it never saw.
    private var loaded: Bool

    init(fileURL: URL = CameraRepository.defaultFileURL) {
        self.fileURL = fileURL
        let initial: [Camera]
        switch Self.read(fileURL) {
        case .success(let cameras):
            initial = cameras
            loaded = true
        case .failure:
            initial = []
            loaded = false
        }
        all = Broadcast(initial)
        enabled = Broadcast(initial.filter(\.enabled))
    }

    nonisolated var cameras: [Camera] { all.value }
    nonisolated var enabledCameras: [Camera] { enabled.value }

    nonisolated func cameraUpdates() -> AsyncStream<[Camera]> { all.stream() }
    nonisolated func enabledCameraUpdates() -> AsyncStream<[Camera]> { enabled.stream() }

    func upsert(_ camera: Camera) async throws {
        try mutate { current in
            var next = current
            if let index = next.firstIndex(where: { $0.id == camera.id }) {
                next[index] = camera
            } else {
                next.append(camera)
            }
            return next
        }
    }

    func remove(id: String) async throws {
        try mutate { $0.filter { $0.id != id } }
    }

    func setEnabled(id: String, _ enabled: Bool) async throws {
        try mutate { current in
            current.map { camera in
                guard camera.id == id else { return camera }
                var changed = camera
                changed.enabled = enabled
                return changed
            }
        }
    }

    /// Re-reads the file if it could not be read at launch; for when the phone
    /// has since been unlocked. A no-op once loaded.
    func reloadIfNeeded() throws {
        guard !loaded else { return }
        switch Self.read(fileURL) {
        case .success(let cameras):
            loaded = true
            publish(cameras)
        case .failure(let error):
            throw error
        }
    }

    private func mutate(_ transform: ([Camera]) -> [Camera]) throws {
        try reloadIfNeeded()
        let next = transform(all.value)
        try write(next)
        publish(next)
    }

    private func publish(_ cameras: [Camera]) {
        all.sendIfChanged(cameras)
        enabled.sendIfChanged(cameras.filter(\.enabled))
    }

    private func write(_ cameras: [Camera]) throws {
        let data = try JSONEncoder().encode(cameras)
        try FileManager.default.createDirectory(
            at: fileURL.deletingLastPathComponent(), withIntermediateDirectories: true)
        try data.write(to: fileURL, options: Self.writeOptions)
        // An atomic write replaces the file, and the flag belongs to the file,
        // so it is set again after every write.
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        var url = fileURL
        try url.setResourceValues(values)
    }

    /// No file is no cameras. A file that does not decode is also no cameras,
    /// as on Android (`CameraRepository.decode`): there is nothing better to
    /// show, and onboarding puts them back. A file that cannot be read at all
    /// is a failure, so the caller never takes it for an empty list.
    private static func read(_ url: URL) -> Result<[Camera], StorageError> {
        let data: Data
        do {
            data = try Data(contentsOf: url)
        } catch CocoaError.fileReadNoSuchFile {
            return .success([])
        } catch {
            return .failure(.unreadable(error))
        }
        return .success((try? JSONDecoder().decode([Camera].self, from: data)) ?? [])
    }
}

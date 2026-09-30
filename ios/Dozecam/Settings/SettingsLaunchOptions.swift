import Foundation

/// What settings takes from launch arguments. Only debug builds read any: they
/// let an agent reach a settings page, and put something on it, in a
/// simulator nobody can tap (`-startOn settings` opens the sheet itself).
///
/// - `-settingsSection cameras|detection|alerts|display|checklist|about`
///   opens that page.
/// - `-settingsLevel 0.2` has the detection meter show that level.
/// - `-seedCameras YES` adds three sample cameras (one switched off, one a
///   stale `rtsps://` entry) if they are not there yet.
enum SettingsLaunchOptions {
    static func section(defaults: UserDefaults = .standard) -> SettingsSection? {
        #if DEBUG
            defaults.string(forKey: "settingsSection").flatMap(SettingsSection.init(rawValue:))
        #else
            nil
        #endif
    }

    /// `fallback` is the real meter: the monitor's level.
    static func levelSource(
        defaults: UserDefaults = .standard, fallback: any LevelSource = StaticLevelSource()
    ) -> any LevelSource {
        #if DEBUG
            if defaults.object(forKey: "settingsLevel") != nil {
                return StaticLevelSource(level: Float(defaults.double(forKey: "settingsLevel")))
            }
        #endif
        return fallback
    }

    #if DEBUG
        static let sampleCameras = [
            Camera(id: "sample-nursery", name: "Nursery", url: "rtsp://127.0.0.1:18554/nursery"),
            Camera(
                id: "sample-playroom", name: "Playroom", url: "rtsp://192.168.1.10:7447/aBcDeFgHiJkLmNoP",
                protect: ProtectStream(cameraId: "65f0c0ffee", channel: 1), enabled: false),
            Camera(id: "sample-hallway", name: "Hallway", url: "rtsps://192.168.1.10:7441/qRsTuVwXyZ"),
        ]

        static func seedCamerasIfAsked(into cameras: CameraRepository, defaults: UserDefaults = .standard) async {
            guard defaults.bool(forKey: "seedCameras") else { return }
            for camera in sampleCameras where !cameras.cameras.contains(where: { $0.id == camera.id }) {
                try? await cameras.upsert(camera)
            }
        }
    #endif
}

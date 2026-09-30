#if DEBUG
    import Foundation
    import SwiftUI
    import Synchronization

    /// Debug builds only: launch arguments that open the viewer on fake cameras,
    /// in stores that touch nothing real, so the grid can be seen (and
    /// screenshotted) in every state on a simulator with no camera or console.
    ///
    ///     -fakeCameras 6                        how many (up to 6)
    ///     -fakeScripts live,live,stall,never,unsupported,flaky
    ///                                           one `FakeVideoPlayer.Script` each
    ///     -fakePaused 5                         pause the camera at this index
    ///     -fakeFullscreen 0                     open the camera at this index
    ///     -fakeOfflineAfter 6                   the network drops after 6 s
    ///     -fakeNightTheme YES   -fakeSoundMode ROTATING|ALL_ALOUD   -fakeAlertsOff YES
    enum MonitorDebugLaunch {
        static let names = ["Nursery", "Twins' room", "Playroom", "Guest room", "Attic", "Garden"]
        static let defaultScripts: [FakeVideoPlayer.Script] = [.live, .live, .stall, .never, .unsupported, .flaky]

        /// A number from the launch arguments, which arrive as strings.
        private static func index(_ defaults: UserDefaults, _ key: String) -> Int? {
            defaults.object(forKey: key) == nil ? nil : defaults.integer(forKey: key)
        }

        @MainActor
        static func appModel(defaults: UserDefaults = .standard) -> AppModel? {
            let count = min(defaults.integer(forKey: "fakeCameras"), names.count)
            guard count > 0 else { return nil }
            let scripts =
                defaults.string(forKey: "fakeScripts")?.split(separator: ",")
                .compactMap { FakeVideoPlayer.Script(rawValue: String($0)) } ?? defaultScripts

            let directory = FileManager.default.temporaryDirectory.appending(path: "fake-\(UUID().uuidString)")
            let cameras = (0..<count).map { index in
                Camera(id: "fake-\(index)", name: names[index], url: "rtsp://fake.invalid/\(index)")
            }
            try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            try? JSONEncoder().encode(cameras).write(to: directory.appending(path: "cameras.json"))

            let suite = "app.dozecam.fake.\(UUID().uuidString)"
            let settings = UserDefaults(suiteName: suite + ".app")!
            settings.set(defaults.bool(forKey: "fakeNightTheme"), forKey: AppSettingsRepository.Key.nightTheme)
            settings.set(
                defaults.string(forKey: "fakeSoundMode") ?? SoundMode.off.rawValue,
                forKey: AppSettingsRepository.Key.soundMode)
            settings.set(!defaults.bool(forKey: "fakeAlertsOff"), forKey: AppSettingsRepository.Key.alertsEnabled)

            let network = ScriptedPathSource(offlineAfter: index(defaults, "fakeOfflineAfter"))
            let dependencies = AppDependencies(
                cameras: CameraRepository(fileURL: directory.appending(path: "cameras.json")),
                appSettings: AppSettingsRepository(defaults: settings),
                detectorSettings: DetectorSettingsRepository(defaults: UserDefaults(suiteName: suite + ".detector")!),
                credentials: InMemoryCredentialsStore(),
                trust: TofuTrustStore(fileURL: nil),
                localNetwork: LocalNetworkAccess(defaults: UserDefaults(suiteName: suite)!),
                network: NetworkMonitor(source: network),
                speakerLosses: SystemSpeakerLossSource()
            )
            let players: [String: (FakeVideoPlayer.Script, String, CGFloat)] = Dictionary(
                uniqueKeysWithValues: cameras.enumerated().map { index, camera in
                    (
                        "rtsp://fake.invalid/\(index)",
                        (scripts[index % max(scripts.count, 1)], camera.name, CGFloat(index) / CGFloat(count))
                    )
                })
            let model = AppModel(
                dependencies: dependencies,
                makePlayer: { source in
                    guard case .rtsp(let url) = source, let (script, name, hue) = players[url] else {
                        return FakeVideoPlayer(script: .never, name: "?", hue: 0)
                    }
                    return FakeVideoPlayer(script: script, name: name, hue: hue)
                },
                destination: .monitor
            )
            if let paused = index(defaults, "fakePaused"), cameras.indices.contains(paused) {
                model.monitor.pause(cameras[paused].id, announcing: false)
            }
            if let open = index(defaults, "fakeFullscreen"), cameras.indices.contains(open) {
                model.monitor.open(cameras[open].id)
            }
            return model
        }
    }

    /// Debug builds only: window shapes a simulator run by an agent cannot
    /// reach, since `simctl` can neither rotate a device nor split its screen.
    ///
    ///     -fakeLandscape YES     lays the app out in the device's landscape size,
    ///                            drawn turned a quarter (rotate the screenshot back)
    ///     -fakeWindowWidth 507   a Split View / Stage Manager window of this width
    struct DebugWindowShape: ViewModifier {
        private let landscape = UserDefaults.standard.bool(forKey: "fakeLandscape")
        private let width = UserDefaults.standard.object(forKey: "fakeWindowWidth").map { _ in
            CGFloat(UserDefaults.standard.integer(forKey: "fakeWindowWidth"))
        }

        func body(content: Content) -> some View {
            if landscape {
                GeometryReader { geometry in
                    content
                        .frame(width: geometry.size.height, height: geometry.size.width)
                        .rotationEffect(.degrees(-90))
                        .position(x: geometry.size.width / 2, y: geometry.size.height / 2)
                }
                .ignoresSafeArea()
            } else if let width {
                HStack(spacing: 0) {
                    content.frame(width: width)
                    Color.gray.ignoresSafeArea()
                }
            } else {
                content
            }
        }
    }

    /// A Wi-Fi network that can be scripted to drop.
    private final class ScriptedPathSource: NetworkPathSource {
        private let offlineAfter: Int?
        private let handler = Mutex<(@Sendable (NetworkPathSnapshot) -> Void)?>(nil)

        init(offlineAfter: Int?) {
            self.offlineAfter = offlineAfter
        }

        func start(onUpdate: @escaping @Sendable (NetworkPathSnapshot) -> Void) {
            handler.withLock { $0 = onUpdate }
            onUpdate(NetworkPathSnapshot(status: .satisfied, interfaces: [.wifi], interfaceNames: ["en0"]))
            guard let offlineAfter else { return }
            Task { @MainActor in
                try? await Task.sleep(for: .seconds(offlineAfter))
                FakeNetwork.isUp = false
                onUpdate(NetworkPathSnapshot(status: .unsatisfied, interfaces: []))
            }
        }

        func cancel() {}
    }
#endif

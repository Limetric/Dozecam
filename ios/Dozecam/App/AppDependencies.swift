import Foundation

/// The app's long-lived services, built once at launch and handed to every
/// model, so onboarding, settings and (later) the monitor share one camera
/// list, one settings store and one trust store. Tests build their own with
/// `isolated(in:)`.
@MainActor
final class AppDependencies {
    let cameras: CameraRepository
    let appSettings: AppSettingsRepository
    let detectorSettings: DetectorSettingsRepository
    let credentials: any CredentialsStore
    let trust: TofuTrustStore
    let localNetwork: LocalNetworkAccess
    let network: NetworkMonitor

    init(
        cameras: CameraRepository,
        appSettings: AppSettingsRepository,
        detectorSettings: DetectorSettingsRepository,
        credentials: any CredentialsStore,
        trust: TofuTrustStore,
        localNetwork: LocalNetworkAccess,
        network: NetworkMonitor
    ) {
        self.cameras = cameras
        self.appSettings = appSettings
        self.detectorSettings = detectorSettings
        self.credentials = credentials
        self.trust = trust
        self.localNetwork = localNetwork
        self.network = network
    }

    /// The real stores: Application Support, UserDefaults.standard, the
    /// Keychain and the shared trust store.
    static func live() -> AppDependencies {
        AppDependencies(
            cameras: CameraRepository(),
            appSettings: AppSettingsRepository(),
            detectorSettings: DetectorSettingsRepository(),
            credentials: KeychainCredentialsStore(),
            trust: .shared,
            localNetwork: LocalNetworkAccess(),
            network: NetworkMonitor()
        )
    }

    /// Stores that touch nothing shared: files under `directory`, a fresh
    /// UserDefaults suite, in-memory credentials and pins. For tests and
    /// previews.
    static func isolated(
        in directory: URL = FileManager.default.temporaryDirectory.appending(path: "deps-\(UUID().uuidString)"),
        credentials: any CredentialsStore = InMemoryCredentialsStore(),
        localNetworkProbe: any LocalNetworkProbe = SystemLocalNetworkProbe(),
        networkSource: any NetworkPathSource = SystemNetworkPathSource()
    ) -> AppDependencies {
        let suite = "app.dozecam.isolated.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        return AppDependencies(
            cameras: CameraRepository(fileURL: directory.appending(path: "cameras.json")),
            appSettings: AppSettingsRepository(defaults: UserDefaults(suiteName: suite + ".app")!),
            detectorSettings: DetectorSettingsRepository(defaults: UserDefaults(suiteName: suite + ".detector")!),
            credentials: credentials,
            trust: TofuTrustStore(fileURL: nil),
            localNetwork: LocalNetworkAccess(probe: localNetworkProbe, defaults: defaults),
            network: NetworkMonitor(source: networkSource)
        )
    }
}

/// Credentials held in memory only: for tests, previews and simulator runs
/// that must not touch the Keychain.
final class InMemoryCredentialsStore: CredentialsStore, @unchecked Sendable {
    private let lock = NSLock()
    private var stored: ProtectCredentials?

    init(_ credentials: ProtectCredentials? = nil) { stored = credentials }

    func save(_ credentials: ProtectCredentials) throws { lock.withLock { stored = credentials } }
    func load() throws -> ProtectCredentials? { lock.withLock { stored } }
    func clear() throws { lock.withLock { stored = nil } }
}

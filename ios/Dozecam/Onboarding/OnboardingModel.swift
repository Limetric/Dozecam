import Foundation
import Observation

/// Getting cameras into the app: sign in to a Protect console and import its
/// cameras, or add one by stream URL (shared/spec/protect.md). The
/// counterpart of Android's `OnboardingViewModel`, with the same rules:
///
/// - Sign-in is address, username and password. The public Integration API is
///   preferred, with an API key reused from the last run or minted once
///   ("Dozecam"); the legacy API is the fallback, and neither is an error.
/// - A console's certificate is confirmed by the user on first contact and
///   again when it changes, and pinned only once a sign-in behind it succeeds.
/// - Nothing is pre-selected in the picker, every import is the Medium
///   channel, and re-importing keeps a camera's enabled setting.
/// - Local-network access is explained before iOS asks, and a refusal is
///   reported as that, with the way to Settings, never as a timeout.
///
/// The model is long-lived (one per app), so `finish()` puts it back at the
/// start for the next visit.
@MainActor
@Observable
final class OnboardingModel {
    /// The screens pushed on the onboarding stack; the start screen is its
    /// root.
    enum Route: Hashable {
        case manualEntry
        case signIn
        case certificate
        case cameras
        case done
    }

    /// Work in progress, which disables the controls that would start more.
    enum Activity: Equatable {
        case connecting
        case importing
    }

    /// The local-network sheet: explained before iOS shows its prompt, and
    /// what to do after a refusal.
    enum LocalNetworkPrompt: Equatable {
        /// Why Dozecam needs the access; continuing puts the iOS prompt up.
        case explain
        /// The probe is out, waiting on the user's answer or the host's.
        case asking
        /// Refused: the way back is Settings.
        case denied
    }

    /// What the local-network probe connects to, and what follows it.
    enum LocalNetworkTarget: Equatable {
        /// Signing in comes next.
        case console(host: String, port: UInt16)
        /// A camera added by hand, already saved; the done screen comes next.
        case camera(host: String, port: UInt16)

        var host: String {
            switch self {
            case .console(let host, _), .camera(let host, _): host
            }
        }

        var port: UInt16 {
            switch self {
            case .console(_, let port), .camera(_, let port): port
            }
        }
    }

    /// A console certificate waiting on the user's word. `pinned` is nil on
    /// first contact and carries the trusted fingerprint when the console now
    /// presents another.
    struct CertificatePrompt: Equatable {
        let endpoint: TofuEndpoint
        let presented: String
        let pinned: String?

        var isChange: Bool { pinned != nil }
    }

    var path: [Route] = []

    var host = ""
    var username = ""
    var password = ""

    private(set) var activity: Activity?
    /// Shown on the sign-in form.
    private(set) var signInError: String?
    /// Shown on the camera picker.
    private(set) var importError: String?
    private(set) var localNetworkPrompt: LocalNetworkPrompt?
    private(set) var localNetworkTarget: LocalNetworkTarget?
    private(set) var certificate: CertificatePrompt?
    private(set) var cameras: [DiscoveredCamera] = []
    private(set) var selectedCameraIDs: Set<String> = []
    /// Which API the picker's cameras came from, for tests and the footer.
    private(set) var usesPublicAPI = false
    /// Cameras added by this run, for the done screen.
    private(set) var addedCount = 0
    /// The form behind `.manualEntry`, fresh for each visit.
    private(set) var manualEntry: ManualCameraEntryModel

    let dependencies: AppDependencies

    /// Builds the pinned session for a sign-in; `confirming` is the
    /// fingerprint the user has just accepted. Injectable so tests can serve
    /// the console from a stub and stage a certificate refusal.
    @ObservationIgnored private let makeConsoleSession: @MainActor (_ confirming: String?) -> PinnedSession

    /// What the picker's selection resolves to at import time.
    private enum Discovery {
        case publicAPI(ProtectPublicApiClient, apiKey: String, cameras: [PublicCamera])
        case legacy(ProtectApiClient, cameras: [ProtectCamera])
    }

    /// The fields as they were when a sign-in started. Everything behind it
    /// uses this snapshot, so one console's credentials never meet another's
    /// address while the form is edited.
    private struct SignIn {
        let host: String
        let username: String
        let password: String
        let baseURL: URL
        let endpoint: TofuEndpoint
    }

    @ObservationIgnored private var signedIn: SignIn?
    @ObservationIgnored private var loginSession: ProtectSession?
    @ObservationIgnored private var consoleSession: PinnedSession?
    @ObservationIgnored private var discovery: Discovery?
    @ObservationIgnored private var probe: Task<LocalNetworkAccessStatus, Never>?
    #if DEBUG
        @ObservationIgnored var appliedLaunchArguments = false
    #endif

    convenience init(dependencies: AppDependencies) {
        let trust = dependencies.trust
        self.init(dependencies: dependencies) { PinnedSessionFactory(store: trust).consoleSession(confirming: $0) }
    }

    init(
        dependencies: AppDependencies,
        consoleSession: @escaping @MainActor (_ confirming: String?) -> PinnedSession
    ) {
        self.dependencies = dependencies
        makeConsoleSession = consoleSession
        manualEntry = ManualCameraEntryModel(cameras: dependencies.cameras)
        loadStoredSignIn()
    }

    // MARK: - Start

    /// Whether there is anything to go back to: with cameras, onboarding was
    /// reached to add more and can be left without adding any.
    var canLeave: Bool { !dependencies.cameras.cameras.isEmpty }

    func startSignIn() {
        signInError = nil
        path = [.signIn]
    }

    func startManualEntry() {
        manualEntry = ManualCameraEntryModel(cameras: dependencies.cameras)
        path = [.manualEntry]
    }

    /// Back to the start screen, ready for the next visit: when onboarding
    /// is left, and for "Add More Cameras" on the done screen.
    func finish() {
        cancelLocalNetworkPrompt(continuing: false)
        consoleSession?.invalidate()
        consoleSession = nil
        loginSession = nil
        signedIn = nil
        discovery = nil
        certificate = nil
        cameras = []
        selectedCameraIDs = []
        usesPublicAPI = false
        signInError = nil
        importError = nil
        addedCount = 0
        activity = nil
        path = []
        loadStoredSignIn()
    }

    // MARK: - Signing in

    var canSignIn: Bool {
        activity == nil && ProtectApiClient.baseURL(for: host) != nil
            && !username.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && !password.isEmpty
    }

    /// The sign-in button. Local-network access comes first: explained
    /// before iOS is made to ask, or reported if it was refused.
    func signIn() async {
        guard canSignIn, let target = consoleTarget() else { return }
        signInError = nil
        guard needsLocalNetworkPrompt(for: target.host) else {
            await connect(confirming: nil)
            return
        }
        localNetworkTarget = target
        localNetworkPrompt = dependencies.localNetwork.status == .denied ? .denied : .explain
    }

    /// The user trusts the certificate on screen: sign in behind it, and pin
    /// it if that works.
    func trustCertificate() async {
        guard let certificate, activity == nil else { return }
        await connect(confirming: certificate.presented)
    }

    /// The user does not trust it: back to the form, nothing pinned.
    func rejectCertificate() {
        certificate = nil
        path = [.signIn]
    }

    private func consoleTarget() -> LocalNetworkTarget? {
        guard let baseURL = ProtectApiClient.baseURL(for: host), let endpoint = TofuEndpoint(url: baseURL) else {
            return nil
        }
        return .console(host: endpoint.host, port: UInt16(clamping: endpoint.port))
    }

    private func connect(confirming fingerprint: String?) async {
        guard let baseURL = ProtectApiClient.baseURL(for: host), let endpoint = TofuEndpoint(url: baseURL) else {
            return
        }
        let signIn = SignIn(
            host: ProtectCameraImport.consoleHost(forInput: host),
            username: username,
            password: password,
            baseURL: baseURL,
            endpoint: endpoint
        )
        activity = .connecting
        signInError = nil
        defer { activity = nil }

        let session = makeConsoleSession(fingerprint)
        consoleSession?.invalidate()
        consoleSession = session
        do {
            let (login, found) = try await session.surfacingTrustFailures {
                let legacy = ProtectApiClient(baseURL: baseURL, urlSession: session.urlSession)
                let login = try await legacy.login(username: signIn.username, password: signIn.password)
                // Only now: a wrong password must never leave a console
                // pinned. Confirming a changed certificate also forgets the
                // media pins learned through the old one.
                if let fingerprint {
                    dependencies.trust.confirmConsole(endpoint, fingerprint: fingerprint)
                }
                let found = try await discover(signIn, legacy: legacy, login: login, session: session)
                return (login, found)
            }
            signedIn = signIn
            loginSession = login
            discovery = found
            certificate = nil
            switch found {
            case .publicAPI(_, _, let cameras):
                self.cameras = cameras.map(ProtectCameraImport.discovered)
                usesPublicAPI = true
            case .legacy(_, let cameras):
                self.cameras = cameras.map(ProtectCameraImport.discovered)
                usesPublicAPI = false
            }
            // Importing is opt-in, and a selection from an earlier discovery
            // is stale.
            selectedCameraIDs = []
            importError = nil
            path = [.signIn, .cameras]
        } catch is CancellationError {
            return
        } catch let refusal as TofuTrustError where refusal.needsConfirmation {
            guard let presented = refusal.presentedFingerprint else { return }
            certificate = CertificatePrompt(
                endpoint: refusal.endpoint, presented: presented, pinned: refusal.pinnedFingerprint)
            path = [.signIn, .certificate]
        } catch {
            await handleConnectFailure(error)
        }
    }

    /// The public API when the console can serve it with a key, the legacy
    /// API otherwise. A stored key for this console and user is tried first,
    /// and one is minted only when there is none or it no longer works, so
    /// re-running onboarding does not litter the console with keys.
    private func discover(
        _ signIn: SignIn,
        legacy: ProtectApiClient,
        login: ProtectSession,
        session: PinnedSession
    ) async throws -> Discovery {
        let publicAPI = ProtectPublicApiClient(baseURL: signIn.baseURL, urlSession: session.urlSession)
        let stored = (try? dependencies.credentials.load())?
            .apiKey(reusableFor: signIn.host, username: signIn.username)
        // The key kept for next time: only a console that rejects a key loses
        // it (shared/spec/protect.md).
        var kept = stored
        if let stored {
            switch try await publicCameras(publicAPI, apiKey: stored) {
            case .cameras(let cameras):
                try save(signIn, apiKey: stored)
                return .publicAPI(publicAPI, apiKey: stored, cameras: cameras)
            case .unsupported:
                // No public API here: a new key would not help.
                try save(signIn, apiKey: stored)
                return .legacy(legacy, cameras: try await legacy.bootstrap(login).cameras)
            case .keyRejected:
                kept = nil
            }
        }
        if let minted = try await mintApiKey(legacy, login: login), minted != stored {
            // Saved as soon as it is issued: if the camera list then fails,
            // the retry reuses this key instead of minting another.
            try save(signIn, apiKey: minted)
            kept = minted
            switch try await publicCameras(publicAPI, apiKey: minted) {
            case .cameras(let cameras):
                return .publicAPI(publicAPI, apiKey: minted, cameras: cameras)
            case .unsupported:
                break
            case .keyRejected:
                // A fresh key refused: this account has no rights there.
                kept = nil
            }
        }
        try save(signIn, apiKey: kept)
        return .legacy(legacy, cameras: try await legacy.bootstrap(login).cameras)
    }

    /// What the public camera list says about the console and the key.
    enum PublicListing {
        case cameras([PublicCamera])
        /// 401 or 403: this key is no longer accepted.
        case keyRejected
        /// 404, or an answer that is not the API's: Protect before 5.3. The
        /// legacy API is the fallback.
        case unsupported
    }

    /// Anything else (an unreachable console, 429, a server error) says
    /// nothing about the key or the API, so it is thrown: the sign-in fails
    /// and the stored key is kept, rather than minting another on every
    /// retry.
    private func publicCameras(_ api: ProtectPublicApiClient, apiKey: String) async throws -> PublicListing {
        do {
            return .cameras(try await api.cameras(apiKey: apiKey))
        } catch let error as ProtectAPIError {
            switch error {
            case .unauthorized, .forbidden: return .keyRejected
            case .notFound, .invalidResponse: return .unsupported
            default: throw error
            }
        }
    }

    /// A new API key, or nil when the console cannot issue one (Protect
    /// before 5.3, an account that does not own the console).
    private func mintApiKey(_ legacy: ProtectApiClient, login: ProtectSession) async throws -> String? {
        do {
            return try await legacy.createApiKey(login, name: ProtectCameraImport.apiKeyName)
        } catch is CancellationError {
            throw CancellationError()
        } catch {
            return nil
        }
    }

    private func save(_ signIn: SignIn, apiKey: String?) throws {
        try dependencies.credentials.save(
            ProtectCredentials(host: signIn.host, username: signIn.username, password: signIn.password, apiKey: apiKey)
        )
    }

    private func handleConnectFailure(_ failure: any Error) async {
        certificate = nil
        path = [.signIn]
        // A remembered grant can have been withdrawn in Settings since, and
        // iOS says nothing: a connection that failed without reaching the
        // console re-checks it, so the refusal is reported as such.
        if dependencies.localNetwork.status == .granted, Self.neverReachedConsole(failure),
            let target = consoleTarget()
        {
            await dependencies.localNetwork.refresh(probing: target.host, port: target.port, timeout: .seconds(5))
        }
        // Checked first: without the access the connection is held back
        // before any TLS happens, and the timeout that surfaces says nothing
        // about the real cause.
        if dependencies.localNetwork.status == .denied, let target = consoleTarget() {
            signInError = Self.localNetworkDeniedMessage
            localNetworkTarget = target
            localNetworkPrompt = .denied
            return
        }
        signInError = Self.message(for: failure, host: ProtectCameraImport.consoleHost(forInput: host))
    }

    /// A transport failure (no HTTP answer at all), as a withdrawn
    /// local-network grant produces; an HTTP status says the console answered.
    static func neverReachedConsole(_ failure: any Error) -> Bool {
        switch failure {
        case let error as ProtectAPIError:
            if case .unreachable = error { return true }
            return false
        case is URLError:
            return true
        default:
            return false
        }
    }

    // MARK: - Picking and importing

    func toggleCamera(_ id: String) {
        if selectedCameraIDs.contains(id) {
            selectedCameraIDs.remove(id)
        } else {
            selectedCameraIDs.insert(id)
        }
    }

    var allCamerasSelected: Bool { !cameras.isEmpty && selectedCameraIDs.count == cameras.count }

    func selectAllCameras(_ selected: Bool) {
        selectedCameraIDs = selected ? Set(cameras.map(\.id)) : []
    }

    var canImport: Bool { activity == nil && !selectedCameraIDs.isEmpty }

    /// Imports the selected cameras, in the console's order, one upsert each,
    /// then shows the done screen. A failure stays on the picker with what
    /// went wrong; cameras imported before it are kept.
    func importSelected() async {
        guard canImport, let discovery, let signedIn, let session = consoleSession else { return }
        activity = .importing
        importError = nil
        defer { activity = nil }
        let selected = selectedCameraIDs
        let consoleHost = signedIn.host
        let existing = dependencies.cameras.cameras
        var imported = 0
        do {
            try await session.surfacingTrustFailures {
                switch discovery {
                case .publicAPI(let api, let apiKey, let cameras):
                    for camera in cameras where selected.contains(camera.id) {
                        let stored = try await ProtectCameraImport.importCamera(
                            camera, api: api, apiKey: apiKey, consoleHost: consoleHost, existing: existing)
                        try await dependencies.cameras.upsert(stored)
                        imported += 1
                        // Done: a retry after a later failure leaves it be.
                        selectedCameraIDs.remove(camera.id)
                    }
                case .legacy(let api, let cameras):
                    for camera in cameras where selected.contains(camera.id) {
                        let stored = try await ProtectCameraImport.importCamera(
                            camera, api: api, consoleHost: consoleHost, existing: existing
                        ) { cameraId, channelId in
                            try await self.withFreshSessionOn401(api, signedIn) { login in
                                try await api.enableRtsp(login, cameraId: cameraId, channelId: channelId)
                            }
                        }
                        // A camera with no channels has nothing to stream.
                        guard let stored else { continue }
                        try await dependencies.cameras.upsert(stored)
                        imported += 1
                        selectedCameraIDs.remove(camera.id)
                    }
                }
            }
            addedCount += imported
            path = [.signIn, .cameras, .done]
        } catch is CancellationError {
            return
        } catch {
            addedCount += imported
            importError = Self.message(for: error, host: consoleHost)
        }
    }

    /// The login session can expire while the picker sits open: sign in again
    /// once with the same credentials rather than strand the user on an
    /// error that retrying cannot clear.
    private func withFreshSessionOn401<T>(
        _ api: ProtectApiClient,
        _ signIn: SignIn,
        _ body: (ProtectSession) async throws -> T
    ) async throws -> T {
        guard let current = loginSession else {
            throw ProtectAPIError.notSignedIn("Sign in to the console again.")
        }
        do {
            return try await body(current)
        } catch let error as ProtectAPIError where error.statusCode == 401 {
            let renewed = try await api.login(username: signIn.username, password: signIn.password)
            loginSession = renewed
            return try await body(renewed)
        }
    }

    // MARK: - Adding a camera by URL

    /// The manual form saved `camera`. Its host is on the LAN, so the
    /// local-network question is explained and asked here, before the monitor
    /// would trip over it unexplained.
    func manualCameraSaved(_ camera: Camera) {
        addedCount += 1
        if let target = Self.cameraTarget(camera.url), needsLocalNetworkPrompt(for: target.host) {
            localNetworkTarget = target
            localNetworkPrompt = dependencies.localNetwork.status == .denied ? .denied : .explain
            return
        }
        path = [.manualEntry, .done]
    }

    private static func cameraTarget(_ url: String) -> LocalNetworkTarget? {
        guard let components = URLComponents(string: url), let host = components.host, !host.isEmpty else {
            return nil
        }
        return .camera(host: host, port: UInt16(clamping: components.port ?? 554))
    }

    // MARK: - Local-network access

    /// Asking is pointless once granted, and loopback (the testbed) never
    /// needs it.
    private func needsLocalNetworkPrompt(for host: String) -> Bool {
        dependencies.localNetwork.status != .granted && !Self.isLoopback(host)
    }

    static func isLoopback(_ host: String) -> Bool {
        let host = host.lowercased().trimmingCharacters(in: CharacterSet(charactersIn: "[]"))
        return host == "localhost" || host == "::1" || host.hasPrefix("127.")
    }

    /// "Continue" on the explanation, and "Try Again" after a refusal: probes
    /// the target, which puts the iOS prompt up the first time. A refusal
    /// shows as soon as it is seen, without ending the probe: while the prompt
    /// is up the connection can already read as refused, and an Allow tapped
    /// afterwards still lets it through and carries on.
    func allowLocalNetwork() async {
        guard let target = localNetworkTarget, localNetworkPrompt != nil, probe == nil else { return }
        let access = dependencies.localNetwork
        // After a refusal iOS never asks again, so there is no answer to wait
        // for, only the connection's.
        let timeout: Duration = access.status == .denied ? .seconds(5) : .seconds(30)
        localNetworkPrompt = .asking
        let watcher = Task { [weak self] in
            for await status in access.statusUpdates().dropFirst() where status == .denied {
                guard let self, self.probe != nil else { return }
                localNetworkPrompt = .denied
            }
        }
        let probe = Task { await access.requestAccess(probing: target.host, port: target.port, timeout: timeout) }
        self.probe = probe
        let status = await probe.value
        watcher.cancel()
        // Left while waiting: `cancelLocalNetworkPrompt` has already moved on.
        guard self.probe == probe, localNetworkPrompt != nil else { return }
        self.probe = nil
        if status == .denied {
            localNetworkPrompt = .denied
            return
        }
        // Granted, or undetermined (a console that is down says nothing about
        // the grant): carry on, and let the connection say what is wrong.
        localNetworkPrompt = nil
        localNetworkTarget = nil
        await proceed(after: target)
    }

    /// "Not now", or the sheet swiped away. For a console that ends the
    /// attempt; a camera added by hand is already saved, so it goes on to the
    /// done screen, and the monitor reports the missing access later.
    func cancelLocalNetworkPrompt(continuing: Bool = true) {
        probe?.cancel()
        probe = nil
        let target = localNetworkTarget
        localNetworkPrompt = nil
        localNetworkTarget = nil
        if continuing, case .camera = target {
            path = [.manualEntry, .done]
        }
    }

    private func proceed(after target: LocalNetworkTarget) async {
        switch target {
        case .console:
            await connect(confirming: nil)
        case .camera:
            path = [.manualEntry, .done]
        }
    }

    // MARK: - Messages

    static let localNetworkDeniedMessage =
        "Dozecam needs local network access to reach the console. Turn on Local Network for Dozecam in Settings."

    /// What the user is told when signing in or importing fails, in the terms
    /// they can act on. The mirror of Android's `handleConnectFailure`.
    static func message(for failure: any Error, host: String) -> String {
        switch failure {
        case ProtectAPIError.unauthorized:
            return "The console did not accept this username and password. "
                + "Use a local account on the console, not a Ubiquiti cloud account."
        case ProtectAPIError.forbidden:
            return "This account does not have the rights Dozecam needs. Give it access to Protect, "
                + "with permission to manage cameras, or sign in with another account."
        case ProtectAPIError.notFound:
            return "No Protect console answered at \(host). Check the address."
        case ProtectAPIError.unreachable(let error):
            return unreachableMessage(error, host: host)
        case let refusal as TofuTrustError:
            return refusal.needsConfirmation
                ? "The console's certificate changed. Sign in again to confirm it."
                : "The console's certificate could not be checked."
        case let error as LocalizedError:
            return error.errorDescription ?? "Connection failed."
        default:
            return "Connection failed."
        }
    }

    private static func unreachableMessage(_ error: URLError, host: String) -> String {
        switch error.code {
        case .timedOut, .cannotConnectToHost, .cannotFindHost, .dnsLookupFailed, .networkConnectionLost,
            .notConnectedToInternet:
            return "Could not reach the console at \(host). Check the address, "
                + "and that this device is on the same network as the console."
        case .secureConnectionFailed, .serverCertificateUntrusted, .serverCertificateHasBadDate,
            .serverCertificateNotYetValid, .serverCertificateHasUnknownRoot, .clientCertificateRejected:
            return "A secure connection to the console at \(host) failed."
        default:
            return "Could not reach the console at \(host): \(error.localizedDescription)"
        }
    }

    // MARK: -

    private func loadStoredSignIn() {
        guard let stored = try? dependencies.credentials.load() else { return }
        host = stored.host
        username = stored.username
        password = stored.password
    }
}

#if DEBUG
    extension OnboardingModel {
        /// Debug builds only: `-onboardingStep <step>` shows one step with
        /// sample content, since nothing can tap through onboarding in a
        /// simulator run by an agent. `-onboardingStep manualEntry` with
        /// `-onboardingManualName`, `-onboardingManualURL` and
        /// `-onboardingManualSave YES` adds that camera through the form's
        /// own save. Applied once.
        func applyLaunchArguments(_ defaults: UserDefaults = .standard) async {
            guard !appliedLaunchArguments else { return }
            appliedLaunchArguments = true
            guard let step = defaults.string(forKey: "onboardingStep") else { return }
            let sampleA =
                "FF:42:07:9C:15:EF:15:79:5E:12:BF:EC:89:17:A5:6C:75:51:56:06:AE:94:F2:CD:29:56:85:FE:1E:06:1D:D8"
            let sampleB =
                "95:84:E4:14:47:DE:E9:48:C5:8E:2A:79:1B:FA:72:20:3D:FE:6E:A1:1F:6D:F3:23:F4:6C:C5:1D:2E:6A:20:B7"
            let endpoint = TofuEndpoint(host: "192.168.1.1", port: 443)
            if host.isEmpty {
                host = "192.168.1.1"
                username = "dozecam"
                password = "password"
            }
            switch step {
            case "manualEntry":
                startManualEntry()
                if let name = defaults.string(forKey: "onboardingManualName") { manualEntry.name = name }
                if let url = defaults.string(forKey: "onboardingManualURL") { manualEntry.url = url }
                if defaults.bool(forKey: "onboardingManualSave"), let camera = await manualEntry.save() {
                    manualCameraSaved(camera)
                }
            case "signIn":
                path = [.signIn]
            case "signInError":
                path = [.signIn]
                signInError = Self.message(for: ProtectAPIError.unauthorized("401"), host: host)
            case "connecting":
                path = [.signIn]
                activity = .connecting
            case "localNetwork", "localNetworkAsking", "localNetworkDenied":
                path = [.signIn]
                localNetworkTarget = .console(host: "192.168.1.1", port: 443)
                localNetworkPrompt =
                    step == "localNetwork" ? .explain : step == "localNetworkAsking" ? .asking : .denied
            case "certificate":
                certificate = CertificatePrompt(endpoint: endpoint, presented: sampleA, pinned: nil)
                path = [.signIn, .certificate]
            case "certificateChanged":
                certificate = CertificatePrompt(endpoint: endpoint, presented: sampleB, pinned: sampleA)
                path = [.signIn, .certificate]
            case "cameras", "camerasError":
                cameras = [
                    DiscoveredCamera(id: "cam1", name: "Nursery", detail: "Medium"),
                    DiscoveredCamera(id: "cam2", name: "Camera", detail: "Medium"),
                    DiscoveredCamera(id: "cam3", name: "Landing", detail: "Medium"),
                ]
                selectedCameraIDs = ["cam1"]
                usesPublicAPI = true
                if step == "camerasError" {
                    importError = Self.message(for: ProtectAPIError.forbidden("403"), host: host)
                }
                path = [.signIn, .cameras]
            case "done":
                addedCount = 2
                path = [.signIn, .cameras, .done]
            default:
                break
            }
        }
    }
#endif

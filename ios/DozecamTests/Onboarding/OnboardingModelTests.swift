import Foundation
import Testing

@testable import Dozecam

/// The onboarding flow against a stubbed console, held to Android's
/// `OnboardingViewModel` and shared/spec/protect.md.
@MainActor
struct OnboardingModelTests {
    private let apiKeyPath = "/proxy/users/api/v2/user/self/keys"
    private let publicCamerasPath = "/proxy/protect/integration/v1/cameras"

    // MARK: Signing in over the public API

    @Test func signingInMintsAnApiKeyOnceAndReusesItOnTheNextRun() async throws {
        let harness = OnboardingHarness()
        harness.fill()
        harness.stub.enqueueLogin()
        try harness.stub.enqueueFixture("protect-api/legacy/api-key.json")
        try harness.stub.enqueueFixture("protect-api/public/cameras.json")

        await harness.model.signIn()

        #expect(harness.requestLines == ["POST /api/auth/login", "POST \(apiKeyPath)", "GET \(publicCamerasPath)"])
        #expect(harness.stub.requests[2].header("X-API-KEY") == "abcdef123456")
        #expect(harness.model.path == [.signIn, .cameras])
        #expect(harness.model.usesPublicAPI)
        #expect(harness.model.cameras.map(\.id) == ["cam1", "cam2", "cam3"])
        #expect(harness.model.cameras.map(\.name) == ["Nursery", "Camera", "Landing"])
        #expect(harness.model.cameras.allSatisfy { $0.detail == "Medium" })
        // Nothing is pre-selected.
        #expect(harness.model.selectedCameraIDs.isEmpty)
        #expect(
            try harness.credentials.load()
                == ProtectCredentials(host: "192.168.1.1", username: "user", password: "pass", apiKey: "abcdef123456"))

        // The next run is prefilled from the stored sign-in and reuses the key.
        harness.replaceModel()
        #expect(harness.model.host == "192.168.1.1")
        #expect(harness.model.username == "user")
        #expect(harness.model.password == "pass")
        harness.stub.enqueueLogin()
        try harness.stub.enqueueFixture("protect-api/public/cameras.json")
        await harness.model.signIn()

        #expect(harness.requestLines.dropFirst(3) == ["POST /api/auth/login", "GET \(publicCamerasPath)"])
        #expect(harness.stub.requests[4].header("X-API-KEY") == "abcdef123456")
        #expect(harness.model.path == [.signIn, .cameras])
    }

    @Test func aRevokedKeyIsReplacedOnce() async throws {
        let harness = OnboardingHarness(
            stored: ProtectCredentials(host: "192.168.1.1", username: "user", password: "pass", apiKey: "revoked"))
        harness.stub.enqueueLogin()
        harness.stub.enqueue(status: 401, json: "{}")
        try harness.stub.enqueueFixture("protect-api/legacy/api-key.json")
        try harness.stub.enqueueFixture("protect-api/public/cameras.json")

        await harness.model.signIn()

        #expect(
            harness.requestLines == [
                "POST /api/auth/login", "GET \(publicCamerasPath)", "POST \(apiKeyPath)", "GET \(publicCamerasPath)",
            ])
        #expect(harness.stub.requests[1].header("X-API-KEY") == "revoked")
        #expect(harness.stub.requests[3].header("X-API-KEY") == "abcdef123456")
        #expect(try harness.credentials.load()?.apiKey == "abcdef123456")
        #expect(harness.model.usesPublicAPI)
    }

    /// A console that fails on its side, or cannot be reached, says nothing
    /// about the stored key: the sign-in fails and the key is kept, rather
    /// than minting another one on every retry.
    @Test(arguments: [
        StubConsole.Reply.response(status: 503, headers: [:], body: Data("{}".utf8)), .failure(.timedOut),
    ])
    func aTransientFailureKeepsTheStoredKey(reply: StubConsole.Reply) async throws {
        let harness = OnboardingHarness(
            stored: ProtectCredentials(host: "192.168.1.1", username: "user", password: "pass", apiKey: "valid"))
        harness.stub.enqueueLogin()
        harness.stub.enqueue(reply)

        await harness.model.signIn()

        #expect(harness.requestLines == ["POST /api/auth/login", "GET \(publicCamerasPath)"])
        #expect(try harness.credentials.load()?.apiKey == "valid")
        #expect(harness.model.signInError != nil)
        #expect(harness.model.path == [.signIn])
    }

    /// A key minted for one console or user is never sent to another.
    @Test func aKeyForAnotherConsoleIsNotTried() async throws {
        let harness = OnboardingHarness(
            stored: ProtectCredentials(host: "192.168.1.2", username: "user", password: "pass", apiKey: "other"))
        harness.fill()
        harness.stub.enqueueLogin()
        try harness.stub.enqueueFixture("protect-api/legacy/api-key.json")
        try harness.stub.enqueueFixture("protect-api/public/cameras.json")

        await harness.model.signIn()

        #expect(harness.requestLines == ["POST /api/auth/login", "POST \(apiKeyPath)", "GET \(publicCamerasPath)"])
        #expect(harness.stub.requests.allSatisfy { $0.header("X-API-KEY") != "other" })
    }

    // MARK: The legacy fallback

    @Test func anAccountThatCannotMintAKeyFallsBackToTheLegacyApi() async throws {
        let harness = OnboardingHarness()
        harness.fill()
        harness.stub.enqueueLogin()
        harness.stub.enqueue(status: 403, json: "{}")
        try harness.stub.enqueueFixture("protect-api/legacy/bootstrap.json")

        await harness.model.signIn()

        #expect(
            harness.requestLines == ["POST /api/auth/login", "POST \(apiKeyPath)", "GET /proxy/protect/api/bootstrap"])
        #expect(harness.model.signInError == nil)
        #expect(harness.model.path == [.signIn, .cameras])
        #expect(!harness.model.usesPublicAPI)
        #expect(harness.model.cameras.map(\.id) == ["cam1", "cam2", "cam3"])
        #expect(harness.model.cameras.map(\.detail) == ["Medium", "High", ""])
        #expect(harness.model.selectedCameraIDs.isEmpty)
        #expect(try harness.credentials.load()?.apiKey == nil)
    }

    @Test func olderFirmwareWithoutTheKeyEndpointFallsBackToo() async throws {
        let harness = OnboardingHarness()
        harness.fill()
        harness.stub.enqueueLogin()
        harness.stub.enqueue(status: 404, json: "{}")
        try harness.stub.enqueueFixture("protect-api/legacy/bootstrap.json")

        await harness.model.signIn()

        #expect(harness.model.path == [.signIn, .cameras])
        #expect(!harness.model.usesPublicAPI)
    }

    // MARK: Importing

    @Test func aPublicImportKeepsEnabledSettingsAndTheSharedIds() async throws {
        let harness = OnboardingHarness()
        try await harness.dependencies.cameras.upsert(
            Camera(id: "protect-cam1-1", name: "Old name", url: "rtsp://old", enabled: false))
        harness.fill()
        harness.stub.enqueueLogin()
        try harness.stub.enqueueFixture("protect-api/legacy/api-key.json")
        try harness.stub.enqueueFixture("protect-api/public/cameras.json")
        await harness.model.signIn()

        harness.model.toggleCamera("cam1")
        harness.model.toggleCamera("cam2")
        harness.model.toggleCamera("cam3")
        harness.model.toggleCamera("cam3")
        // cam1 already serves Medium; cam2 serves nothing, so it is enabled.
        try harness.stub.enqueueFixture("protect-api/public/rtsps-stream.json")
        harness.stub.enqueue(json: #"{"high": null, "medium": null, "low": null}"#)
        try harness.stub.enqueueFixture("protect-api/public/rtsps-stream-created.json")

        await harness.model.importSelected()

        #expect(
            harness.requestLines.dropFirst(3) == [
                "GET \(publicCamerasPath)/cam1/rtsps-stream",
                "GET \(publicCamerasPath)/cam2/rtsps-stream",
                "POST \(publicCamerasPath)/cam2/rtsps-stream",
            ])
        #expect(harness.stub.requests[5].bodyText.contains("medium"))
        #expect(harness.model.importError == nil)
        #expect(harness.model.path == [.signIn, .cameras, .done])
        #expect(harness.model.addedCount == 2)
        #expect(
            harness.dependencies.cameras.cameras == [
                Camera(
                    id: "protect-cam1-1", name: "Nursery", url: "rtsp://192.168.1.1:7447/aliasM",
                    protect: ProtectStream(cameraId: "cam1", channel: 1, consoleHost: "192.168.1.1"), enabled: false),
                Camera(
                    id: "protect-cam2-1", name: "Camera", url: "rtsp://192.168.1.1:7447/aliasM",
                    protect: ProtectStream(cameraId: "cam2", channel: 1, consoleHost: "192.168.1.1"), enabled: true),
            ])
    }

    @Test func aLegacyImportUsesTheServedAliasesAndSkipsCamerasWithoutChannels() async throws {
        let harness = OnboardingHarness()
        try await harness.dependencies.cameras.upsert(
            Camera(id: "protect-cam2-0", name: "Old", url: "rtsp://old", enabled: false))
        harness.fill()
        harness.stub.enqueueLogin()
        harness.stub.enqueue(status: 403, json: "{}")
        try harness.stub.enqueueFixture("protect-api/legacy/bootstrap.json")
        await harness.model.signIn()

        harness.model.selectAllCameras(true)
        #expect(harness.model.allCamerasSelected)
        await harness.model.importSelected()

        // Every alias was already served: nothing is changed on the console.
        #expect(harness.stub.requests.count == 3)
        #expect(harness.model.addedCount == 2)
        #expect(harness.model.path.last == .done)
        #expect(harness.dependencies.cameras.cameras.map(\.id) == ["protect-cam2-0", "protect-cam1-1"])
        #expect(harness.dependencies.cameras.cameras.map(\.enabled) == [false, true])
        #expect(
            harness.dependencies.cameras.cameras.map(\.url) == [
                "rtsp://192.168.1.1:7447/aliasH", "rtsp://192.168.1.1:7447/aliasM",
            ])
    }

    @Test func aLegacyImportEnablesRtspAndRenewsAnExpiredSessionOnce() async throws {
        let harness = OnboardingHarness()
        harness.fill()
        harness.stub.enqueueLogin(token: "first")
        harness.stub.enqueue(status: 403, json: "{}")
        harness.stub.enqueue(
            json: #"{"cameras": [{"id": "cam1", "name": "Nursery", "channels": [{"id": 1, "name": "Medium"}]}]}"#)
        await harness.model.signIn()

        harness.model.toggleCamera("cam1")
        harness.stub.enqueue(status: 401, json: "{}")
        harness.stub.enqueueLogin(token: "second")
        try harness.stub.enqueueFixture("protect-api/legacy/camera-rtsp-enabled.json")
        await harness.model.importSelected()

        #expect(
            harness.requestLines.dropFirst(3) == [
                "PATCH /proxy/protect/api/cameras/cam1",
                "POST /api/auth/login",
                "PATCH /proxy/protect/api/cameras/cam1",
            ])
        #expect(harness.stub.requests[3].header("Cookie") == "TOKEN=first")
        #expect(harness.stub.requests[5].header("Cookie") == "TOKEN=second")
        #expect(harness.model.importError == nil)
        #expect(harness.dependencies.cameras.cameras.map(\.url) == ["rtsp://192.168.1.1:7447/newAlias"])
    }

    @Test func aFailedImportStaysOnThePickerWithTheReason() async throws {
        let harness = OnboardingHarness()
        harness.fill()
        harness.stub.enqueueLogin()
        harness.stub.enqueue(status: 403, json: "{}")
        harness.stub.enqueue(
            json: #"{"cameras": [{"id": "cam1", "name": "Nursery", "channels": [{"id": 1, "name": "Medium"}]}]}"#)
        await harness.model.signIn()

        harness.model.toggleCamera("cam1")
        harness.stub.enqueue(status: 403, json: "{}")
        await harness.model.importSelected()

        #expect(harness.model.path == [.signIn, .cameras])
        #expect(harness.model.importError?.contains("rights") == true)
        #expect(harness.model.activity == nil)
        #expect(harness.dependencies.cameras.cameras.isEmpty)
    }

    /// Cameras already imported leave the selection, so a retry after a
    /// later camera failed imports only the rest and counts each camera once.
    @Test func aRetriedImportCountsEachCameraOnce() async throws {
        let harness = OnboardingHarness()
        harness.fill()
        harness.stub.enqueueLogin()
        harness.stub.enqueue(status: 403, json: "{}")
        harness.stub.enqueue(
            json: #"""
                {"cameras": [
                  {"id": "cam1", "name": "Nursery", "channels": [{"id": 1, "name": "Medium", "isRtspEnabled": true, "rtspAlias": "a1"}]},
                  {"id": "cam2", "name": "Hall", "channels": [{"id": 1, "name": "Medium"}]}
                ]}
                """#)
        await harness.model.signIn()
        harness.model.selectAllCameras(true)

        harness.stub.enqueue(status: 500, json: "{}")  // enabling RTSP on cam2 fails
        await harness.model.importSelected()
        #expect(harness.model.importError != nil)
        #expect(harness.model.selectedCameraIDs == ["cam2"])
        #expect(harness.dependencies.cameras.cameras.map(\.id) == ["protect-cam1-1"])

        harness.stub.enqueue(
            json:
                #"{"id": "cam2", "name": "Hall", "channels": [{"id": 1, "name": "Medium", "isRtspEnabled": true, "rtspAlias": "a2"}]}"#
        )
        await harness.model.importSelected()
        #expect(harness.model.path == [.signIn, .cameras, .done])
        #expect(harness.model.addedCount == 2)
        #expect(harness.dependencies.cameras.cameras.map(\.id) == ["protect-cam1-1", "protect-cam2-1"])
    }

    /// A key is saved the moment it is minted: a camera list that then fails
    /// is retried with it rather than minting another.
    @Test func aMintedKeyOutlivesAFailedCameraList() async throws {
        let harness = OnboardingHarness()
        harness.fill()
        harness.stub.enqueueLogin()
        try harness.stub.enqueueFixture("protect-api/legacy/api-key.json")
        harness.stub.enqueue(status: 503, json: "{}")
        await harness.model.signIn()
        #expect(harness.model.signInError != nil)
        #expect(try harness.credentials.load()?.apiKey == "abcdef123456")

        harness.stub.enqueueLogin()
        try harness.stub.enqueueFixture("protect-api/public/cameras.json")
        await harness.model.signIn()
        #expect(harness.requestLines.dropFirst(3) == ["POST /api/auth/login", "GET \(publicCamerasPath)"])
        #expect(harness.model.usesPublicAPI)
    }

    // MARK: Trust on first use

    @Test func firstContactAsksAndPinsOnlyOnceTheSignInBehindItSucceeds() async throws {
        let harness = OnboardingHarness()
        harness.fill()
        harness.handshakes = ["console-a"]
        harness.stub.enqueue(.failure(.cancelled))

        await harness.model.signIn()

        #expect(harness.model.path == [.signIn, .certificate])
        #expect(
            harness.model.certificate
                == .init(
                    endpoint: OnboardingHarness.console, presented: TestCertificates.consoleAFingerprint, pinned: nil))
        #expect(harness.dependencies.trust.pin(for: OnboardingHarness.console) == nil)
        #expect(harness.model.signInError == nil)

        harness.stub.enqueueLogin()
        harness.stub.enqueue(status: 403, json: "{}")
        try harness.stub.enqueueFixture("protect-api/legacy/bootstrap.json")
        await harness.model.trustCertificate()

        #expect(harness.sessionsConfirming == [nil, TestCertificates.consoleAFingerprint])
        #expect(harness.model.path == [.signIn, .cameras])
        #expect(harness.model.certificate == nil)
        #expect(
            harness.dependencies.trust.fingerprint(for: OnboardingHarness.console)
                == TestCertificates.consoleAFingerprint)
    }

    @Test func aWrongPasswordBehindTheConfirmedCertificatePinsNothing() async throws {
        let harness = OnboardingHarness()
        harness.fill()
        harness.handshakes = ["console-a"]
        harness.stub.enqueue(.failure(.cancelled))
        await harness.model.signIn()

        harness.stub.enqueue(status: 401, json: "{}")
        await harness.model.trustCertificate()

        #expect(harness.model.path == [.signIn])
        #expect(harness.model.signInError?.contains("username and password") == true)
        #expect(harness.dependencies.trust.pin(for: OnboardingHarness.console) == nil)
    }

    @Test func aChangedCertificateShowsBothFingerprintsAndReplacesThePin() async throws {
        let harness = OnboardingHarness()
        let media = TofuEndpoint(host: "192.168.1.1", port: 7443)
        harness.dependencies.trust.confirmConsole(
            OnboardingHarness.console, fingerprint: TestCertificates.consoleAFingerprint)
        _ = harness.dependencies.trust.evaluate(
            presented: "AA", at: media, role: .media(vouchedBy: OnboardingHarness.console))
        #expect(harness.dependencies.trust.fingerprint(for: media) == "AA")
        harness.fill()
        harness.handshakes = ["console-b"]
        harness.stub.enqueue(.failure(.cancelled))

        await harness.model.signIn()

        let prompt = try #require(harness.model.certificate)
        #expect(prompt.isChange)
        #expect(prompt.pinned == TestCertificates.consoleAFingerprint)
        #expect(prompt.presented == TestCertificates.consoleBFingerprint)
        #expect(harness.model.path == [.signIn, .certificate])

        harness.stub.enqueueLogin()
        harness.stub.enqueue(status: 403, json: "{}")
        try harness.stub.enqueueFixture("protect-api/legacy/bootstrap.json")
        await harness.model.trustCertificate()

        #expect(
            harness.dependencies.trust.fingerprint(for: OnboardingHarness.console)
                == TestCertificates.consoleBFingerprint)
        // The media pins learned through the old certificate are forgotten.
        #expect(harness.dependencies.trust.fingerprint(for: media) == nil)
    }

    @Test func rejectingTheCertificateGoesBackToTheForm() async throws {
        let harness = OnboardingHarness()
        harness.fill()
        harness.handshakes = ["console-a"]
        harness.stub.enqueue(.failure(.cancelled))
        await harness.model.signIn()

        harness.model.rejectCertificate()

        #expect(harness.model.path == [.signIn])
        #expect(harness.model.certificate == nil)
        #expect(harness.dependencies.trust.pin(for: OnboardingHarness.console) == nil)
    }

    // MARK: What the user is told

    @Test(arguments: [
        (401, "did not accept this username and password"),
        (403, "does not have the rights"),
        (404, "No Protect console answered at 192.168.1.1"),
        (500, "Login failed (500)"),
    ])
    func aRefusedLoginSaysWhy(status: Int, message: String) async throws {
        let harness = OnboardingHarness()
        harness.fill()
        harness.stub.enqueue(status: status, json: "{}")

        await harness.model.signIn()

        #expect(harness.model.path == [.signIn])
        #expect(harness.model.activity == nil)
        let error = try #require(harness.model.signInError)
        #expect(error.contains(message), "\(error)")
        #expect(try harness.credentials.load() == nil)
    }

    @Test(arguments: [URLError.Code.cannotConnectToHost, .timedOut, .cannotFindHost])
    func anUnreachableConsoleSaysSo(code: URLError.Code) async throws {
        let harness = OnboardingHarness()
        harness.fill()
        harness.stub.enqueue(.failure(code))

        await harness.model.signIn()

        #expect(harness.model.signInError?.hasPrefix("Could not reach the console at 192.168.1.1.") == true)
        #expect(harness.model.localNetworkPrompt == nil)
    }

    /// Without local-network access the connection is held back and times
    /// out; that is reported as the missing access, not as the timeout.
    @Test func aFailureWithLocalNetworkRefusedIsReportedAsThat() async throws {
        let harness = OnboardingHarness()
        harness.fill()
        harness.onSession = { [dependencies = harness.dependencies] in dependencies.localNetwork.record(.denied) }
        harness.stub.enqueue(.failure(.timedOut))

        await harness.model.signIn()

        #expect(harness.model.signInError == OnboardingModel.localNetworkDeniedMessage)
        #expect(harness.model.localNetworkPrompt == .denied)
        #expect(harness.model.path == [.signIn])
    }

    /// Access granted on an earlier run, then withdrawn in Settings: iOS
    /// does not say so, so a connection that never reached the console
    /// re-checks the remembered grant and reports the refusal.
    @Test func aWithdrawnGrantIsFoundAndReportedAfterAFailedConnection() async throws {
        let harness = OnboardingHarness(localNetwork: .granted, probe: [.init(evidence: [.denied])])
        harness.fill()
        harness.stub.enqueue(.failure(.timedOut))

        await harness.model.signIn()

        #expect(harness.probe.connections == ["192.168.1.1:443"])
        #expect(harness.dependencies.localNetwork.status == .denied)
        #expect(harness.model.signInError == OnboardingModel.localNetworkDeniedMessage)
        #expect(harness.model.localNetworkPrompt == .denied)
    }

    /// A console that answered was reached, so the grant is not in question.
    @Test func anAnsweringConsoleDoesNotReopenTheGrant() async throws {
        let harness = OnboardingHarness(localNetwork: .granted)
        harness.fill()
        harness.stub.enqueue(status: 401)

        await harness.model.signIn()

        #expect(harness.probe.connections.isEmpty)
        #expect(harness.dependencies.localNetwork.status == .granted)
    }

    @Test func signingInNeedsAnAddressAUsernameAndAPassword() {
        let harness = OnboardingHarness()
        #expect(!harness.model.canSignIn)
        harness.fill(host: "http://192.168.1.1")
        #expect(!harness.model.canSignIn)
        harness.fill(username: "  ")
        #expect(!harness.model.canSignIn)
        harness.fill(password: "")
        #expect(!harness.model.canSignIn)
        harness.fill(host: "console.local:8443")
        #expect(harness.model.canSignIn)
    }

    // MARK: Local-network access

    @Test func accessIsExplainedBeforeTheConsoleIsProbed() async throws {
        let harness = OnboardingHarness(localNetwork: nil, probe: [.init(evidence: [.reachable])])
        harness.fill()

        await harness.model.signIn()

        #expect(harness.model.localNetworkPrompt == .explain)
        #expect(harness.probe.connections.isEmpty)
        #expect(harness.stub.requests.isEmpty)

        harness.stub.enqueueLogin()
        harness.stub.enqueue(status: 403, json: "{}")
        try harness.stub.enqueueFixture("protect-api/legacy/bootstrap.json")
        await harness.model.allowLocalNetwork()

        #expect(harness.probe.connections == ["192.168.1.1:443"])
        #expect(harness.model.localNetworkPrompt == nil)
        #expect(harness.model.path == [.signIn, .cameras])
    }

    @Test func aRefusalShowsTheWayToSettingsAndTryingAgainCarriesOn() async throws {
        let harness = OnboardingHarness(
            localNetwork: nil, probe: [.init(evidence: [.denied]), .init(evidence: [.reachable])])
        harness.fill()
        await harness.model.signIn()

        await harness.model.allowLocalNetwork()

        #expect(harness.model.localNetworkPrompt == .denied)
        #expect(harness.dependencies.localNetwork.status == .denied)
        #expect(harness.stub.requests.isEmpty)

        harness.stub.enqueueLogin()
        harness.stub.enqueue(status: 403, json: "{}")
        try harness.stub.enqueueFixture("protect-api/legacy/bootstrap.json")
        await harness.model.allowLocalNetwork()

        #expect(harness.model.localNetworkPrompt == nil)
        #expect(harness.model.path == [.signIn, .cameras])
    }

    /// The refusal shows as soon as it is seen, while the probe is still
    /// waiting (an Allow tapped late would still let it through).
    @Test func aRefusalShowsBeforeTheProbeEnds() async throws {
        let harness = OnboardingHarness(localNetwork: nil, probe: [.init(evidence: [.denied], staysOpen: true)])
        harness.model.startSignIn()
        harness.fill()
        await harness.model.signIn()

        let allowing = Task { await harness.model.allowLocalNetwork() }
        #expect(await eventually { harness.model.localNetworkPrompt == .denied })

        harness.model.cancelLocalNetworkPrompt()
        await allowing.value

        #expect(harness.model.localNetworkPrompt == nil)
        #expect(harness.stub.requests.isEmpty)
        #expect(harness.model.path == [.signIn])
    }

    @Test func aRefusalFromAnEarlierRunIsShownRightAway() async {
        let harness = OnboardingHarness(localNetwork: .denied)
        harness.fill()

        await harness.model.signIn()

        #expect(harness.model.localNetworkPrompt == .denied)
        #expect(harness.probe.connections.isEmpty)
    }

    @Test func anUndecidedProbeStillLetsTheSignInSayWhatIsWrong() async {
        let harness = OnboardingHarness(localNetwork: nil, probe: [.init(evidence: [.inconclusive])])
        harness.fill()
        await harness.model.signIn()
        harness.stub.enqueue(.failure(.timedOut))

        await harness.model.allowLocalNetwork()

        #expect(harness.model.localNetworkPrompt == nil)
        #expect(harness.model.signInError?.hasPrefix("Could not reach the console") == true)
    }

    @Test func aLoopbackConsoleIsNotProbed() async {
        let harness = OnboardingHarness(localNetwork: nil)
        harness.fill(host: "127.0.0.1:8443")
        harness.stub.enqueue(.failure(.cannotConnectToHost))

        await harness.model.signIn()

        #expect(harness.model.localNetworkPrompt == nil)
        #expect(harness.stub.requests.count == 1)
    }

    // MARK: Adding a camera by URL

    @Test func aCameraOnLoopbackGoesStraightToDone() async throws {
        let harness = OnboardingHarness(localNetwork: nil)
        harness.model.startManualEntry()
        harness.model.manualEntry.name = "Nursery"
        harness.model.manualEntry.url = "rtsp://127.0.0.1:18554/nursery"
        let camera = try #require(await harness.model.manualEntry.save())

        harness.model.manualCameraSaved(camera)

        #expect(harness.model.path == [.manualEntry, .done])
        #expect(harness.model.addedCount == 1)
        #expect(harness.model.localNetworkPrompt == nil)
        #expect(harness.dependencies.cameras.cameras == [camera])
    }

    @Test func aCameraOnTheLanExplainsAccessAndProbesTheCamera() async throws {
        let harness = OnboardingHarness(localNetwork: nil, probe: [.init(evidence: [.reachable])])
        harness.model.startManualEntry()

        harness.model.manualCameraSaved(Camera(id: "x", name: "Nursery", url: "rtsp://192.168.1.20:7447/alias"))

        #expect(harness.model.localNetworkPrompt == .explain)
        #expect(harness.model.path == [.manualEntry])
        await harness.model.allowLocalNetwork()
        #expect(harness.probe.connections == ["192.168.1.20:7447"])
        #expect(harness.model.path == [.manualEntry, .done])
    }

    @Test func declingAccessForACameraStillFinishes() {
        let harness = OnboardingHarness(localNetwork: .denied)
        harness.model.startManualEntry()

        harness.model.manualCameraSaved(Camera(id: "x", name: "Nursery", url: "rtsp://camera.local/stream"))
        #expect(harness.model.localNetworkPrompt == .denied)
        harness.model.cancelLocalNetworkPrompt()

        #expect(harness.model.localNetworkPrompt == nil)
        #expect(harness.model.path == [.manualEntry, .done])
    }

    // MARK: Leaving

    @Test func finishingGoesBackToTheStartForTheNextVisit() async throws {
        let harness = OnboardingHarness()
        harness.fill()
        harness.stub.enqueueLogin()
        harness.stub.enqueue(status: 403, json: "{}")
        try harness.stub.enqueueFixture("protect-api/legacy/bootstrap.json")
        await harness.model.signIn()
        harness.model.toggleCamera("cam1")
        await harness.model.importSelected()
        #expect(harness.model.path.last == .done)

        harness.model.finish()

        #expect(harness.model.path.isEmpty)
        #expect(harness.model.cameras.isEmpty)
        #expect(harness.model.selectedCameraIDs.isEmpty)
        #expect(harness.model.addedCount == 0)
        #expect(harness.model.canLeave)
        // The stored sign-in is ready for the next visit.
        #expect(harness.model.host == "192.168.1.1")
    }

    @Test func withoutCamerasThereIsNothingToLeaveTo() {
        #expect(!OnboardingHarness().model.canLeave)
    }
}

#if DEBUG
    import Foundation
    import Observation

    /// Debug builds only: exercises the #64 Protect layer against a real
    /// console before onboarding (#65) exists. Signs in through the pinned
    /// session, shows the certificate to confirm on first contact or after a
    /// change, and lists the cameras with the ids they would be stored under.
    /// Listing over the legacy API changes nothing on the console; the public
    /// API needs an API key, which may be minted ("Dozecam"), so it is opt-in.
    @MainActor
    @Observable
    final class ConsoleDebugModel {
        struct Listing: Equatable {
            let api: String
            let cameras: [Camera]
        }

        var host = ""
        var username = ""
        var password = ""
        private(set) var busy = false
        private(set) var log: [String] = []
        private(set) var pendingTrust: TofuTrustError?
        private(set) var listings: [Listing] = []
        private(set) var pinned: String?

        private let trust: TofuTrustStore
        private let credentials: any CredentialsStore
        private let localNetwork: LocalNetworkAccess

        init(
            trust: TofuTrustStore = .shared,
            credentials: any CredentialsStore = KeychainCredentialsStore(),
            localNetwork: LocalNetworkAccess = LocalNetworkAccess()
        ) {
            self.trust = trust
            self.credentials = credentials
            self.localNetwork = localNetwork
            if let stored = try? credentials.load() {
                host = stored.host
                username = stored.username
                password = stored.password
            }
            refreshPin()
        }

        private var endpoint: TofuEndpoint? {
            ProtectApiClient.baseURL(for: host).flatMap(TofuEndpoint.init(url:))
        }

        func signIn(includingPublicAPI: Bool, confirming fingerprint: String? = nil) async {
            guard let baseURL = ProtectApiClient.baseURL(for: host), let endpoint else {
                note("Not a console address: \(host)")
                return
            }
            busy = true
            defer {
                busy = false
                refreshPin()
            }
            pendingTrust = nil
            let access = await localNetwork.requestAccess(probing: endpoint.host, port: UInt16(endpoint.port))
            note("Local network access: \(access.rawValue)")

            let session = PinnedSessionFactory(store: trust).consoleSession(confirming: fingerprint)
            defer { session.invalidate() }
            do {
                try await session.surfacingTrustFailures {
                    let legacy = ProtectApiClient(baseURL: baseURL, urlSession: session.urlSession)
                    let signedIn = try await legacy.login(username: username, password: password)
                    // The pin is stored only after a sign-in succeeds behind it.
                    if let fingerprint {
                        trust.confirmConsole(endpoint, fingerprint: fingerprint)
                        note("Pinned \(endpoint.key): \(fingerprint)")
                    }
                    note("Signed in to \(endpoint.key)")
                    let consoleHost = ProtectCameraImport.consoleHost(forInput: host)
                    var stored = ProtectCredentials(host: host, username: username, password: password)
                    stored.apiKey = (try? credentials.load())?.apiKey(reusableFor: host, username: username)

                    let bootstrap = try await legacy.bootstrap(signedIn)
                    let legacyCameras = bootstrap.cameras.compactMap { camera -> Camera? in
                        guard let channel = camera.preferredChannel else { return nil }
                        return ProtectCameraImport.camera(
                            camera, channel: channel,
                            streamURL: channel.rtspAlias.map(legacy.rtspURL(forAlias:)) ?? "(RTSP not enabled)",
                            consoleHost: consoleHost, existing: [])
                    }
                    var listings = [Listing(api: "Legacy API", cameras: legacyCameras)]

                    if includingPublicAPI {
                        let key: String
                        if let reused = stored.apiKey {
                            key = reused
                        } else {
                            key = try await legacy.createApiKey(signedIn, name: ProtectCameraImport.apiKeyName)
                            note("Minted an API key named \(ProtectCameraImport.apiKeyName)")
                        }
                        stored.apiKey = key
                        let publicAPI = ProtectPublicApiClient(baseURL: baseURL, urlSession: session.urlSession)
                        let publicCameras = try await publicAPI.cameras(apiKey: key).map {
                            ProtectCameraImport.camera(
                                $0, streamURL: "(not requested)", consoleHost: consoleHost, existing: [])
                        }
                        listings.append(Listing(api: "Public API", cameras: publicCameras))
                    }
                    self.listings = listings
                    try credentials.save(stored)
                    note("Listed \(listings.map { "\($0.cameras.count) via \($0.api)" }.joined(separator: ", "))")
                }
            } catch let failure as TofuTrustError where failure.needsConfirmation {
                pendingTrust = failure
                note("Certificate needs confirming: \(failure)")
            } catch {
                note("Failed: \(error)")
            }
        }

        /// Stands in for a reissued console certificate: the pin no longer
        /// matches, so the next sign-in must ask again, showing both.
        func simulateChangedCertificate() {
            guard let endpoint else { return }
            let fake = (0..<32).map { _ in String(format: "%02X", UInt8.random(in: 0...255)) }.joined(separator: ":")
            trust.confirmConsole(endpoint, fingerprint: fake)
            note("Replaced the pin for \(endpoint.key) with a fake one")
            refreshPin()
        }

        func forgetPin() {
            guard let endpoint else { return }
            trust.forget(endpoint)
            note("Forgot the pin for \(endpoint.key)")
            refreshPin()
        }

        private func refreshPin() {
            pinned = endpoint.flatMap { trust.fingerprint(for: $0) }
        }

        private func note(_ line: String) {
            log.insert("\(Date().formatted(date: .omitted, time: .standard))  \(line)", at: 0)
        }
    }
#endif

import SwiftUI
import UIKit

// MARK: - Sign in

/// The console's address and a local account on it.
struct ConsoleSignInView: View {
    @Bindable var model: OnboardingModel

    private enum Field: Hashable {
        case host
        case username
        case password
    }

    @FocusState private var focus: Field?

    var body: some View {
        Form {
            if let error = model.signInError {
                Section {
                    ErrorLabel(message: error)
                }
            }
            Section {
                TextField("Console address", text: $model.host, prompt: Text(verbatim: "192.168.1.1"))
                    .textContentType(.URL)
                    .keyboardType(.URL)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .submitLabel(.next)
                    .focused($focus, equals: .host)
                    .onSubmit { focus = .username }
            } header: {
                Text("Console")
            } footer: {
                Text("The address you open your console at in a browser, such as 192.168.1.1.")
            }
            Section {
                TextField("Username", text: $model.username, prompt: Text("Username"))
                    .textContentType(.username)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .submitLabel(.next)
                    .focused($focus, equals: .username)
                    .onSubmit { focus = .password }
                SecureField("Password", text: $model.password, prompt: Text("Password"))
                    .textContentType(.password)
                    .submitLabel(.go)
                    .focused($focus, equals: .password)
                    .onSubmit(signIn)
            } header: {
                Text("Account")
            } footer: {
                Text(
                    "Use a local account on the console, not a Ubiquiti cloud account. A dedicated view-only "
                        + "account with permission to manage cameras is recommended."
                )
            }
        }
        .readableContentWidth()
        .disabled(model.activity != nil)
        .navigationTitle("Sign In")
        .navigationBarTitleDisplayMode(.inline)
        .navigationBarBackButtonHidden(model.activity != nil)
        .safeAreaBar(edge: .bottom) {
            ActionBar {
                PrimaryButton(
                    title: "Sign In",
                    busyTitle: "Connecting…",
                    isBusy: model.activity == .connecting,
                    isEnabled: model.canSignIn,
                    action: signIn
                )
            }
        }
        .onAppear {
            if model.host.isEmpty { focus = .host }
        }
    }

    private func signIn() {
        guard model.canSignIn else { return }
        focus = nil
        Task { await model.signIn() }
    }
}

// MARK: - Certificate

/// Trust on first use: the fingerprint of the console's self-signed
/// certificate, to compare before trusting it. A changed certificate shows
/// the trusted and the presented fingerprints, since the answer can also be
/// that something is wrong.
struct CertificateConfirmationView: View {
    let model: OnboardingModel
    @ScaledMetric(relativeTo: .largeTitle) private var iconSize = 48

    var body: some View {
        Form {
            if let certificate = model.certificate {
                Section {
                    VStack(spacing: 12) {
                        Image(systemName: certificate.isChange ? "exclamationmark.shield.fill" : "lock.shield.fill")
                            .font(.system(size: iconSize))
                            .foregroundStyle(certificate.isChange ? AnyShapeStyle(.red) : AnyShapeStyle(.tint))
                            .accessibilityHidden(true)
                        Text(
                            certificate.isChange
                                ? "This Console's Certificate Changed" : "First Connection to This Console"
                        )
                        .font(.title3.weight(.semibold))
                        .accessibilityAddTraits(.isHeader)
                        Text(certificate.isChange ? Self.changedExplanation : Self.firstExplanation)
                            .foregroundStyle(.secondary)
                    }
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: .infinity)
                    .listRowBackground(Color.clear)
                }
                Section("Console") {
                    Text(verbatim: certificate.endpoint.key)
                        .font(.body.monospaced())
                        .textSelection(.enabled)
                }
                if let pinned = certificate.pinned {
                    Section("Trusted until now") {
                        FingerprintView(fingerprint: pinned)
                    }
                    Section("Presented now") {
                        FingerprintView(fingerprint: certificate.presented)
                    }
                } else {
                    Section {
                        FingerprintView(fingerprint: certificate.presented)
                    } header: {
                        Text("SHA-256 fingerprint")
                    } footer: {
                        Text("If it ever changes, Dozecam stops and asks you again before connecting.")
                    }
                }
            }
        }
        .readableContentWidth()
        .navigationTitle("Verify Console")
        .navigationBarTitleDisplayMode(.inline)
        .navigationBarBackButtonHidden(model.activity != nil)
        .safeAreaBar(edge: .bottom) {
            ActionBar {
                PrimaryButton(
                    title: model.certificate?.isChange == true ? "Trust the New Certificate" : "Trust This Console",
                    busyTitle: "Signing In…",
                    isBusy: model.activity == .connecting,
                    isEnabled: model.certificate != nil && model.activity == nil,
                    tint: model.certificate?.isChange == true ? .red : nil
                ) {
                    Task { await model.trustCertificate() }
                }
                SecondaryButton(title: "Cancel", action: model.rejectCertificate)
                    .disabled(model.activity != nil)
            }
        }
    }

    static let firstExplanation =
        "The console presented a self-signed certificate. Compare this fingerprint with the one your console "
        + "shows before trusting it."
    static let changedExplanation =
        "The console is presenting a different certificate from the one you trusted. Consoles reissue theirs "
        + "after a firmware update or a reset, but so would something impersonating your console. Compare the "
        + "new fingerprint with the one your console shows before trusting it."
}

/// A SHA-256 fingerprint in four lines of eight pairs, so it can be compared
/// a line at a time; read to VoiceOver pair by pair.
private struct FingerprintView: View {
    let fingerprint: String

    private var lines: [String] {
        let pairs = fingerprint.split(separator: ":")
        return stride(from: 0, to: pairs.count, by: 8).map { start in
            pairs[start..<min(start + 8, pairs.count)].joined(separator: ":")
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            ForEach(Array(lines.enumerated()), id: \.offset) { _, line in
                Text(verbatim: line)
            }
        }
        .font(.body.monospaced())
        .textSelection(.enabled)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Fingerprint")
        .accessibilityValue(fingerprint.split(separator: ":").joined(separator: " "))
    }
}

// MARK: - Cameras

/// The console's cameras, none selected until the user picks them.
struct CameraPickerView: View {
    let model: OnboardingModel

    var body: some View {
        List {
            if let error = model.importError {
                Section {
                    ErrorLabel(message: error)
                }
            }
            if !model.cameras.isEmpty {
                Section {
                    ForEach(model.cameras) { camera in
                        CameraPickerRow(
                            camera: camera,
                            isSelected: model.selectedCameraIDs.contains(camera.id)
                        ) {
                            model.toggleCamera(camera.id)
                        }
                    }
                } header: {
                    Text("Choose cameras to add")
                } footer: {
                    Text(
                        "Each camera is added at medium quality: plenty for a nursery, and light on decoding "
                            + "and Wi-Fi."
                    )
                }
            }
        }
        .listStyle(.insetGrouped)
        .readableContentWidth()
        .overlay {
            if model.cameras.isEmpty {
                ContentUnavailableView(
                    "No Cameras Found",
                    systemImage: "video.slash",
                    description: Text("No cameras were found on this console.")
                )
            }
        }
        .disabled(model.activity != nil)
        .navigationTitle("Cameras")
        .navigationBarTitleDisplayMode(.inline)
        .navigationBarBackButtonHidden(model.activity != nil)
        .toolbar {
            if !model.cameras.isEmpty {
                ToolbarItem(placement: .topBarTrailing) {
                    Button(model.allCamerasSelected ? "Deselect All" : "Select All") {
                        model.selectAllCameras(!model.allCamerasSelected)
                    }
                    .disabled(model.activity != nil)
                }
            }
        }
        .safeAreaBar(edge: .bottom) {
            ActionBar {
                PrimaryButton(
                    title: model.selectedCameraIDs.isEmpty
                        ? "Add Cameras" : "Add ^[\(model.selectedCameraIDs.count) Camera](inflect: true)",
                    busyTitle: "Adding Cameras…",
                    isBusy: model.activity == .importing,
                    isEnabled: model.canImport
                ) {
                    Task { await model.importSelected() }
                }
            }
        }
    }
}

private struct CameraPickerRow: View {
    let camera: DiscoveredCamera
    let isSelected: Bool
    let onToggle: () -> Void

    var body: some View {
        Button(action: onToggle) {
            HStack(spacing: 16) {
                Image(systemName: "video.fill")
                    .foregroundStyle(.tint)
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 2) {
                    Text(camera.name)
                        .foregroundStyle(Color.primary)
                    if !camera.detail.isEmpty {
                        Text(camera.detail)
                            .font(.subheadline)
                            .foregroundStyle(Color.secondary)
                    }
                }
                Spacer(minLength: 0)
                Image(systemName: isSelected ? "checkmark.circle.fill" : "circle")
                    .font(.title2)
                    .foregroundStyle(isSelected ? AnyShapeStyle(.tint) : AnyShapeStyle(Color(uiColor: .tertiaryLabel)))
                    .accessibilityHidden(true)
            }
            .contentShape(.rect)
        }
        .accessibilityAddTraits(isSelected ? .isSelected : [])
    }
}

// MARK: - Done

struct OnboardingDoneView: View {
    let model: OnboardingModel
    let onFinish: () -> Void
    @ScaledMetric(relativeTo: .largeTitle) private var iconSize = 64

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                Image(systemName: model.addedCount > 0 ? "checkmark.circle.fill" : "exclamationmark.circle.fill")
                    .font(.system(size: iconSize))
                    .foregroundStyle(model.addedCount > 0 ? .green : .orange)
                    .accessibilityHidden(true)
                Text(
                    model.addedCount > 0
                        ? "Added ^[\(model.addedCount) camera](inflect: true)" : "No Cameras Added"
                )
                .font(.title.bold())
                .accessibilityAddTraits(.isHeader)
                Text(
                    model.addedCount > 0
                        ? "Dozecam listens to your cameras whenever the viewer is open, until you exit the app."
                        : "The cameras you chose have no stream the console can share."
                )
                .foregroundStyle(.secondary)
            }
            .multilineTextAlignment(.center)
            .padding(24)
            .frame(maxWidth: 560)
            .frame(maxWidth: .infinity)
        }
        .defaultScrollAnchor(.center, for: .alignment)
        .navigationTitle("Done")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar(.hidden, for: .navigationBar)
        .navigationBarBackButtonHidden()
        .safeAreaBar(edge: .bottom) {
            ActionBar {
                PrimaryButton(title: "Done", action: onFinish)
                SecondaryButton(title: "Add More Cameras", action: model.finish)
            }
        }
    }
}

// MARK: - Local network

/// Explains local-network access before iOS asks for it, waits on the
/// answer, and after a refusal points to Settings.
struct LocalNetworkAccessSheet: View {
    let model: OnboardingModel
    @Environment(\.openURL) private var openURL
    @Environment(\.scenePhase) private var scenePhase
    @ScaledMetric(relativeTo: .largeTitle) private var iconSize = 56
    @State private var wasAway = false

    private var prompt: OnboardingModel.LocalNetworkPrompt { model.localNetworkPrompt ?? .explain }

    private var forCamera: Bool {
        if case .camera = model.localNetworkTarget { true } else { false }
    }

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                Image(systemName: prompt == .denied ? "network.slash" : "network")
                    .font(.system(size: iconSize))
                    .foregroundStyle(prompt == .denied ? AnyShapeStyle(.red) : AnyShapeStyle(.tint))
                    .accessibilityHidden(true)
                Text(prompt == .denied ? "Local Network Access Is Off" : "Allow Local Network Access")
                    .font(.title2.bold())
                    .accessibilityAddTraits(.isHeader)
                Text(message)
                    .foregroundStyle(.secondary)
            }
            .multilineTextAlignment(.center)
            .padding(.horizontal, 24)
            .padding(.top, 32)
            .frame(maxWidth: 560)
            .frame(maxWidth: .infinity)
        }
        .defaultScrollAnchor(.center, for: .alignment)
        .safeAreaBar(edge: .bottom) {
            ActionBar {
                switch prompt {
                case .explain:
                    PrimaryButton(title: "Continue", action: allow)
                    SecondaryButton(title: "Not Now", action: cancel)
                case .asking:
                    PrimaryButton(title: "Continue", busyTitle: "Waiting for Your Answer…", isBusy: true) {}
                    SecondaryButton(title: "Cancel", action: cancel)
                case .denied:
                    PrimaryButton(title: "Open Settings") {
                        if let url = URL(string: UIApplication.openSettingsURLString) { openURL(url) }
                    }
                    SecondaryButton(title: "Try Again", action: allow)
                    SecondaryButton(title: forCamera ? "Continue Without It" : "Not Now", action: cancel)
                }
            }
        }
        .presentationDetents([.large])
        .interactiveDismissDisabled(prompt == .asking)
        .onChange(of: scenePhase) { _, phase in
            // Back from Settings: check again rather than make the user say so.
            switch phase {
            case .background: wasAway = true
            case .active where wasAway:
                wasAway = false
                if prompt == .denied { allow() }
            default: break
            }
        }
    }

    private var message: String {
        let reach = forCamera ? "your camera" : "your console and its cameras"
        switch prompt {
        case .explain, .asking:
            return "Dozecam talks to \(reach) over your home network, and to nothing else. "
                + "iOS asks for permission next: choose Allow, or Dozecam cannot reach them."
        case .denied:
            return "Without it Dozecam cannot reach \(reach), so nothing can be watched or listened to. "
                + "In Settings, turn on Local Network for Dozecam, then come back here."
                + (forCamera ? " Your camera is saved." : "")
        }
    }

    private func allow() {
        Task { await model.allowLocalNetwork() }
    }

    private func cancel() {
        model.cancelLocalNetworkPrompt()
    }
}

// MARK: - Shared pieces

/// The buttons at the bottom of a step, kept to a readable width.
private struct ActionBar<Content: View>: View {
    @ViewBuilder let content: Content

    var body: some View {
        VStack(spacing: 8) {
            content
        }
        .frame(maxWidth: 560)
        .padding(.horizontal, 20)
        .padding(.vertical, 12)
        .frame(maxWidth: .infinity)
    }
}

private struct PrimaryButton: View {
    let title: LocalizedStringKey
    var busyTitle: LocalizedStringKey?
    var isBusy = false
    var isEnabled = true
    var tint: Color?
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 8) {
                if isBusy {
                    ProgressView()
                }
                Text(isBusy ? (busyTitle ?? title) : title)
            }
            .frame(maxWidth: .infinity)
        }
        .buttonStyle(.borderedProminent)
        .controlSize(.large)
        .tint(tint)
        .disabled(isBusy || !isEnabled)
    }
}

private struct SecondaryButton: View {
    let title: LocalizedStringKey
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(title)
                .frame(maxWidth: .infinity)
        }
        .buttonStyle(.bordered)
        .controlSize(.large)
    }
}

private struct ErrorLabel: View {
    let message: String

    var body: some View {
        Label {
            Text(message)
        } icon: {
            Image(systemName: "exclamationmark.triangle.fill")
                .foregroundStyle(.red)
        }
        .accessibilityLabel("Error: \(message)")
    }
}

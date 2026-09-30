import SwiftUI

/// Onboarding: a navigation stack from the start screen through signing in,
/// confirming the console's certificate, picking cameras and the done
/// screen, or through the manual URL form. `onFinish` leaves onboarding.
struct OnboardingView: View {
    @Bindable var model: OnboardingModel
    let onFinish: () -> Void

    var body: some View {
        NavigationStack(path: $model.path) {
            OnboardingStartView(model: model, onLeave: leave)
                .navigationDestination(for: OnboardingModel.Route.self) { route in
                    switch route {
                    case .manualEntry:
                        ManualCameraEntryView(model: model.manualEntry, onSaved: model.manualCameraSaved)
                    case .signIn:
                        ConsoleSignInView(model: model)
                    case .certificate:
                        CertificateConfirmationView(model: model)
                    case .cameras:
                        CameraPickerView(model: model)
                    case .done:
                        OnboardingDoneView(model: model, onFinish: leave)
                    }
                }
        }
        .sheet(isPresented: localNetworkSheetShown) {
            LocalNetworkAccessSheet(model: model)
        }
        #if DEBUG
            .task { await model.applyLaunchArguments() }
        #endif
    }

    /// Only a dismissal by the user writes `false`; the model closing the
    /// sheet itself does not come back through here.
    private var localNetworkSheetShown: Binding<Bool> {
        Binding(
            get: { model.localNetworkPrompt != nil },
            set: { shown in if !shown { model.cancelLocalNetworkPrompt() } }
        )
    }

    private func leave() {
        model.finish()
        onFinish()
    }
}

/// The two ways in: sign in to a console, or add a camera by URL.
private struct OnboardingStartView: View {
    let model: OnboardingModel
    let onLeave: () -> Void
    @ScaledMetric(relativeTo: .largeTitle) private var iconSize = 56

    var body: some View {
        Form {
            Section {
                VStack(spacing: 12) {
                    Image(systemName: "moon.stars.fill")
                        .font(.system(size: iconSize))
                        .foregroundStyle(.tint)
                        .accessibilityHidden(true)
                    Text("A baby monitor for UniFi Protect cameras.")
                        .font(.title3.weight(.semibold))
                    Text("Everything stays on your local network: no cloud, and no account.")
                        .foregroundStyle(.secondary)
                }
                .multilineTextAlignment(.center)
                .frame(maxWidth: .infinity)
                .listRowBackground(Color.clear)
            }
            Section {
                ChoiceRow(
                    title: "Sign In to a Console",
                    detail: "Add the cameras of a Protect console on this network.",
                    systemImage: "server.rack",
                    action: model.startSignIn
                )
                ChoiceRow(
                    title: "Add a Camera by URL",
                    detail: "Enter a camera's RTSP stream address.",
                    systemImage: "link",
                    action: model.startManualEntry
                )
            } header: {
                Text("Add Cameras")
            }
        }
        .readableContentWidth()
        .navigationTitle(model.canLeave ? "Add Cameras" : "Welcome to Dozecam")
        .toolbar {
            if model.canLeave {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel", action: onLeave)
                }
            }
        }
    }
}

/// A navigation row that runs `action` rather than pushing a value, so the
/// model can set up the screen it leads to.
private struct ChoiceRow: View {
    let title: String
    let detail: String
    let systemImage: String
    let action: () -> Void
    @ScaledMetric(relativeTo: .title2) private var iconWidth = 32

    var body: some View {
        Button(action: action) {
            HStack(spacing: 16) {
                Image(systemName: systemImage)
                    .font(.title2)
                    .foregroundStyle(.tint)
                    .frame(width: iconWidth)
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                        .font(.headline)
                        .foregroundStyle(Color.primary)
                    Text(detail)
                        .font(.subheadline)
                        .foregroundStyle(Color.secondary)
                }
                Spacer(minLength: 0)
                Image(systemName: "chevron.forward")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(Color(uiColor: .tertiaryLabel))
                    .accessibilityHidden(true)
            }
            .padding(.vertical, 6)
            .contentShape(.rect)
        }
    }
}

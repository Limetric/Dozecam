#if DEBUG
    import SwiftUI

    struct ConsoleDebugView: View {
        @Bindable var model: ConsoleDebugModel

        var body: some View {
            Form {
                Section("Console") {
                    TextField("Address", text: $model.host)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .keyboardType(.URL)
                    TextField("Username", text: $model.username)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    SecureField("Password", text: $model.password)
                    LabeledContent("Pinned", value: model.pinned ?? "none")
                        .font(.caption.monospaced())
                }
                Section {
                    Button("Sign in and list cameras (legacy API)") {
                        Task { await model.signIn(includingPublicAPI: false) }
                    }
                    Button("Also list via the public API (may mint an API key)") {
                        Task { await model.signIn(includingPublicAPI: true) }
                    }
                    Button("Simulate a changed certificate") { model.simulateChangedCertificate() }
                    Button("Forget the pin", role: .destructive) { model.forgetPin() }
                }
                .disabled(model.busy)
                if let pending = model.pendingTrust {
                    Section("Confirm the console's certificate") {
                        if let pinned = pending.pinnedFingerprint {
                            LabeledContent("Pinned", value: pinned).font(.caption.monospaced())
                        }
                        LabeledContent("Presented", value: pending.presentedFingerprint ?? "?")
                            .font(.caption.monospaced())
                        Button("Trust this certificate and sign in") {
                            Task {
                                await model.signIn(
                                    includingPublicAPI: false, confirming: pending.presentedFingerprint)
                            }
                        }
                    }
                }
                ForEach(model.listings, id: \.api) { listing in
                    Section(listing.api) {
                        ForEach(listing.cameras) { camera in
                            VStack(alignment: .leading) {
                                Text(camera.name)
                                Text(camera.id).font(.caption.monospaced())
                                Text(camera.url).font(.caption2.monospaced()).foregroundStyle(.secondary)
                            }
                        }
                    }
                }
                Section("Log") {
                    ForEach(Array(model.log.enumerated()), id: \.offset) { _, line in
                        Text(line).font(.caption.monospaced())
                    }
                }
            }
            .navigationTitle("Console debug")
        }
    }
#endif

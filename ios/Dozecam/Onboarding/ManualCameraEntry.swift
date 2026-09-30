import Foundation
import Observation
import SwiftUI

/// A camera added, or edited, by stream URL: a name and an `rtsp://` or
/// `rtsps://` URL with a host (shared/spec/protect.md, "Stream URLs"). The
/// counterpart of the camera form in Android's `SettingsViewModel`, with the
/// same rules:
///
/// - Saving needs a name that is not blank and a URL `StreamUrlValidator`
///   accepts.
/// - The URL is normalized before it is stored, so a Protect `rtsps://` link
///   is saved as plain `rtsp://` (7441 becoming 7447); the name is trimmed.
/// - A new camera gets a random id and no console, and arrives enabled. An
///   edit changes only the name and URL: the id, the Protect identity and
///   the enabled setting stay.
///
/// Knows nothing of onboarding, so settings can present the same form.
@MainActor
@Observable
final class ManualCameraEntryModel {
    var name: String
    var url: String
    /// The camera being edited, or nil when adding one.
    let editing: Camera?
    private(set) var isSaving = false
    private(set) var saveError: String?

    @ObservationIgnored private let cameras: any CameraStore

    init(cameras: any CameraStore, editing: Camera? = nil) {
        self.cameras = cameras
        self.editing = editing
        name = editing?.name ?? ""
        url = editing?.url ?? ""
    }

    var isEditing: Bool { editing != nil }

    var trimmedName: String { name.trimmingCharacters(in: .whitespacesAndNewlines) }

    var isURLValid: Bool { StreamUrlValidator.isValid(url) }

    /// What is wrong with the URL, once something has been typed.
    var urlProblem: String? {
        guard !url.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, !isURLValid else { return nil }
        return Self.invalidURLMessage
    }

    /// The URL as it will be stored.
    var normalizedURL: String { StreamUrlValidator.normalize(url) }

    /// Whether saving rewrites the URL beyond trimming it: an `rtsps://` link
    /// is stored as its plain `rtsp://` counterpart.
    var rewritesURL: Bool {
        isURLValid && normalizedURL != url.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    var canSave: Bool { !isSaving && !trimmedName.isEmpty && isURLValid }

    /// Stores the camera and returns it, or nil when the form cannot be saved
    /// or the store refused it (`saveError` then says so).
    @discardableResult
    func save() async -> Camera? {
        guard canSave else { return nil }
        isSaving = true
        saveError = nil
        defer { isSaving = false }
        let name = trimmedName
        let url = normalizedURL
        let camera: Camera
        // Read back from the store rather than trusting `editing`, which may
        // be stale: an edit must not undo a switch flipped since.
        if let editing {
            var current = cameras.cameras.first { $0.id == editing.id } ?? editing
            current.name = name
            current.url = url
            camera = current
        } else {
            camera = Camera(id: Self.newID(), name: name, url: url)
        }
        do {
            try await cameras.upsert(camera)
            return camera
        } catch {
            saveError = "Could not save the camera: \(error.localizedDescription)"
            return nil
        }
    }

    /// A random id, lowercase like Android's `UUID.randomUUID().toString()`.
    static func newID() -> String { UUID().uuidString.lowercased() }

    static let invalidURLMessage =
        "Enter an rtsp:// or rtsps:// address with a host, such as rtsp://192.168.1.20:554/stream."
}

/// The form for `ManualCameraEntryModel`: name, stream URL, and a confirming
/// toolbar button. Push it onto a navigation stack, or present it in one;
/// `onSaved` is told once the camera is stored.
struct ManualCameraEntryView: View {
    @Bindable var model: ManualCameraEntryModel
    let onSaved: (Camera) -> Void

    private enum Field: Hashable {
        case name
        case url
    }

    @FocusState private var focus: Field?

    var body: some View {
        Form {
            Section {
                TextField("Name", text: $model.name, prompt: Text("Nursery"))
                    .textContentType(.name)
                    .textInputAutocapitalization(.words)
                    .submitLabel(.next)
                    .focused($focus, equals: .name)
                    .onSubmit { focus = .url }
            } header: {
                Text("Name")
            }
            Section {
                TextField("Stream URL", text: $model.url, prompt: Text(verbatim: "rtsp://192.168.1.20:554/stream"))
                    .textContentType(.URL)
                    .keyboardType(.URL)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .submitLabel(.done)
                    .focused($focus, equals: .url)
                    .onSubmit(save)
                    .accessibilityHint("An rtsp or rtsps address")
            } header: {
                Text("Stream URL")
            } footer: {
                footer
            }
            if let error = model.saveError {
                Section {
                    Label(error, systemImage: "exclamationmark.triangle.fill")
                        .foregroundStyle(.red)
                }
            }
        }
        .readableContentWidth()
        .disabled(model.isSaving)
        .navigationTitle(model.isEditing ? "Edit Camera" : "Add Camera")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                Button(model.isEditing ? "Save" : "Add", action: save)
                    .disabled(!model.canSave)
            }
        }
        .onAppear {
            if !model.isEditing, model.name.isEmpty { focus = .name }
        }
    }

    @ViewBuilder private var footer: some View {
        if let problem = model.urlProblem {
            Label(problem, systemImage: "exclamationmark.circle")
                .foregroundStyle(.red)
        } else if model.rewritesURL {
            Text(
                "Saved as \(Text(verbatim: model.normalizedURL).monospaced()), the plain RTSP stream behind this link."
            )
        } else {
            Text("An rtsp:// or rtsps:// address. A Protect rtsps:// link is saved as its plain rtsp:// stream.")
        }
    }

    private func save() {
        guard model.canSave else { return }
        Task {
            if let camera = await model.save() { onSaved(camera) }
        }
    }
}

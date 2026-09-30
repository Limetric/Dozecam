import SwiftUI

/// The page for one section, the same on an iPhone (pushed) and an iPad
/// (the split view's detail).
struct SettingsPage: View {
    let section: SettingsSection
    let model: SettingsModel
    let onAddCameras: () -> Void
    #if DEBUG
        let consoleDebug: ConsoleDebugModel
    #endif

    var body: some View {
        Group {
            switch section {
            case .cameras: CamerasPage(model: model, onAddCameras: onAddCameras)
            case .detection: DetectionPage(model: model)
            case .alerts: AlertsPage(model: model)
            case .display: DisplayPage(model: model)
            case .checklist: ChecklistPage()
            case .about: AboutPage(model: model)
            #if DEBUG
                case .debug: ConsoleDebugView(model: consoleDebug)
            #endif
            }
        }
        .navigationTitle(section.title)
        .navigationBarTitleDisplayMode(.inline)
    }
}

// MARK: - Cameras

/// The camera list and the two ways to add more. A camera's switch is the
/// whole story: a camera that is on is the one the viewer shows *and* the one
/// the monitor listens to (Android's `CamerasSettings`).
struct CamerasPage: View {
    let model: SettingsModel
    let onAddCameras: () -> Void

    var body: some View {
        ScrollViewReader { proxy in
            Form {
                if model.cameras.isEmpty {
                    Section {
                        Text(
                            "No cameras yet. Add them from your Protect console, or by the stream URL of any RTSP camera."
                        )
                        .foregroundStyle(.secondary)
                    }
                } else {
                    Section {
                        ForEach(model.cameras) { camera in
                            CameraRow(camera: camera, model: model)
                        }
                    } footer: {
                        Text(
                            "A camera that is on is shown and listened to. Swipe left on a camera, or touch and hold it, to remove it."
                        )
                    }
                }
                Section {
                    Button("Add cameras", systemImage: "plus.circle", action: onAddCameras)
                        .id(SettingsSearch.addCamerasID)
                    Button("Add by stream URL", systemImage: "link") { model.isAddingByURL = true }
                        .id(SettingsSearch.addByURLID)
                } footer: {
                    Text("Add cameras signs in to your Protect console and imports its cameras.")
                }
            }
            .scrollsToSearchFocus(on: .cameras, model: model, proxy: proxy)
        }
        .confirmationDialog(
            removalTitle,
            isPresented: Binding(
                get: { model.pendingRemoval != nil },
                set: { if !$0 { model.cancelRemoval() } }),
            titleVisibility: .visible,
            presenting: model.pendingRemoval
        ) { camera in
            Button("Remove \(camera.name)", role: .destructive) { model.confirmRemoval() }
            Button("Cancel", role: .cancel) { model.cancelRemoval() }
        } message: { _ in
            Text("It will no longer be shown or listened to. You can add it again later.")
        }
    }

    private var removalTitle: String {
        model.pendingRemoval.map { "Remove \($0.name)?" } ?? "Remove camera?"
    }
}

private struct CameraRow: View {
    let camera: Camera
    let model: SettingsModel

    /// A stale `rtsps://` entry can be watched but never listened to; this is
    /// the only place the user can act on it (Android's `CameraRow`).
    private var notMonitorable: Bool {
        camera.enabled && !StreamUrlValidator.isMonitorable(camera.url)
    }

    var body: some View {
        Toggle(isOn: Binding(get: { camera.enabled }, set: { model.setEnabled(camera, $0) })) {
            VStack(alignment: .leading, spacing: 2) {
                Text(camera.name)
                if notMonitorable {
                    // The icon carries the colour; orange text on white would
                    // be too faint to read.
                    HStack(alignment: .firstTextBaseline, spacing: 4) {
                        Image(systemName: "exclamationmark.triangle.fill")
                            .foregroundStyle(.orange)
                            .accessibilityHidden(true)
                        Text("Can be watched but not monitored. Re-add this camera to fix it.")
                            .foregroundStyle(.secondary)
                    }
                    .font(.footnote)
                } else {
                    Text(camera.url)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                        .truncationMode(.middle)
                        .accessibilityLabel(camera.protect == nil ? "Stream URL" : "From Protect")
                }
            }
        }
        .swipeActions(edge: .trailing) {
            Button("Remove", systemImage: "trash", role: .destructive) { model.requestRemoval(of: camera) }
        }
        .contextMenu {
            Button("Remove…", systemImage: "trash", role: .destructive) { model.requestRemoval(of: camera) }
        }
    }
}

// MARK: - Detection

/// How loud, for how long, and when to re-arm (Android's `DetectionSettings`),
/// with the live meter the threshold is set against.
struct DetectionPage: View {
    let model: SettingsModel

    var body: some View {
        ScrollViewReader { proxy in
            Form {
                Section("Live audio level") {
                    LevelMeter(level: model.level, threshold: model.detector.threshold)
                        .padding(.vertical, 6)
                }
                Section {
                    SettingSliderRow(setting: .threshold, model: model)
                    SettingSliderRow(setting: .sustain, model: model)
                    SettingSliderRow(setting: .quiet, model: model)
                } footer: {
                    Text("Applies to every camera that is switched on.")
                }
            }
            .scrollsToSearchFocus(on: .detection, model: model, proxy: proxy)
        }
    }
}

// MARK: - Alerts

/// How an alert reaches someone asleep (Android's `AlertsSettings`). The
/// master switch comes first: everything under it is about how an alert is
/// delivered, and it is the same stored setting as the viewer's alerts button.
struct AlertsPage: View {
    let model: SettingsModel

    var body: some View {
        ScrollViewReader { proxy in
            Form {
                Section {
                    SettingToggleRow(setting: .alertsEnabled, model: model)
                } footer: {
                    if !model.settings.alertsEnabled {
                        Text("Nothing will wake anyone: rooms are still listened to, but no alert is raised.")
                    }
                }
                Section("Sound") {
                    SettingToggleRow(setting: .alertChime, model: model)
                    SettingToggleRow(setting: .alertVibrate, model: model)
                    // Choosing a tone arrives with the alert path (#68).
                    LabeledContent {
                        Text(model.settings.alertSoundTitle)
                    } label: {
                        Label("Alert sound", systemImage: "alarm")
                    }
                    .id(SettingsSearch.alertSoundID)
                    SettingToggleRow(setting: .alertRamp, model: model)
                }
                Section {
                    SettingSliderRow(setting: .alertVolume, model: model)
                    SettingSliderRow(setting: .alertRepeat, model: model)
                } footer: {
                    Text(
                        "Alerts ring through silent mode and Focus. They can be quieter than your alarm volume, never louder."
                    )
                }
                Section("When the monitor fails") {
                    SettingSliderRow(setting: .failureGrace, model: model)
                }
            }
            .scrollsToSearchFocus(on: .alerts, model: model, proxy: proxy)
        }
    }
}

// MARK: - Display

/// Theme, screen, orientation and talk-back (Android's `DisplaySettings`).
struct DisplayPage: View {
    @Bindable var model: SettingsModel

    var body: some View {
        ScrollViewReader { proxy in
            Form {
                Section {
                    SettingToggleRow(setting: .nightTheme, model: model)
                    SettingToggleRow(setting: .keepScreenOn, model: model)
                }
                Section {
                    Picker("Monitor orientation", selection: $model.orientationLock) {
                        ForEach(OrientationLock.allCases, id: \.self) { lock in
                            Text(lock.shortTitle).tag(lock)
                        }
                    }
                    .pickerStyle(.segmented)
                    .id(SettingsSearch.orientationID)
                } header: {
                    Text("Monitor orientation")
                } footer: {
                    Text(model.orientationLock.description)
                }
                Section {
                    SettingSliderRow(setting: .talkbackVolume, model: model)
                }
            }
            .scrollsToSearchFocus(on: .display, model: model, proxy: proxy)
        }
    }
}

// MARK: - Night checklist

/// The bedtime check: what could stop Dozecam waking you tonight, and a test
/// alert. A placeholder until #69.
struct ChecklistPage: View {
    var body: some View {
        ContentUnavailableView {
            Label("Night checklist", systemImage: "moon.stars")
        } description: {
            Text(
                "Before bed, this page will check that Dozecam can wake you: alerts allowed, cameras reachable, and a test alert to prove it."
            )
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color(.systemGroupedBackground))
    }
}

// MARK: - About

struct AboutPage: View {
    let model: SettingsModel

    var body: some View {
        Form {
            Section {
                LabeledContent("Version", value: model.buildInfo.summary)
                    .id(SettingsSearch.versionID)
            } footer: {
                Text(
                    "Dozecam talks only to your own Protect console and cameras, on your network. No cloud, no accounts."
                )
            }
        }
    }
}

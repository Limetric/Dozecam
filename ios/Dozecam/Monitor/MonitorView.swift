import SwiftUI

/// The viewer: every enabled camera, live, or where it has been paused for the
/// night a placeholder offering it back; one camera at a time on request; and
/// a row of controls. Opening it arms the monitor (`MonitoringService`), which
/// also plays its sound; how a camera is set up lives in settings.
struct MonitorView: View {
    let model: MonitorModel
    let onOpenSettings: () -> Void
    let onAddCameras: () -> Void
    /// The night checklist arrives with #69; until then its button opens
    /// settings, where the grants it will check are.
    var onOpenChecklist: (() -> Void)?

    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        let palette = model.palette
        content
            // Any touch is a person answering a sounding alarm; simultaneous,
            // so scrolling and every button work as ever.
            .simultaneousGesture(DragGesture(minimumDistance: 0).onChanged { _ in model.touched() })
            .environment(\.viewerPalette, palette)
            .task { await model.observe() }
            .onChange(of: scenePhase, initial: true) { _, phase in
                model.sceneChanged(inForeground: phase != .background)
            }
            .onChange(of: model.announcement) { _, announcement in
                if let announcement {
                    AccessibilityNotification.Announcement(announcement.text).post()
                }
            }
            .alert("Exit Dozecam?", isPresented: exitBinding) {
                Button("Exit", role: .destructive) { model.confirmExit() }
                Button("Keep running", role: .cancel) {}
            } message: {
                Text(
                    "Monitoring stops with the app — nothing will wake you if a room gets loud until you open Dozecam again."
                )
            }
            .animation(.easeInOut(duration: 0.2), value: model.announcement)
    }

    @ViewBuilder
    private var content: some View {
        if model.cameras.isEmpty {
            EmptyMonitor(model: model, controls: controls, onOpenSettings: onOpenSettings, onAddCameras: onAddCameras)
        } else {
            ZStack {
                grid
                    // Kept in place under a single camera, so the way back
                    // lands where the grid was left, scrolled as it was.
                    .opacity(model.fullscreenCamera == nil ? 1 : 0)
                    .allowsHitTesting(model.fullscreenCamera == nil)
                    .accessibilityHidden(model.fullscreenCamera != nil)
                if let camera = model.fullscreenCamera {
                    FullscreenCamera(model: model, camera: camera)
                }
            }
            .background(model.palette.background.ignoresSafeArea())
            // The picture is the screen, as on Android's immersive viewer.
            .statusBarHidden()
            .persistentSystemOverlays(model.fullscreenCamera == nil ? .automatic : .hidden)
            // A dark viewer in either appearance: its chrome is drawn for video.
            .environment(\.colorScheme, .dark)
        }
    }

    private var grid: some View {
        VStack(spacing: 0) {
            controls
                .padding(OverlayChrome.margin)
            // iOS has no ongoing notification: the status line lives here,
            // on a row of its own so it is never cut short by the buttons.
            if let status = model.statusLine, !model.showsNotMonitoring {
                StatusLine(text: status)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, OverlayChrome.margin)
                    .padding(.bottom, OverlayChrome.gap)
            }
            CameraGrid(model: model)
        }
        .overlay(alignment: .bottom) {
            VStack(spacing: OverlayChrome.gap) {
                FailureNotices(model: model)
                NetworkNotice(reach: model.reach)
                AnnouncementView(model: model)
            }
            .padding(OverlayChrome.margin)
        }
    }

    private var controls: some View {
        ControlRow(
            model: model, onOpenSettings: onOpenSettings, onOpenChecklist: onOpenChecklist ?? onOpenSettings)
    }

    private var exitBinding: Binding<Bool> {
        Binding(get: { model.isConfirmingExit }, set: { model.isConfirmingExit = $0 })
    }
}

/// The strip of controls above the grid: its own row rather than floated over
/// the first camera, so no button ever covers a tile's status pill.
struct ControlRow: View {
    let model: MonitorModel
    let onOpenSettings: () -> Void
    let onOpenChecklist: () -> Void

    var body: some View {
        HStack(spacing: OverlayChrome.gap) {
            if model.showsNotMonitoring {
                NotMonitoringBadge {
                    if model.retryMonitoring() { onOpenChecklist() }
                }
            }
            Spacer(minLength: 0)
            // The way out first: the one control that ends the night rather
            // than adjusting it.
            ControlButton(systemImage: "power", label: "Exit Dozecam") { model.requestExit() }
            // Nothing to listen to yet: no switch for sound, alerts or a
            // display the viewer never holds awake.
            if !model.cameras.isEmpty {
                SoundModeButton(model: model)
                ControlButton(
                    systemImage: model.settings.alertsEnabled ? "bell.fill" : "bell.slash.fill",
                    label: model.settings.alertsEnabled ? "Turn alerts off" : "Turn alerts on",
                    // Off is the one state in which the night is not watched,
                    // and it looks it for as long as it lasts.
                    attention: !model.settings.alertsEnabled
                ) { model.toggleAlerts() }
                .accessibilityValue(model.settings.alertsEnabled ? "Alerts on" : "Alerts off")
                ControlButton(
                    systemImage: model.settings.keepScreenOn ? "sun.max.fill" : "moon.zzz.fill",
                    label: model.settings.keepScreenOn ? "Let the screen sleep" : "Keep the screen awake"
                ) { model.toggleKeepScreenOn() }
                .accessibilityValue(model.settings.keepScreenOn ? "Screen stays awake" : "Screen sleeps as usual")
            }
            ControlButton(systemImage: "checklist", label: "Night checklist", action: onOpenChecklist)
            ControlButton(systemImage: "gearshape.fill", label: "Settings", action: onOpenSettings)
        }
    }
}

/// Every failure past grace, with its start, whatever the alerts switch
/// says: on the grid and on a camera full screen alike
/// (shared/spec/failure-alerts.md).
struct FailureNotices: View {
    let model: MonitorModel

    var body: some View {
        ForEach(model.failureNotices, id: \.id) { notice in
            OverlayNotice(text: notice.text, attention: true)
        }
    }
}

/// What the monitor is doing, and when it last said so: a line that stops
/// moving is a monitor that has stopped.
struct StatusLine: View {
    let text: String
    @Environment(\.viewerPalette) private var palette

    var body: some View {
        Text(text)
            .font(.caption)
            .lineLimit(2)
            .foregroundStyle(palette.onOverlayVariant)
            .accessibilityLabel("Monitoring: \(text)")
    }
}

/// A start that never landed, styled as the error it is. Tapping retries.
struct NotMonitoringBadge: View {
    let action: () -> Void
    @Environment(\.viewerPalette) private var palette

    var body: some View {
        Button(action: action) {
            Label("Not monitoring", systemImage: "exclamationmark.triangle.fill")
                .font(.subheadline.weight(.semibold))
                .lineLimit(1)
                .padding(.horizontal, OverlayChrome.pillPadding)
                .frame(height: OverlayChrome.controlHeight)
                .foregroundStyle(palette.onAttention)
                .background(palette.attention, in: Capsule())
                .overlay(Capsule().strokeBorder(palette.overlayOutline, lineWidth: 1))
                .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .accessibilityHint("Tries to start monitoring again")
    }
}

/// Says out loud that this device is not where its cameras are: tiles at
/// OFFLINE look the same whether the console died or the phone is on mobile
/// data, and this is the one explanation the viewer can give.
struct NetworkNotice: View {
    let reach: NetworkReach

    var body: some View {
        switch reach {
        case .local:
            EmptyView()
        case .offline:
            OverlayNotice(
                text:
                    "No network. Your cameras are on the home network, so nothing here can reach them until this device is back on it.",
                attention: true)
        case .mobileData:
            OverlayNotice(
                text: "On mobile data. Your cameras are on the home network, so nothing here can reach them.",
                attention: true)
        }
    }
}

private struct EmptyMonitor<Controls: View>: View {
    let model: MonitorModel
    let controls: Controls
    let onOpenSettings: () -> Void
    let onAddCameras: () -> Void

    var body: some View {
        let palette = model.palette
        VStack(spacing: 0) {
            controls.padding(OverlayChrome.margin)
            ContentUnavailableView {
                Label(
                    model.hasDisabledOnly ? "Every camera is switched off" : "No cameras yet",
                    systemImage: "video.slash")
            } description: {
                Text(
                    model.hasDisabledOnly
                        ? "Turn one on in settings to watch and monitor it."
                        : "Connect your UniFi Protect console to add them.")
            } actions: {
                if model.hasDisabledOnly {
                    Button("Settings", action: onOpenSettings)
                        .buttonStyle(.borderedProminent)
                } else {
                    Button("Add cameras", action: onAddCameras)
                        .buttonStyle(.borderedProminent)
                }
            }
            .foregroundStyle(palette.onPage ?? .primary)
            .tint(palette.onPage)
        }
        .background((palette.page ?? Color(.systemBackground)).ignoresSafeArea())
        .environment(\.viewerPalette, palette.forPage)
    }
}

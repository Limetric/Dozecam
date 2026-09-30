import SwiftUI

/// One camera with the screen to itself: the picture edge to edge, pinch to
/// look closer, and in a line along the top the way back, the camera's state,
/// and the controls a single camera keeps (pause, sound). A detour the viewer
/// takes itself back from after a minute nobody touches it.
struct FullscreenCamera: View {
    let model: MonitorModel
    let camera: Camera
    @Environment(\.viewerPalette) private var palette
    @State private var lastMagnification: CGFloat = 1
    @State private var lastTranslation: CGSize = .zero

    private var session: CameraSession? { model.session(for: camera.id) }

    var body: some View {
        ZStack {
            palette.background.ignoresSafeArea()
            picture
                .ignoresSafeArea()
            chrome
        }
        // A hand anywhere on this screen is someone looking, including on the
        // chrome; watched alongside every other gesture, never instead of one.
        .simultaneousGesture(
            DragGesture(minimumDistance: 0).onChanged { _ in model.userInteracted() }
        )
        .accessibilityAction(.escape) { model.closeFullscreen() }
        .onChange(of: session?.videoAspect, initial: true) { _, aspect in
            model.zoom.pictureChanged(aspect)
        }
    }

    private var picture: some View {
        ZStack {
            if let session {
                PlayerSurface(player: session.player)
                    .scaleEffect(model.zoom.scale)
                    .offset(model.zoom.offset)
            }
            if case .unsupported(let codec) = session?.tileState {
                OverlayNotice(text: "This device cannot play \(codec) video.", attention: true)
                    .padding(OverlayChrome.margin)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .contentShape(Rectangle())
        .clipped()
        .onGeometryChange(for: CGSize.self, of: \.size) { model.zoom.viewportChanged($0) }
        .gesture(zoomGesture.simultaneously(with: panGesture))
        .accessibilityElement()
        .accessibilityLabel(camera.name)
        .accessibilityValue(session.map { StatusText.spokenLabel($0.tileState) } ?? "Connecting")
    }

    private var zoomGesture: some Gesture {
        MagnifyGesture()
            .onChanged { value in
                let factor = value.magnification / lastMagnification
                lastMagnification = value.magnification
                model.zoom.transform(centroid: value.startLocation, pan: .zero, zoom: factor)
            }
            .onEnded { _ in lastMagnification = 1 }
    }

    /// Only a zoomed picture pans: at full view there is nothing to move to.
    private var panGesture: some Gesture {
        DragGesture(minimumDistance: 8)
            .onChanged { value in
                guard model.zoom.scale > 1 else { return }
                let delta = CGSize(
                    width: value.translation.width - lastTranslation.width,
                    height: value.translation.height - lastTranslation.height)
                lastTranslation = value.translation
                model.zoom.transform(centroid: value.location, pan: delta, zoom: 1)
            }
            .onEnded { _ in lastTranslation = .zero }
    }

    private var chrome: some View {
        VStack(spacing: 0) {
            if let countdown = model.countdown {
                ProgressView(value: countdown.fraction)
                    .progressViewStyle(.linear)
                    .tint(palette.onOverlay)
                    .accessibilityHidden(true)
            }
            HStack(spacing: OverlayChrome.gap) {
                ControlButton(systemImage: "chevron.backward", label: "All cameras") {
                    model.closeFullscreen()
                }
                if let session {
                    StatusPill(
                        state: session.tileState, lastFrameAt: session.lastFrameAt,
                        height: OverlayChrome.controlHeight
                    )
                    .layoutPriority(-1)
                } else {
                    StatusPill(state: .connection(.connecting), lastFrameAt: nil, height: OverlayChrome.controlHeight)
                        .layoutPriority(-1)
                }
                Spacer(minLength: 0)
                ControlButton(systemImage: "pause.fill", label: "Pause \(camera.name)") {
                    model.pause(camera.id)
                }
                SoundModeButton(model: model)
            }
            .padding(OverlayChrome.margin)
            .dynamicTypeSize(...DynamicTypeSize.accessibility2)
            Spacer()
            VStack(spacing: OverlayChrome.gap) {
                OverlayPill(height: OverlayChrome.controlHeight) {
                    Text(camera.name)
                        .font(.subheadline.weight(.semibold))
                        .lineLimit(1)
                }
                .accessibilityHidden(true)
                if let countdown = model.countdown {
                    InactivityNotice(countdown: countdown) { model.userInteracted() }
                }
                AnnouncementView(model: model)
            }
            .padding(OverlayChrome.margin)
            .dynamicTypeSize(...DynamicTypeSize.accessibility2)
        }
    }
}

/// Says when the grid is coming back, and offers the way to stay. Tapping the
/// picture stays too, but the gesture deserves something visible beside it.
struct InactivityNotice: View {
    let countdown: InactivityCountdown
    let onStay: () -> Void
    @Environment(\.viewerPalette) private var palette

    var body: some View {
        OverlayPill(height: OverlayChrome.controlHeight) {
            // Not a live region: a screen reader announcing a new number every
            // second would be its own kind of alarm.
            Text("All cameras in \(countdown.remainingSeconds)s")
                .font(.subheadline)
                .monospacedDigit()
            Button("Stay", action: onStay)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(palette.audible)
                .accessibilityHint("Keeps this camera on screen")
        }
    }
}

/// The one button for the whole speaker: off, one room at a time, every room
/// at once. It shows the stored setting.
struct SoundModeButton: View {
    let model: MonitorModel

    var body: some View {
        ControlButton(systemImage: icon, label: model.soundModeActionLabel) {
            model.cycleSoundMode()
        }
        .accessibilityValue(value)
    }

    private var icon: String {
        switch model.settings.soundMode {
        case .off: "speaker.slash.fill"
        case .rotating: "speaker.wave.2.fill"
        case .allAloud: "dot.radiowaves.left.and.right"
        }
    }

    private var value: String {
        switch model.settings.soundMode {
        case .off: "Sound off"
        case .rotating: "One camera at a time"
        case .allAloud: "Every camera aloud"
        }
    }
}

/// What a button press just did, in words: the icons step between states and
/// no icon says which state a tap just put them in.
struct AnnouncementView: View {
    let model: MonitorModel

    var body: some View {
        if let announcement = model.announcement {
            OverlayNotice(text: announcement.text)
                .id(announcement.id)
                .transition(.opacity)
                .allowsHitTesting(false)
                // Announced to VoiceOver by the screen when it changes.
                .accessibilityHidden(true)
        }
    }
}

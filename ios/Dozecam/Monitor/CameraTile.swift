import SwiftUI

/// One camera in the grid. Tiles are independent on purpose: one camera
/// stalling must not disturb the others, and its status pill tells the truth
/// about that camera alone. It shows a session rather than owning one, so the
/// state it reads is the session's, carried across into fullscreen and back.
struct CameraTile: View {
    let camera: Camera
    let session: CameraSession?
    /// Whether this camera's picture is drawn here; not while it has the
    /// screen to itself, where the fullscreen surface holds its player view.
    let showsPicture: Bool
    /// Whether this is a camera being heard, marked so sound never has an
    /// unseen source.
    let audible: Bool
    let onOpen: () -> Void
    let onPause: () -> Void
    @Environment(\.viewerPalette) private var palette

    private var state: StatusText.TileState { session?.tileState ?? .connection(.connecting) }

    var body: some View {
        ZStack {
            palette.emptyTile
            if showsPicture, let session {
                PlayerSurface(player: session.player)
            }
            if case .unsupported(let codec) = state {
                OverlayNotice(text: "This device cannot play \(codec) video.", attention: true)
                    .padding(OverlayChrome.margin)
            }
        }
        .contentShape(Rectangle())
        .onTapGesture(perform: onOpen)
        .overlay(alignment: .topLeading) {
            StatusPill(state: state, lastFrameAt: session?.lastFrameAt)
                .padding(OverlayChrome.margin)
                // Its footprint beside the pause button is given up front, so
                // a long status can never slide under it.
                .padding(.trailing, OverlayChrome.tileHeight + OverlayChrome.gap)
                .dynamicTypeSize(...DynamicTypeSize.accessibility1)
        }
        .overlay(alignment: .topTrailing) {
            Button(action: onPause) {
                IconPill(systemImage: "pause.fill")
                    // A target for a thumb around a pill sized for a picture.
                    .frame(width: 44, height: 44)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .padding(OverlayChrome.margin - 6)
            .accessibilityLabel("Pause \(camera.name)")
        }
        .overlay(alignment: .bottomLeading) {
            OverlayPill {
                Text(camera.name)
                    .font(.footnote.weight(.semibold))
                    .lineLimit(1)
            }
            .padding(OverlayChrome.margin)
            .padding(.trailing, audible ? OverlayChrome.tileHeight + OverlayChrome.gap : 0)
            .dynamicTypeSize(...DynamicTypeSize.accessibility1)
            .accessibilityHidden(true)
        }
        .overlay(alignment: .bottomTrailing) {
            if audible {
                AudibleBadge(cameraName: camera.name)
                    .padding(OverlayChrome.margin)
            }
        }
        .overlay {
            if audible {
                Rectangle().strokeBorder(palette.audible, lineWidth: 2).allowsHitTesting(false)
            }
        }
        .clipped()
        .accessibilityElement(children: .contain)
        .accessibilityLabel(camera.name)
        .accessibilityAction(named: "Open \(camera.name)", onOpen)
    }
}

/// Marks the camera the sound is coming from: in a grid the sound moves on a
/// timer, and guessing which room it is is the failure a baby monitor cannot
/// afford.
struct AudibleBadge: View {
    let cameraName: String
    var height: CGFloat = OverlayChrome.tileHeight
    @Environment(\.viewerPalette) private var palette

    var body: some View {
        IconPill(systemImage: "speaker.wave.2.fill", size: height, tint: palette.audible)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel("Sound from \(cameraName)")
    }
}

/// A paused camera's slot: no picture, no session, and a plain statement that
/// nobody is watching this room, with the way back on it. Kept in the grid
/// rather than dropped from it: a room that vanished would read as a camera
/// lost.
struct PausedTile: View {
    let camera: Camera
    let onResume: () -> Void
    @Environment(\.viewerPalette) private var palette

    var body: some View {
        VStack(spacing: OverlayChrome.gap) {
            Text(camera.name)
                .font(.headline)
                .foregroundStyle(palette.onOverlay)
                .lineLimit(1)
            Text("Paused · not being watched")
                .font(.subheadline)
                .foregroundStyle(palette.onOverlayVariant)
                .multilineTextAlignment(.center)
            Button(action: onResume) {
                Label("Resume", systemImage: "play.fill")
                    .font(.subheadline.weight(.semibold))
                    .padding(.horizontal, 16)
                    .frame(minHeight: OverlayChrome.controlHeight)
                    .foregroundStyle(palette.onControl)
                    .background(palette.control, in: Capsule())
                    .overlay(Capsule().strokeBorder(palette.overlayOutline, lineWidth: 1))
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Resume \(camera.name)")
        }
        .dynamicTypeSize(...DynamicTypeSize.accessibility1)
        .padding(OverlayChrome.margin)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(palette.pausedTile)
        .accessibilityElement(children: .contain)
    }
}

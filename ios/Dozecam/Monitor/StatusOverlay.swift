import SwiftUI

/// The measurements every piece of chrome on the viewer shares, so whatever
/// sits in a row sits in a line (Android's `OverlayChrome`). Heights are
/// floors: text grows with Dynamic Type and a pill grows with it.
enum OverlayChrome {
    /// From the edge of the picture, or the screen, to the nearest chrome.
    static let margin: CGFloat = 12
    /// Between two pieces of chrome in one row or column.
    static let gap: CGFloat = 8
    /// Buttons, and every pill sharing a row with one.
    static let controlHeight: CGFloat = 44
    /// Pills inside a grid tile: captions on a picture, a size down.
    static let tileHeight: CGFloat = 32
    static let iconSize: CGFloat = 14
    static let pillPadding: CGFloat = 12
}

/// One line of chrome over the picture: a rounded, outlined surface dark
/// enough to read over a nursery lit by a lamp or a window blown out to white.
struct OverlayPill<Content: View>: View {
    var height: CGFloat = OverlayChrome.tileHeight
    var horizontalPadding: CGFloat = OverlayChrome.pillPadding
    @ViewBuilder var content: Content
    @Environment(\.viewerPalette) private var palette

    var body: some View {
        HStack(spacing: OverlayChrome.gap) { content }
            .padding(.horizontal, horizontalPadding)
            .frame(minHeight: height)
            .foregroundStyle(palette.onOverlay)
            .background(palette.overlay, in: shape)
            .overlay(shape.strokeBorder(palette.overlayOutline, lineWidth: 1))
    }

    /// A capsule on one line; a rounded box when the text wraps to two.
    private var shape: RoundedRectangle { RoundedRectangle(cornerRadius: height / 2, style: .continuous) }
}

/// A pill with nothing but its glyph: the same surface closed up to a circle.
struct IconPill: View {
    let systemImage: String
    var size: CGFloat = OverlayChrome.tileHeight
    var tint: Color?
    @Environment(\.viewerPalette) private var palette

    var body: some View {
        Image(systemName: systemImage)
            .font(.system(size: OverlayChrome.iconSize, weight: .bold))
            .foregroundStyle(tint ?? palette.onOverlay)
            .frame(width: size, height: size)
            .background(palette.overlay, in: Circle())
            .overlay(Circle().strokeBorder(palette.overlayOutline, lineWidth: 1))
    }
}

/// A sentence over the picture: a notice rather than a caption.
struct OverlayNotice: View {
    let text: String
    var attention = false
    @Environment(\.viewerPalette) private var palette

    var body: some View {
        Text(text)
            .font(.callout)
            .multilineTextAlignment(.leading)
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
            .foregroundStyle(attention ? palette.onAttention : palette.onOverlay)
            .background(
                attention ? palette.attention : palette.overlay,
                in: RoundedRectangle(cornerRadius: 16, style: .continuous)
            )
            .overlay(
                RoundedRectangle(cornerRadius: 16, style: .continuous)
                    .strokeBorder(palette.overlayOutline, lineWidth: 1))
    }
}

/// Honest connection state, always visible: the word, the colour and the
/// icon's silhouette, so the state reads across a dark room, to someone who
/// cannot tell the hues apart, and in the night palette where every hue is
/// red. While not live it says how old the picture is, re-read every second:
/// the question a stalled tile raises is how long it has been frozen.
struct StatusPill: View {
    let state: StatusText.TileState
    let lastFrameAt: Date?
    var height: CGFloat = OverlayChrome.tileHeight
    @Environment(\.viewerPalette) private var palette

    var body: some View {
        if state != .connection(.live), lastFrameAt != nil {
            TimelineView(.periodic(from: .now, by: 1)) { context in
                pill(now: context.date)
            }
        } else {
            pill(now: .now)
        }
    }

    private func pill(now: Date) -> some View {
        OverlayPill(height: height) {
            Image(systemName: icon)
                .font(.system(size: OverlayChrome.iconSize, weight: .bold))
                .foregroundStyle(color)
                .accessibilityHidden(true)
            Text(StatusText.text(state, lastFrameAt: lastFrameAt, now: now))
                .font(.footnote.weight(.semibold))
                // Wrapped before any is cut: the age is the half of a long
                // status a narrow tile would otherwise lose.
                .lineLimit(3)
                .fixedSize(horizontal: false, vertical: true)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(StatusText.spokenText(state, lastFrameAt: lastFrameAt, now: now))
    }

    private var icon: String {
        switch state {
        case .connection(.live): "antenna.radiowaves.left.and.right"
        case .connection(.connecting), .connection(.reconnecting): "arrow.triangle.2.circlepath"
        case .connection(.offline): "video.slash.fill"
        case .unsupported: "exclamationmark.triangle.fill"
        }
    }

    /// A stream being fetched and one being fetched again are the same fact to
    /// anyone looking; the attempt count tells them apart.
    private var color: Color {
        switch state {
        case .connection(.live): palette.live
        case .connection(.connecting), .connection(.reconnecting): palette.connecting
        case .connection(.offline), .unsupported: palette.offline
        }
    }
}

/// A round control over or beside the picture, at a thumb's size.
struct ControlButton: View {
    let systemImage: String
    let label: String
    var attention = false
    let action: () -> Void
    @Environment(\.viewerPalette) private var palette

    var body: some View {
        Button(action: action) {
            Image(systemName: systemImage)
                .font(.system(size: 18, weight: .semibold))
                .frame(width: OverlayChrome.controlHeight, height: OverlayChrome.controlHeight)
                .foregroundStyle(attention ? palette.onAttention : palette.onControl)
                .background(attention ? palette.attention : palette.control, in: Circle())
                .overlay(Circle().strokeBorder(palette.overlayOutline, lineWidth: 1))
                .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
    }
}

extension CameraSession {
    var tileState: StatusText.TileState {
        unsupportedCodec.map { .unsupported(codec: $0) } ?? .connection(connection)
    }
}

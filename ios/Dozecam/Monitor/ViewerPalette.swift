import SwiftUI

/// The viewer's colours. The grid always sits on black, so the letterboxing
/// around a picture is part of the picture, and its chrome is dark whatever
/// the system appearance: a white pill in a nursery at night is a lamp.
///
/// The night palette is Android's `NightRedColorScheme`: dim red throughout,
/// minimal blue light, low luminance, still readable. Connection states keep
/// apart there by word and icon silhouette, not only by hue.
struct ViewerPalette: Equatable {
    var background: Color
    /// A tile with no picture yet: a slot of its own, not a gap in the grid.
    var emptyTile: Color
    /// Pills and notices over the picture, already at their overlay opacity.
    var overlay: Color
    var overlayOutline: Color
    var onOverlay: Color
    var onOverlayVariant: Color
    var live: Color
    var connecting: Color
    var offline: Color
    /// The audible border and badge.
    var audible: Color
    var control: Color
    var onControl: Color
    /// Error-styled controls and notices (alerts off, no network).
    var attention: Color
    var onAttention: Color
    var pausedTile: Color
    /// The empty state's page, or nil to follow the system appearance.
    var page: Color?
    var onPage: Color?

    /// How opaque chrome over video is: enough of it survives a picture blown
    /// out to white, and it still reads as glass over the room.
    static let overlayOpacity = 0.85

    static let standard = ViewerPalette(
        background: .black,
        emptyTile: Color(white: 0.07),
        overlay: Color(white: 0.11).opacity(overlayOpacity),
        overlayOutline: Color(white: 1, opacity: 0.16),
        onOverlay: .white,
        onOverlayVariant: Color(white: 0.78),
        live: Color(red: 0.19, green: 0.82, blue: 0.35),
        connecting: Color(red: 1, green: 0.75, blue: 0.22),
        offline: Color(red: 1, green: 0.42, blue: 0.38),
        audible: Color(red: 0.39, green: 0.71, blue: 1),
        control: Color(white: 0.18),
        onControl: .white,
        attention: Color(red: 0.45, green: 0.1, blue: 0.09),
        onAttention: Color(red: 1, green: 0.85, blue: 0.84),
        pausedTile: Color(white: 0.12),
        page: nil,
        onPage: nil
    )

    static let night = ViewerPalette(
        background: .black,
        emptyTile: Color(hex: 0x080000),
        overlay: Color(hex: 0x160505).opacity(overlayOpacity),
        overlayOutline: Color(hex: 0x3A1817),
        onOverlay: Color(hex: 0xB05A58),
        onOverlayVariant: Color(hex: 0x9A4A48),
        live: Color(hex: 0xB94A48),
        connecting: Color(hex: 0xA85250),
        offline: Color(hex: 0xD96360),
        audible: Color(hex: 0xB94A48),
        control: Color(hex: 0x1E0808),
        onControl: Color(hex: 0xB05A58),
        attention: Color(hex: 0x450E0E),
        onAttention: Color(hex: 0xE89896),
        pausedTile: Color(hex: 0x160505),
        page: Color(hex: 0x0A0000),
        onPage: Color(hex: 0xB05A58)
    )

    static func of(nightTheme: Bool) -> ViewerPalette { nightTheme ? .night : .standard }

    /// For the empty viewer, a page rather than a picture: the standard
    /// palette's controls follow the system appearance there.
    var forPage: ViewerPalette {
        guard page == nil else { return self }
        var palette = self
        palette.control = Color(.tertiarySystemFill)
        palette.onControl = .primary
        palette.overlayOutline = .clear
        return palette
    }
}

extension Color {
    fileprivate init(hex: UInt32) {
        self.init(
            red: Double((hex >> 16) & 0xFF) / 255, green: Double((hex >> 8) & 0xFF) / 255,
            blue: Double(hex & 0xFF) / 255)
    }
}

extension EnvironmentValues {
    @Entry var viewerPalette: ViewerPalette = .standard
}

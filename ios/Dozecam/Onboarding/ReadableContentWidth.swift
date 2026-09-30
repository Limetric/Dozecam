import SwiftUI

extension View {
    /// Keeps a scroll view's content, a `Form` or `List` included, to a
    /// comfortable reading width on a wide window (an iPad in landscape, or
    /// Stage Manager), while the scroll view itself, its background and its
    /// indicators still span the window. Narrower windows are left alone.
    func readableContentWidth(_ maxWidth: CGFloat = 640) -> some View {
        modifier(ReadableContentWidth(maxWidth: maxWidth))
    }
}

private struct ReadableContentWidth: ViewModifier {
    let maxWidth: CGFloat
    @Environment(\.horizontalSizeClass) private var sizeClass
    @State private var width: CGFloat = 0

    /// What an inset-grouped list uses by itself; overriding the margins
    /// replaces it, so it is the floor.
    private var standardMargin: CGFloat { sizeClass == .regular ? 20 : 16 }

    private var margin: CGFloat { max(standardMargin, (width - maxWidth) / 2) }

    func body(content: Content) -> some View {
        content
            .contentMargins(.horizontal, margin, for: .scrollContent)
            .onGeometryChange(for: CGFloat.self) { proxy in
                proxy.size.width
            } action: { newWidth in
                width = newWidth
            }
    }
}

import CoreGraphics

/// Where a pinch has put the one camera filling the screen, the port of
/// Android's `PinchZoomState`: how far in, and which part of the room is under
/// the middle of the view.
///
/// The identity is the floor: the picture can be looked into but not shrunk
/// below the screen. Panning is bounded by the picture itself, not the screen:
/// a letterboxed frame stops at its own edge, and along an axis it does not
/// yet overflow it stays centred, because a zoomed view of bare letterbox
/// black reads as a camera with something wrong with it.
struct PinchZoom: Equatable {
    /// Close enough to fill the screen with a cot; an IP camera's frame turns
    /// to mush well before this.
    static let maxScale: CGFloat = 5

    private(set) var scale: CGFloat = 1
    /// The picture's centre, from the view's centre.
    private(set) var offset: CGSize = .zero
    private var bounds: CGSize = .zero
    private var pictureAspect: Double?

    /// The view was laid out (or rotated): re-clamped at once, since an offset
    /// fine in portrait can hold the picture off a landscape screen.
    mutating func viewportChanged(_ bounds: CGSize) {
        guard self.bounds != bounds else { return }
        self.bounds = bounds
        offset = clamped(offset, scale: scale)
    }

    /// The stream declared its shape; the letterbox bars just moved.
    mutating func pictureChanged(_ aspect: Double?) {
        guard pictureAspect != aspect else { return }
        pictureAspect = aspect
        offset = clamped(offset, scale: scale)
    }

    /// Folds one gesture update in. The picture point under the fingers stays
    /// under them: the new offset is whatever closes the gap between where it
    /// was and where the centroid went, then the edges get the last word.
    mutating func transform(centroid: CGPoint, pan: CGSize, zoom: CGFloat) {
        let newScale = min(max(scale * zoom, 1), Self.maxScale)
        let fromCenter = CGSize(width: centroid.x - bounds.width / 2, height: centroid.y - bounds.height / 2)
        let ratio = newScale / scale
        let moved = CGSize(
            width: fromCenter.width + pan.width - (fromCenter.width - offset.width) * ratio,
            height: fromCenter.height + pan.height - (fromCenter.height - offset.height) * ratio)
        scale = newScale
        offset = clamped(moved, scale: newScale)
    }

    /// Whole picture, dead centre: where every camera starts.
    mutating func reset() {
        scale = 1
        offset = .zero
    }

    /// The rectangle the picture paints before zooming: its own shape fitted
    /// inside the view. Until the shape is known the whole view stands in.
    private func picture() -> CGSize {
        guard let aspect = pictureAspect, aspect > 0, bounds.width > 0, bounds.height > 0 else { return bounds }
        if bounds.width / bounds.height > aspect {
            return CGSize(width: bounds.height * aspect, height: bounds.height)
        }
        return CGSize(width: bounds.width, height: bounds.width / aspect)
    }

    private func clamped(_ offset: CGSize, scale: CGFloat) -> CGSize {
        let picture = picture()
        let maxX = max(0, (scale * picture.width - bounds.width) / 2)
        let maxY = max(0, (scale * picture.height - bounds.height) / 2)
        return CGSize(width: min(max(offset.width, -maxX), maxX), height: min(max(offset.height, -maxY), maxY))
    }
}

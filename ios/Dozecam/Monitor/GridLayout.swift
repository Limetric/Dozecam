import CoreGraphics

/// How the grid places its tiles in the space it has: an iPhone in portrait,
/// an iPad in landscape, a Split View or Stage Manager window of any size.
///
/// Every tile keeps a 16:9 slot (the picture letterboxes inside it, so a 4:3
/// camera still shows its whole frame). Seeing every room at once is the
/// point, so when all of them fit at a readable size the grid picks the
/// column count that makes them largest and does not scroll. When they do not
/// fit, it falls back to Android's rule, a scrolling grid of one column below
/// 600 pt of width, two below 1000 pt, three above, rather than shrinking
/// tiles past the point of telling a sleeping child from a pillow.
struct GridLayout: Equatable {
    static let spacing: CGFloat = 2
    static let tileAspect: CGFloat = 16 / 9
    /// The narrowest a tile may be in a grid that fits on screen.
    static let minFittedTileWidth: CGFloat = 240

    let columns: Int
    let rows: Int
    let tileSize: CGSize
    /// Whether the tiles run past the bottom of the space.
    let scrolls: Bool

    static func of(count: Int, in size: CGSize) -> GridLayout {
        guard count > 0, size.width > 0, size.height > 0 else {
            return GridLayout(columns: max(count, 1), rows: count > 0 ? 1 : 0, tileSize: .zero, scrolls: false)
        }
        var best: GridLayout?
        for columns in 1...count {
            let rows = (count + columns - 1) / columns
            let byWidth = (size.width - spacing * CGFloat(columns - 1)) / CGFloat(columns)
            let byHeight = (size.height - spacing * CGFloat(rows - 1)) / CGFloat(rows) * tileAspect
            let width = min(byWidth, byHeight)
            // More columns only when that makes the tiles bigger: equal sizes
            // keep the fewer, wider rows.
            if width > (best?.tileSize.width ?? 0) + 0.5 {
                best = GridLayout(
                    columns: columns, rows: rows, tileSize: CGSize(width: width, height: width / tileAspect),
                    scrolls: false)
            }
        }
        // One camera always fits: there is nothing to scroll to.
        if let best, count == 1 || best.tileSize.width >= min(minFittedTileWidth, size.width) {
            return best
        }
        let columns = min(size.width >= 1000 ? 3 : size.width >= 600 ? 2 : 1, count)
        let width = (size.width - spacing * CGFloat(columns - 1)) / CGFloat(columns)
        return GridLayout(
            columns: columns, rows: (count + columns - 1) / columns,
            tileSize: CGSize(width: width, height: width / tileAspect), scrolls: true)
    }
}

import CoreGraphics
import Testing

@testable import Dozecam

struct GridLayoutTests {
    struct Case: Sendable, CustomTestStringConvertible {
        let name: String
        let count: Int
        let size: CGSize
        let columns: Int
        let rows: Int
        let scrolls: Bool
        var testDescription: String { name }
    }

    // Sizes are the space under the control row, in points.
    @Test(arguments: [
        Case(
            name: "one camera on an iPhone in portrait", count: 1, size: .init(width: 402, height: 730),
            columns: 1, rows: 1, scrolls: false),
        Case(
            name: "four cameras on an iPhone in portrait fit in a column", count: 4,
            size: .init(width: 402, height: 730), columns: 1, rows: 4, scrolls: false),
        Case(
            name: "six cameras on an iPhone in portrait scroll in one column", count: 6,
            size: .init(width: 402, height: 730), columns: 1, rows: 6, scrolls: true),
        Case(
            name: "six cameras on an iPhone in landscape", count: 6, size: .init(width: 874, height: 330),
            columns: 3, rows: 2, scrolls: false),
        Case(
            name: "two cameras on an iPhone in landscape sit side by side", count: 2,
            size: .init(width: 874, height: 330), columns: 2, rows: 1, scrolls: false),
        Case(
            name: "six cameras on a 13-inch iPad in landscape", count: 6, size: .init(width: 1376, height: 956),
            columns: 2, rows: 3, scrolls: false),
        Case(
            name: "six cameras on a 13-inch iPad in portrait", count: 6, size: .init(width: 1032, height: 1300),
            columns: 2, rows: 3, scrolls: false),
        Case(
            name: "four cameras on a 13-inch iPad in landscape", count: 4, size: .init(width: 1376, height: 956),
            columns: 2, rows: 2, scrolls: false),
        Case(
            name: "nine cameras on a 13-inch iPad in landscape", count: 9, size: .init(width: 1376, height: 956),
            columns: 3, rows: 3, scrolls: false),
        Case(
            name: "six cameras in a narrow Split View column", count: 6, size: .init(width: 320, height: 956),
            columns: 1, rows: 6, scrolls: false),
        Case(
            name: "six cameras in a short Slide Over window scroll", count: 6, size: .init(width: 320, height: 600),
            columns: 1, rows: 6, scrolls: true),
        Case(
            name: "six cameras in a half-width Split View", count: 6, size: .init(width: 678, height: 956),
            columns: 2, rows: 3, scrolls: false),
        Case(
            name: "three cameras in a small Stage Manager window", count: 3, size: .init(width: 500, height: 360),
            columns: 2, rows: 2, scrolls: false),
        Case(
            name: "twelve cameras in a small Stage Manager window", count: 12, size: .init(width: 700, height: 400),
            columns: 2, rows: 6, scrolls: true),
    ])
    func layout(_ testCase: Case) {
        let layout = GridLayout.of(count: testCase.count, in: testCase.size)
        #expect(layout.columns == testCase.columns)
        #expect(layout.rows == testCase.rows)
        #expect(layout.scrolls == testCase.scrolls)
        // Every tile is 16:9, and the columns fit the width.
        #expect(abs(layout.tileSize.width / layout.tileSize.height - 16.0 / 9.0) < 0.001)
        let width = layout.tileSize.width * CGFloat(layout.columns) + GridLayout.spacing * CGFloat(layout.columns - 1)
        #expect(width <= testCase.size.width + 0.001)
        if !layout.scrolls {
            let height = layout.tileSize.height * CGFloat(layout.rows) + GridLayout.spacing * CGFloat(layout.rows - 1)
            #expect(height <= testCase.size.height + 0.001)
            #expect(layout.tileSize.width >= GridLayout.minFittedTileWidth)
        }
    }

    @Test func aGridThatFitsPicksTheLargestTiles() {
        let size = CGSize(width: 1376, height: 956)
        let chosen = GridLayout.of(count: 6, in: size)
        // Three across would fit too, but smaller.
        let threeAcross = (size.width - 2 * GridLayout.spacing) / 3
        #expect(chosen.tileSize.width > threeAcross)
    }

    @Test func noCamerasIsAnEmptyGrid() {
        let layout = GridLayout.of(count: 0, in: CGSize(width: 400, height: 800))
        #expect(layout.rows == 0)
        #expect(layout.tileSize == .zero)
    }

    @Test func aWindowTooSmallForTheMinimumStillFillsItsWidth() {
        let layout = GridLayout.of(count: 1, in: CGSize(width: 200, height: 100))
        #expect(layout.columns == 1)
        #expect(!layout.scrolls)
        #expect(layout.tileSize.width <= 200)
    }
}

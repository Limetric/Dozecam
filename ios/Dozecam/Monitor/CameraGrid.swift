import SwiftUI

/// Every enabled camera at once, laid out for the space the window has
/// (`GridLayout`): an iPhone in portrait, an iPad in landscape, Split View and
/// Stage Manager windows of any size.
struct CameraGrid: View {
    let model: MonitorModel

    var body: some View {
        GeometryReader { geometry in
            let layout = GridLayout.of(count: model.cameras.count, in: geometry.size)
            ScrollView(.vertical) {
                LazyVGrid(
                    columns: Array(
                        repeating: GridItem(.fixed(layout.tileSize.width), spacing: GridLayout.spacing),
                        count: layout.columns),
                    spacing: GridLayout.spacing
                ) {
                    ForEach(model.cameras) { camera in
                        slot(for: camera)
                            .frame(width: layout.tileSize.width, height: layout.tileSize.height)
                            .onScrollVisibilityChange(threshold: 0.01) { visible in
                                model.tileVisibilityChanged(camera.id, visible: visible)
                            }
                    }
                }
                // A grid that fits sits centred under the controls.
                .frame(minWidth: geometry.size.width, minHeight: geometry.size.height, alignment: .top)
            }
            .scrollDisabled(!layout.scrolls)
            .scrollBounceBehavior(.basedOnSize)
        }
    }

    @ViewBuilder
    private func slot(for camera: Camera) -> some View {
        if model.pausedIds.contains(camera.id) {
            PausedTile(camera: camera) { model.resume(camera.id) }
        } else {
            CameraTile(
                camera: camera,
                session: model.session(for: camera.id),
                showsPicture: model.fullscreenId != camera.id,
                audible: model.audibleIds.contains(camera.id),
                onOpen: { model.open(camera.id) },
                onPause: { model.pause(camera.id) }
            )
        }
    }
}

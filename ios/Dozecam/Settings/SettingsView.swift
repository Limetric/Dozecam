import SwiftUI

/// Settings, presented as a sheet over the viewer. With room for two columns
/// (an iPad, unless Split View has narrowed the app) it is a split view with
/// the sections in a sidebar; otherwise a navigation stack whose root lists
/// them, as the iPhone's Settings app does.
struct SettingsView: View {
    @Bindable var model: SettingsModel
    let onAddCameras: () -> Void
    @Environment(\.dismiss) private var dismiss
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    #if DEBUG
        @State private var consoleDebug = ConsoleDebugModel()
    #endif

    var body: some View {
        Group {
            if horizontalSizeClass == .regular {
                splitView
            } else {
                stack
            }
        }
        .task {
            #if DEBUG
                await SettingsLaunchOptions.seedCamerasIfAsked(into: model.dependencies.cameras)
            #endif
            await model.observe()
        }
        .sheet(isPresented: $model.isAddingByURL) {
            AddCameraByURLSheet(model: model)
        }
        .alert(
            "Camera not saved",
            isPresented: Binding(get: { model.cameraError != nil }, set: { if !$0 { model.cameraError = nil } })
        ) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(model.cameraError ?? "")
        }
        // Large enough on an iPad for the sidebar and a page side by side.
        .presentationSizing(.page)
    }

    // MARK: iPhone

    private var stack: some View {
        NavigationStack(path: stackPath) {
            SettingsIndex(model: model, rowStyle: .link)
                .navigationTitle("Settings")
                .toolbar { doneButton }
                .navigationDestination(for: SettingsSection.self) { section in
                    page(section)
                }
        }
        .searchable(text: $model.query, prompt: "Search settings")
    }

    /// One page deep: the pushed page is the selection.
    private var stackPath: Binding<[SettingsSection]> {
        Binding(
            get: { model.selection.map { [$0] } ?? [] },
            set: { model.selection = $0.last })
    }

    // MARK: iPad

    private var splitView: some View {
        NavigationSplitView {
            SettingsIndex(model: model, rowStyle: .sidebar)
                .navigationTitle("Settings")
                .toolbar { doneButton }
                .searchable(text: $model.query, placement: .sidebar, prompt: "Search settings")
        } detail: {
            NavigationStack {
                page(model.selection ?? .defaultDetail)
            }
        }
        .navigationSplitViewStyle(.balanced)
    }

    // MARK: Shared

    private func page(_ section: SettingsSection) -> some View {
        #if DEBUG
            SettingsPage(section: section, model: model, onAddCameras: onAddCameras, consoleDebug: consoleDebug)
        #else
            SettingsPage(section: section, model: model, onAddCameras: onAddCameras)
        #endif
    }

    private var doneButton: some ToolbarContent {
        ToolbarItem(placement: .confirmationAction) {
            Button("Done") { dismiss() }
        }
    }
}

/// The list of sections, or search results while there is a query: the
/// iPhone's root list and the iPad's sidebar.
private struct SettingsIndex: View {
    enum RowStyle {
        /// Rows push their page (a stack).
        case link
        /// Rows select their page (a sidebar with a selection).
        case sidebar
    }

    @Bindable var model: SettingsModel
    let rowStyle: RowStyle

    var body: some View {
        Group {
            if model.isSearching {
                searchResults
            } else if rowStyle == .sidebar {
                List(selection: sidebarSelection) { sections }
            } else {
                List { sections }
            }
        }
    }

    private var sidebarSelection: Binding<SettingsSection?> {
        Binding(get: { model.selection ?? .defaultDetail }, set: { model.selection = $0 })
    }

    @ViewBuilder private var sections: some View {
        ForEach(Array(SettingsSection.groups.enumerated()), id: \.offset) { _, group in
            Section {
                ForEach(group) { section in
                    NavigationLink(value: section) {
                        SectionRow(section: section, model: model)
                    }
                }
            }
        }
    }

    @ViewBuilder private var searchResults: some View {
        let results = model.searchResults
        if results.isEmpty {
            ContentUnavailableView.search(text: model.query)
        } else {
            List {
                ForEach(results) { entry in
                    Button {
                        model.open(entry)
                    } label: {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(entry.title).foregroundStyle(.primary)
                            Text([entry.section.title, entry.detail].compactMap(\.self).joined(separator: " · "))
                                .font(.footnote)
                                .foregroundStyle(.secondary)
                        }
                    }
                    .accessibilityHint("Opens \(entry.section.title)")
                }
            }
        }
    }
}

private struct SectionRow: View {
    let section: SettingsSection
    let model: SettingsModel

    var body: some View {
        Label {
            VStack(alignment: .leading, spacing: 2) {
                Text(section.title)
                if let summary {
                    Text(summary)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }
        } icon: {
            Image(systemName: section.systemImage)
        }
    }

    /// The summary, or for Cameras how many are on; for About, the version.
    private var summary: String? {
        switch section {
        case .cameras where !model.cameras.isEmpty:
            let on = model.cameras.count(where: \.enabled)
            return "\(on) of \(model.cameras.count) switched on"
        case .about:
            return model.buildInfo.summary
        default:
            return section.summary
        }
    }
}

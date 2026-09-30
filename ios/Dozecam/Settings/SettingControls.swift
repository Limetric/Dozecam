import SwiftUI

/// A switch with its title, what it does, and an icon: Android's
/// `SettingSwitchRow`.
struct SettingToggleRow: View {
    let setting: ToggleSetting
    let model: SettingsModel

    var body: some View {
        Toggle(isOn: Binding(get: { model.isOn(setting) }, set: { model.set(setting, $0) })) {
            Label {
                VStack(alignment: .leading, spacing: 2) {
                    Text(setting.title)
                    Text(setting.description)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            } icon: {
                Image(systemName: setting.systemImage)
            }
        }
        .id(setting.id)
    }
}

/// A slider with its title and value above it and its footnote below. While
/// it is being dragged it shows where the finger is, not the store's echo of
/// a moment ago; every change is still written as it happens, as on Android,
/// snapped to the setting's step.
struct SettingSliderRow: View {
    let setting: SliderSetting
    let model: SettingsModel
    @State private var draft: Double?
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    var body: some View {
        let shown = draft ?? model.value(setting)
        VStack(alignment: .leading, spacing: 6) {
            header(shown)
                // The slider carries the title and the value for VoiceOver.
                .accessibilityHidden(true)
            Slider(
                value: Binding(
                    get: { shown },
                    set: { value in
                        draft = value
                        model.set(setting, value)
                    }),
                // No `step:` here: iOS draws a tick for every step, which at
                // 1 % is a dotted smear. The model snaps instead.
                in: setting.range
            ) {
                Text(setting.title)
            } onEditingChanged: { editing in
                if !editing { draft = nil }
            }
            .accessibilityValue(setting.spokenValue(shown))
            if let footnote = setting.footnote {
                Text(footnote)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .padding(.vertical, 4)
        .id(setting.id)
    }

    @ViewBuilder private func header(_ value: Double) -> some View {
        let valueText = Text([setting.formatted(value), setting.qualifier].compactMap(\.self).joined(separator: " "))
            .monospacedDigit()
            .foregroundStyle(.secondary)
        // Side by side until the text is too big to share a line.
        if dynamicTypeSize.isAccessibilitySize {
            VStack(alignment: .leading, spacing: 2) {
                Text(setting.title)
                valueText
            }
        } else {
            HStack(alignment: .firstTextBaseline) {
                Text(setting.title)
                Spacer(minLength: 12)
                valueText.multilineTextAlignment(.trailing)
            }
        }
    }
}

/// The live level with the threshold marked, for setting the threshold
/// against the room itself (Android's `AudioLevelMeter`): scaled so the
/// useful 0...0.5 of RMS fills the bar, and red once the level is at or above
/// the threshold. An unknown level shows no bar at all, never an empty one.
struct LevelMeter: View {
    let level: Float?
    let threshold: Float

    /// The RMS that fills the bar.
    static let scale: Float = 0.5

    private var triggered: Bool { (level ?? 0) >= threshold && level != nil }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            GeometryReader { geometry in
                let width = geometry.size.width
                ZStack(alignment: .leading) {
                    Capsule().fill(Color(.systemFill))
                    if let level {
                        Capsule()
                            .fill(triggered ? Color.red : Color.accentColor)
                            .frame(width: width * CGFloat(min(max(level / Self.scale, 0), 1)))
                            .animation(.easeOut(duration: 0.2), value: level)
                    }
                    Rectangle()
                        .fill(Color.primary)
                        .frame(width: 2)
                        .offset(x: width * CGFloat(min(threshold / Self.scale, 1)) - 1)
                }
            }
            .frame(height: 12)
            if level == nil {
                Text("The meter moves while monitoring is running. The line marks the threshold.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Live audio level")
        .accessibilityValue(accessibilityValue)
    }

    private var accessibilityValue: String {
        guard let level else { return "Not known yet" }
        let percent = Int((level * 100).rounded())
        return "\(percent) percent, \(triggered ? "at or above" : "below") the threshold"
    }
}

extension View {
    /// Brings the row a search result named into view once this page is the
    /// one showing.
    func scrollsToSearchFocus(on section: SettingsSection, model: SettingsModel, proxy: ScrollViewProxy) -> some View {
        task(id: model.focus) {
            guard let focus = model.focus, (model.selection ?? .defaultDetail) == section else { return }
            // Let the page finish arriving before scrolling it.
            try? await Task.sleep(for: .milliseconds(350))
            guard !Task.isCancelled else { return }
            withAnimation { proxy.scrollTo(focus, anchor: .center) }
            model.focus = nil
        }
    }
}

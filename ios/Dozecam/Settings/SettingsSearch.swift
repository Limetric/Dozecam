import Foundation

/// One thing settings search can find, and where it lives: the counterpart of
/// Android's `SettingSearchEntry`. `id` is also the row's scroll id, so a
/// result can bring its row into view.
struct SettingSearchEntry: Equatable, Hashable, Identifiable, Sendable {
    let id: String
    let section: SettingsSection
    let title: String
    /// The current value or what the row says under its title.
    let detail: String?
    let keywords: [String]

    init(id: String, section: SettingsSection, title: String, detail: String? = nil, keywords: [String] = []) {
        self.id = id
        self.section = section
        self.title = title
        self.detail = detail
        self.keywords = keywords
    }
}

enum SettingsSearch {
    static let alertSoundID = "alert-sound"
    static let orientationID = "orientation"
    static let addCamerasID = "add-cameras"
    static let addByURLID = "add-by-url"
    static let checklistID = "checklist"
    static let versionID = "version"

    /// Everything searchable, labelled as the rows render it, current values
    /// included, so search and the pages cannot drift apart. Preference rows
    /// and the camera actions; the cameras themselves are content, not
    /// settings, as on Android.
    static func entries(app: AppSettings, detector: DetectorSettings) -> [SettingSearchEntry] {
        var entries: [SettingSearchEntry] = [
            SettingSearchEntry(
                id: addCamerasID, section: .cameras, title: "Add cameras",
                detail: "From your Protect console", keywords: ["protect", "console", "import", "unifi"]),
            SettingSearchEntry(
                id: addByURLID, section: .cameras, title: "Add by stream URL",
                detail: "An RTSP address", keywords: ["rtsp", "manual", "url", "stream"]),
        ]
        for slider in SliderSetting.allCases {
            let value = slider.value(app: app, detector: detector)
            let shown = [slider.formatted(value), slider.qualifier].compactMap(\.self).joined(separator: " ")
            entries.append(
                SettingSearchEntry(
                    id: slider.id, section: slider.section, title: slider.title, detail: shown,
                    keywords: slider.keywords + [slider.footnote].compactMap(\.self)))
        }
        for toggle in ToggleSetting.allCases {
            entries.append(
                SettingSearchEntry(
                    id: toggle.id, section: toggle.section, title: toggle.title, detail: toggle.description))
        }
        entries += [
            SettingSearchEntry(
                id: alertSoundID, section: .alerts, title: "Alert sound", detail: app.alertSoundTitle,
                keywords: ["tone", "ringtone", "alarm"]),
            SettingSearchEntry(
                id: orientationID, section: .display, title: "Monitor orientation",
                detail: app.orientationLock.description, keywords: ["rotation", "portrait", "landscape"]),
            SettingSearchEntry(
                id: checklistID, section: .checklist, title: "Night checklist",
                detail: "Will Dozecam wake you tonight?", keywords: ["bedtime", "test", "readiness", "ready"]),
            SettingSearchEntry(
                id: versionID, section: .about, title: "Version", keywords: ["about", "build"]),
        ]
        return entries
    }

    /// Case- and diacritic-insensitive substring match. Title hits come
    /// first, then hits on what is under the title or a keyword, so the
    /// obvious answer is on top (Android's `searchSettings`).
    static func search(_ query: String, in entries: [SettingSearchEntry]) -> [SettingSearchEntry] {
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return [] }
        let byTitle = entries.filter { $0.title.localizedStandardContains(trimmed) }
        let rest = entries.filter { entry in
            !entry.title.localizedStandardContains(trimmed)
                && ([entry.detail].compactMap(\.self) + entry.keywords).contains {
                    $0.localizedStandardContains(trimmed)
                }
        }
        return byTitle + rest
    }
}

/// The pages settings is made of, the counterpart of Android's
/// `SettingsCategory` plus the destinations its hub links to (the night
/// checklist) and the About row. On an iPad they are the sidebar; on an
/// iPhone, the rows of the root list.
enum SettingsSection: String, CaseIterable, Hashable, Identifiable, Sendable {
    case cameras
    case detection
    case alerts
    case display
    case checklist
    case about
    #if DEBUG
        case debug
    #endif

    var id: Self { self }

    var title: String {
        switch self {
        case .cameras: "Cameras"
        case .detection: "Sound detection"
        case .alerts: "Alerts"
        case .display: "Display"
        case .checklist: "Night checklist"
        case .about: "About"
        #if DEBUG
            case .debug: "Console debug"
        #endif
        }
    }

    /// What the page holds, under its title in the list (Android's category
    /// summaries).
    var summary: String? {
        switch self {
        case .cameras: "Add and switch on cameras"
        case .detection: "How loud, how long, and when to re-arm"
        case .alerts: "Wake alerts, sound, volume and failures"
        case .display: "Theme, screen, orientation and talk-back"
        case .checklist: "Will Dozecam wake you tonight?"
        case .about: nil
        #if DEBUG
            case .debug: nil
        #endif
        }
    }

    var systemImage: String {
        switch self {
        case .cameras: "video"
        case .detection: "waveform"
        case .alerts: "alarm"
        case .display: "iphone"
        case .checklist: "moon.stars"
        case .about: "info.circle"
        #if DEBUG
            case .debug: "ladybug"
        #endif
        }
    }

    /// The list, grouped: the four setting pages, then the checklist, then
    /// About (and, in debug builds, the console debug page). The same groups
    /// make the iPhone's root list and the iPad's sidebar.
    static let groups: [[SettingsSection]] = {
        var groups: [[SettingsSection]] = [[.cameras, .detection, .alerts, .display], [.checklist], [.about]]
        #if DEBUG
            groups.append([.debug])
        #endif
        return groups
    }()

    /// The page an iPad shows before anything is picked in the sidebar.
    static let defaultDetail = SettingsSection.cameras
}

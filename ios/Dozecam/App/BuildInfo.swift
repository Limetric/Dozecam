import Foundation

/// The version the build carries: MARKETING_VERSION and the commit count that
/// ios/tools/generate.sh writes into Config/Version.xcconfig.
struct BuildInfo: Equatable {
    let version: String
    let build: String
    let bundleID: String

    init(version: String, build: String, bundleID: String) {
        self.version = version
        self.build = build
        self.bundleID = bundleID
    }

    init(infoDictionary: [String: Any]) {
        version = infoDictionary["CFBundleShortVersionString"] as? String ?? "?"
        build = infoDictionary["CFBundleVersion"] as? String ?? "?"
        bundleID = infoDictionary["CFBundleIdentifier"] as? String ?? "?"
    }

    static let current = BuildInfo(infoDictionary: Bundle.main.infoDictionary ?? [:])

    /// The dev variant installs beside the app relied on at night.
    var isDev: Bool { bundleID.hasSuffix(".dev") }

    var summary: String { "\(version) (\(build))\(isDev ? " · dev" : "")" }
}

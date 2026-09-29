import Foundation
import Testing

@testable import Dozecam

struct BuildInfoTests {
    @Test func readsVersionBuildAndBundleID() {
        let info = BuildInfo(infoDictionary: [
            "CFBundleShortVersionString": "0.1.0", "CFBundleVersion": "185", "CFBundleIdentifier": "app.dozecam",
        ])
        #expect(info == BuildInfo(version: "0.1.0", build: "185", bundleID: "app.dozecam"))
        #expect(info.summary == "0.1.0 (185)")
    }

    @Test func marksTheDevVariant() {
        let info = BuildInfo(version: "0.1.0", build: "185", bundleID: "app.dozecam.dev")
        #expect(info.isDev)
        #expect(info.summary == "0.1.0 (185) · dev")
    }

    @Test func missingKeysReadAsUnknown() {
        #expect(BuildInfo(infoDictionary: [:]) == BuildInfo(version: "?", build: "?", bundleID: "?"))
    }

    /// The generated version reaches the app: a numeric marketing version
    /// and the commit count as the build number.
    @Test func theAppCarriesTheGeneratedVersion() throws {
        let info = BuildInfo.current
        #expect(info.version.split(separator: ".").allSatisfy { Int($0) != nil })
        #expect(try #require(Int(info.build)) >= 1)
    }
}

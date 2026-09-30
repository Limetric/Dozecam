import Foundation
import Testing

@testable import Dozecam

/// The accept/reject cases live in `shared/fixtures/stream-url/`, one file per
/// question. One test per Android `StreamUrlValidatorTest` test, running the
/// same named cases.
struct StreamUrlValidatorTests {
    private struct Case<Expected: Codable & Equatable & Sendable>: Codable {
        let name: String
        let url: String
        let expected: Expected
    }

    private struct Table<Expected: Codable & Equatable & Sendable>: Codable {
        let cases: [Case<Expected>]
    }

    private func check<Expected: Codable & Equatable & Sendable>(
        _ name: String, in path: String, _ actual: (String) -> Expected
    ) throws {
        let cases = try Fixtures.decode(Table<Expected>.self, from: path).cases.filter { $0.name == name }
        guard cases.count == 1, let fixture = cases.first else {
            Issue.record("no single fixture case \"\(name)\" in \(path)")
            return
        }
        #expect(actual(fixture.url) == fixture.expected, "\(fixture.name): \"\(fixture.url)\"")
    }

    private func checkValid(_ name: String) throws {
        try check(name, in: "stream-url/valid.json", StreamUrlValidator.isValid)
    }

    private func checkMonitorable(_ name: String) throws {
        try check(name, in: "stream-url/monitorable.json", StreamUrlValidator.isMonitorable)
    }

    private func checkNormalized(_ name: String) throws {
        try check(name, in: "stream-url/normalize.json", StreamUrlValidator.normalize)
    }

    @Test func acceptsPlainRtspUrlWithPortAndTokenPath() throws {
        try checkValid("plain rtsp url with port and token path")
    }

    @Test func acceptsHostnameUrlsAndSurroundingWhitespace() throws {
        try checkValid("hostname url with surrounding whitespace")
    }

    @Test func acceptsUppercaseScheme() throws {
        try checkValid("uppercase scheme")
    }

    @Test(arguments: ["empty input", "whitespace-only input"])
    func rejectsBlankInput(_ name: String) throws {
        try checkValid(name)
    }

    @Test func rejectsNonRtspSchemes() throws {
        try checkValid("http scheme")
    }

    @Test(arguments: ["rtsps url", "rtsps url with Protect's secure-RTSP query param"])
    func acceptsRtspsUrlsIncludingSecureRtspQueryParams(_ name: String) throws {
        try checkValid(name)
    }

    // A stale pre-normalization rtsps entry; normalize() prevents new ones.
    @Test(arguments: [
        "plain rtsp url", "stale pre-normalization rtsps url", "empty input is not monitorable", "http url",
    ])
    func onlyPlainRtspUrlsAreMonitorable(_ name: String) throws {
        try checkMonitorable(name)
    }

    @Test func normalizeRewritesProtectsRtspsConsoleLinkToItsPlayableRtspAlias() throws {
        try checkNormalized("Protect's rtsps console link becomes its playable rtsp alias")
    }

    @Test func normalizeLeavesAnRtspsUrlOnANonStandardPortUntouchedApartFromScheme() throws {
        try checkNormalized("rtsps on a non-standard port changes only the scheme")
    }

    @Test func normalizeIsANoOpForPlainRtspUrlsAndTrimsWhitespace() throws {
        try checkNormalized("plain rtsp is only trimmed")
    }

    // Foundation's URL parsing accepts some of these; the validator must not.
    @Test(arguments: ["scheme and slashes with no host", "opaque rtsp url with no host"])
    func rejectsUrlsWithoutAHost(_ name: String) throws {
        try checkValid(name)
    }

    @Test(arguments: ["host containing spaces", "not a url at all"])
    func rejectsUnparseableInput(_ name: String) throws {
        try checkValid(name)
    }

    // Not shared fixtures: edges of the explicit parse that stands in for
    // java.net.URI, each checked against what Java's URI makes of it.
    @Test(arguments: [
        ("rtsp://user:pw@cam.local:7447/x", true),
        ("rtsp://[::1]:7447/x", true),
        ("rtsp://host.:7447/x", true),
        ("rtsp://123/x", true),
        ("rtsp://host:/x", true),
        ("rtsp://cam_1/x", false),
        ("rtsp://192.168.1.256/x", false),
        ("rtsp://1.2.3.4.5/x", false),
        ("rtsp://-bad/x", false),
        ("rtsp://a..b/x", false),
        ("rtsp://host:abc/x", false),
        ("rtsp:///x", false),
        ("rtsp://h/a%zz", false),
        ("rtsp://h/a|b", false),
    ])
    func acceptsWhatJavasUriAccepts(url: String, expected: Bool) {
        #expect(StreamUrlValidator.isValid(url) == expected, "\"\(url)\"")
    }

    @Test(arguments: [
        ("rtsps://u@[::1]:7441/p%20q?x#f", "rtsp://u@[::1]:7447/p%20q"),
        ("rtsps://h:7441", "rtsp://h:7447"),
    ])
    func normalizeKeepsUserInfoIPv6HostsAndEscapes(url: String, expected: String) {
        #expect(StreamUrlValidator.normalize(url) == expected, "\"\(url)\"")
    }
}

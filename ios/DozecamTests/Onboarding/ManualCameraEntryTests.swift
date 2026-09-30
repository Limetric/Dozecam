import Foundation
import Testing

@testable import Dozecam

/// The manual camera form, held to Android's `CameraFormState` and
/// `SettingsViewModel.saveCamera`.
@MainActor
struct ManualCameraEntryTests {
    private let dependencies = AppDependencies.isolated()

    private func entry(editing: Camera? = nil) -> ManualCameraEntryModel {
        ManualCameraEntryModel(cameras: dependencies.cameras, editing: editing)
    }

    @Test(arguments: [
        ("Nursery", "rtsp://127.0.0.1:18554/nursery", true),
        ("Nursery", "  RTSPS://192.168.1.1:7441/alias?enableSrtp  ", true),
        ("Nursery", "rtsp://[fe80::1]:554/stream", true),
        ("", "rtsp://127.0.0.1:18554/nursery", false),
        ("   ", "rtsp://127.0.0.1:18554/nursery", false),
        ("Nursery", "", false),
        ("Nursery", "http://192.168.1.1/stream", false),
        ("Nursery", "rtsp://", false),
        ("Nursery", "rtsp:token", false),
        ("Nursery", "rtsp://cam 1/stream", false),
    ])
    func savingNeedsANameAndAStreamUrl(name: String, url: String, canSave: Bool) {
        let model = entry()
        model.name = name
        model.url = url
        #expect(model.canSave == canSave)
    }

    @Test func anInvalidUrlSaysWhatIsExpectedOnceSomethingIsTyped() {
        let model = entry()
        #expect(model.urlProblem == nil)
        model.url = "http://192.168.1.1/stream"
        #expect(model.urlProblem == ManualCameraEntryModel.invalidURLMessage)
        model.url = "rtsp://192.168.1.1/stream"
        #expect(model.urlProblem == nil)
    }

    @Test func aNewCameraIsNormalizedTrimmedAndGetsARandomId() async throws {
        let model = entry()
        model.name = "  Nursery "
        model.url = " rtsps://192.168.1.1:7441/alias?enableSrtp "
        #expect(model.rewritesURL)
        #expect(model.normalizedURL == "rtsp://192.168.1.1:7447/alias")

        let camera = try #require(await model.save())

        #expect(camera.name == "Nursery")
        #expect(camera.url == "rtsp://192.168.1.1:7447/alias")
        #expect(camera.protect == nil)
        #expect(camera.enabled)
        // Lowercase UUID, like Android's UUID.randomUUID().toString().
        #expect(UUID(uuidString: camera.id) != nil)
        #expect(camera.id == camera.id.lowercased())
        #expect(dependencies.cameras.cameras == [camera])
    }

    @Test func eachNewCameraGetsItsOwnId() async throws {
        for _ in 0..<2 {
            let model = entry()
            model.name = "Nursery"
            model.url = "rtsp://127.0.0.1:18554/nursery"
            _ = await model.save()
        }
        #expect(Set(dependencies.cameras.cameras.map(\.id)).count == 2)
    }

    @Test func aPlainUrlIsOnlyTrimmed() {
        let model = entry()
        model.url = " rtsp://127.0.0.1:18554/nursery "
        #expect(!model.rewritesURL)
        #expect(model.normalizedURL == "rtsp://127.0.0.1:18554/nursery")
    }

    /// An edit changes only the name and URL: the Protect identity and the
    /// enabled setting, as stored now, stay.
    @Test func anEditKeepsTheIdTheConsoleAndTheEnabledSetting() async throws {
        let original = Camera(
            id: "protect-cam1-1", name: "Nursery", url: "rtsp://192.168.1.1:7447/aliasM",
            protect: ProtectStream(cameraId: "cam1", channel: 1, consoleHost: "192.168.1.1"), enabled: true)
        try await dependencies.cameras.upsert(original)
        let model = entry(editing: original)
        #expect(model.isEditing)
        #expect(model.name == "Nursery")
        #expect(model.url == "rtsp://192.168.1.1:7447/aliasM")
        // Switched off while the form was open.
        try await dependencies.cameras.setEnabled(id: original.id, false)

        model.name = "Nursery cot"
        model.url = "rtsp://192.168.1.1:7447/other"
        let saved = try #require(await model.save())

        #expect(saved.id == original.id)
        #expect(saved.protect == original.protect)
        #expect(!saved.enabled)
        #expect(dependencies.cameras.cameras == [saved])
    }

    @Test func nothingIsSavedWhenTheFormIsIncomplete() async {
        let model = entry()
        model.name = "Nursery"
        #expect(await model.save() == nil)
        #expect(dependencies.cameras.cameras.isEmpty)
    }
}

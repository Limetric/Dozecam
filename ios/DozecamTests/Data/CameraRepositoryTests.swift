import Foundation
import Testing

@testable import Dozecam

struct CameraRepositoryTests {
    let directory: TestDirectory
    let fileURL: URL

    init() throws {
        directory = try TestDirectory()
        fileURL = directory.url.appending(path: "nested/cameras.json")
    }

    let nursery = Camera(
        id: "protect-abc-1", name: "Nursery", url: "rtsp://10.0.0.2:7447/token1",
        protect: ProtectStream(cameraId: "abc", channel: 1, consoleHost: "10.0.0.1"))
    let hallway = Camera(id: "manual-1", name: "Hallway", url: "rtsp://10.0.0.3/stream")
    let garden = Camera(id: "manual-2", name: "Garden", url: "rtsp://10.0.0.4/stream", enabled: false)

    @Test func noFileIsNoCameras() {
        let repository = CameraRepository(fileURL: fileURL)
        #expect(repository.cameras.isEmpty)
        #expect(repository.enabledCameras.isEmpty)
    }

    /// Android's `CameraRepository.decode` reads a list that does not decode
    /// as no cameras.
    @Test func aCorruptFileIsNoCameras() throws {
        try FileManager.default.createDirectory(
            at: fileURL.deletingLastPathComponent(), withIntermediateDirectories: true)
        try Data("{not json".utf8).write(to: fileURL)
        let repository = CameraRepository(fileURL: fileURL)
        #expect(repository.cameras.isEmpty)
    }

    @Test func aCorruptFileIsReplacedByTheNextWrite() async throws {
        try FileManager.default.createDirectory(
            at: fileURL.deletingLastPathComponent(), withIntermediateDirectories: true)
        try Data("[{\"id\": 3}]".utf8).write(to: fileURL)
        let repository = CameraRepository(fileURL: fileURL)
        try await repository.upsert(nursery)
        #expect(CameraRepository(fileURL: fileURL).cameras == [nursery])
    }

    /// Unlike a corrupt file, one that cannot be read (before the first unlock,
    /// say; a directory in its place here) is never taken for an empty list
    /// and written over.
    @Test func anUnreadableFileIsNotOverwritten() async throws {
        try FileManager.default.createDirectory(at: fileURL, withIntermediateDirectories: true)
        let repository = CameraRepository(fileURL: fileURL)
        #expect(repository.cameras.isEmpty)
        await #expect(throws: CameraRepository.StorageError.self) { try await repository.upsert(nursery) }
        #expect(repository.cameras.isEmpty)
        var isDirectory: ObjCBool = false
        #expect(FileManager.default.fileExists(atPath: fileURL.path, isDirectory: &isDirectory))
        #expect(isDirectory.boolValue)
    }

    @Test func camerasSurviveARelaunch() async throws {
        let repository = CameraRepository(fileURL: fileURL)
        try await repository.upsert(nursery)
        try await repository.upsert(hallway)
        try await repository.upsert(garden)
        let relaunched = CameraRepository(fileURL: fileURL)
        #expect(relaunched.cameras == [nursery, hallway, garden])
        #expect(relaunched.cameras[0].protect?.consoleHost == "10.0.0.1")
        #expect(relaunched.cameras[2].enabled == false)
    }

    @Test func upsertAppendsANewCamera() async throws {
        let repository = CameraRepository(fileURL: fileURL)
        try await repository.upsert(nursery)
        try await repository.upsert(hallway)
        #expect(repository.cameras.map(\.id) == [nursery.id, hallway.id])
    }

    @Test func upsertReplacesACameraWhereItStands() async throws {
        let repository = CameraRepository(fileURL: fileURL)
        try await repository.upsert(nursery)
        try await repository.upsert(hallway)
        try await repository.upsert(garden)
        var renamed = hallway
        renamed.name = "Landing"
        renamed.url = "rtsp://10.0.0.3/other"
        try await repository.upsert(renamed)
        #expect(repository.cameras == [nursery, renamed, garden])
        #expect(CameraRepository(fileURL: fileURL).cameras == [nursery, renamed, garden])
    }

    @Test func removeDropsOnlyThatCamera() async throws {
        let repository = CameraRepository(fileURL: fileURL)
        for camera in [nursery, hallway, garden] { try await repository.upsert(camera) }
        try await repository.remove(id: hallway.id)
        #expect(repository.cameras == [nursery, garden])
        try await repository.remove(id: "no-such-camera")
        #expect(CameraRepository(fileURL: fileURL).cameras == [nursery, garden])
    }

    @Test func setEnabledChangesOnlyThatCamera() async throws {
        let repository = CameraRepository(fileURL: fileURL)
        for camera in [nursery, hallway, garden] { try await repository.upsert(camera) }
        try await repository.setEnabled(id: nursery.id, false)
        try await repository.setEnabled(id: garden.id, true)
        #expect(repository.cameras.map(\.enabled) == [false, true, true])
        #expect(repository.cameras.map(\.id) == [nursery.id, hallway.id, garden.id])
        #expect(CameraRepository(fileURL: fileURL).cameras.map(\.enabled) == [false, true, true])
    }

    @Test func enabledCamerasAreTheEnabledSubsetInListOrder() async throws {
        let repository = CameraRepository(fileURL: fileURL)
        let fourth = Camera(id: "manual-3", name: "Attic", url: "rtsp://10.0.0.5/s")
        for camera in [nursery, garden, hallway, fourth] { try await repository.upsert(camera) }
        #expect(repository.enabledCameras.map(\.id) == [nursery.id, hallway.id, fourth.id])
        try await repository.setEnabled(id: garden.id, true)
        #expect(repository.enabledCameras.map(\.id) == [nursery.id, garden.id, hallway.id, fourth.id])
    }

    @Test func cameraUpdatesStartWithTheCurrentListAndFollowChanges() async throws {
        let repository = CameraRepository(fileURL: fileURL)
        try await repository.upsert(nursery)
        var updates = repository.cameraUpdates().makeAsyncIterator()
        #expect(await updates.next() == [nursery])
        try await repository.upsert(hallway)
        #expect(await updates.next() == [nursery, hallway])
        try await repository.remove(id: nursery.id)
        #expect(await updates.next() == [hallway])
    }

    @Test func enabledCameraUpdatesFollowTheSubset() async throws {
        let repository = CameraRepository(fileURL: fileURL)
        try await repository.upsert(nursery)
        var updates = repository.enabledCameraUpdates().makeAsyncIterator()
        #expect(await updates.next() == [nursery])
        // A disabled camera leaves the subset as it was: nothing is sent.
        try await repository.upsert(garden)
        try await repository.setEnabled(id: nursery.id, false)
        #expect(await updates.next() == [])
        try await repository.setEnabled(id: garden.id, true)
        var enabledGarden = garden
        enabledGarden.enabled = true
        #expect(await updates.next() == [enabledGarden])
    }

    @Test func theFileIsWrittenReadableWhileLockedAfterFirstUnlock() async throws {
        #expect(CameraRepository.writeOptions.contains(.completeFileProtectionUntilFirstUserAuthentication))
        #expect(CameraRepository.writeOptions.contains(.atomic))
        let repository = CameraRepository(fileURL: fileURL)
        try await repository.upsert(nursery)
        let attributes = try FileManager.default.attributesOfItem(atPath: fileURL.path)
        // The simulator has no Data Protection and records no class; a device
        // must report the one written.
        #if !targetEnvironment(simulator)
            #expect(
                attributes[.protectionKey] as? FileProtectionType == .completeUntilFirstUserAuthentication)
        #else
            _ = attributes
        #endif
    }

    @Test func theFileIsExcludedFromBackupAfterEveryWrite() async throws {
        let repository = CameraRepository(fileURL: fileURL)
        try await repository.upsert(nursery)
        #expect(try excludedFromBackup())
        // Atomic writes replace the file; the flag has to follow.
        try await repository.upsert(hallway)
        #expect(try excludedFromBackup())
    }

    @Test func theFileIsJSONInTheSharedCameraShape() async throws {
        let repository = CameraRepository(fileURL: fileURL)
        try await repository.upsert(nursery)
        let json = try #require(
            try JSONSerialization.jsonObject(with: Data(contentsOf: fileURL)) as? [[String: Any]])
        #expect(json.first?["id"] as? String == "protect-abc-1")
        #expect((json.first?["protect"] as? [String: Any])?["cameraId"] as? String == "abc")
    }

    @Test func theDefaultFileLivesInApplicationSupport() {
        #expect(CameraRepository.defaultFileURL.lastPathComponent == "cameras.json")
        #expect(
            CameraRepository.defaultFileURL.deletingLastPathComponent().standardizedFileURL
                == URL.applicationSupportDirectory.standardizedFileURL)
    }

    private func excludedFromBackup() throws -> Bool {
        var url = fileURL
        url.removeAllCachedResourceValues()
        return try url.resourceValues(forKeys: [.isExcludedFromBackupKey]).isExcludedFromBackup == true
    }
}

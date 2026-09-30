import AVFAudio
import Testing

@testable import Dozecam

struct MicrophonePermissionTests {
    @Test(arguments: [
        (AVAudioApplication.recordPermission.granted, MicrophonePermission.Status.granted),
        (.denied, .denied),
        (.undetermined, .undetermined),
    ])
    func statusFollowsTheSystemRecordPermission(
        permission: AVAudioApplication.recordPermission, status: MicrophonePermission.Status
    ) {
        #expect(MicrophonePermission.Status(permission) == status)
    }

    @Test func isGrantedAgreesWithStatus() {
        #expect(MicrophonePermission.isGranted == (MicrophonePermission.status == .granted))
    }
}

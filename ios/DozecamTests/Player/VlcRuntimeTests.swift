import Foundation
import Testing

@testable import Dozecam

struct VlcRuntimeTests {
    @Test func usesAndroidsLowLatencyFlags() {
        #expect(
            VlcRuntime.options == ["--network-caching=150", "--rtsp-tcp", "--drop-late-frames", "--skip-frames"]
        )
    }

    @Test func questionDialogsTakeTheAffirmativeChainLikeAndroid() {
        // "View certificate" / "Accept permanently": the second button.
        #expect(VlcDialogAnswerer.answer(action2: "Accept permanently") == 2)
        // A single-action question: its only button.
        #expect(VlcDialogAnswerer.answer(action2: nil) == 1)
        #expect(VlcDialogAnswerer.answer(action2: "") == 1)
    }

    @Test func readsTheCodecOutOfLibVlcsNoDecoderDialog() {
        #expect(
            UndecodableCodecs.codec(
                inDialogTitle: "Codec not supported",
                message: #"VLC could not decode the format "av01" (AOMedia's AV1 Video)"#
            ) == "av01"
        )
        #expect(
            UndecodableCodecs.codec(
                inDialogTitle: "Unidentified codec", message: "VLC could not identify the audio or video codec"
            ) == UndecodableCodecs.unidentified
        )
        #expect(UndecodableCodecs.codec(inDialogTitle: "Your input can't be opened", message: "…") == nil)
        #expect(UndecodableCodecs.codec(inDialogTitle: "Codec not supported", message: "no quotes") == nil)
    }

    @Test func remembersUndecodableCodecsForEveryPlayer() {
        let codecs = UndecodableCodecs()
        #expect(!codecs.contains("hevc"))
        codecs.insert("hevc")
        #expect(codecs.contains("hevc"))
    }

    @Test func printsFourCCsInMemoryOrderAsLibVlcDoes() {
        // 'av01' as VLC_FOURCC builds it: little-endian bytes a, v, 0, 1.
        #expect(VlcPlayerCore.fourcc(0x3130_7661) == "av01")
        #expect(VlcPlayerCore.fourcc(0x3436_3268) == "h264")
    }

    @Test func namesTheCodecsCamerasSend() {
        #expect(VlcPlayerCore.codecName(fourcc: "av01") == "AV1")
        #expect(VlcPlayerCore.codecName(fourcc: "hevc") == "HEVC")
        #expect(VlcPlayerCore.codecName(fourcc: "h264") == "H.264")
        #expect(VlcPlayerCore.codecName(fourcc: "zzzz") == nil)
    }
}

@MainActor
struct LivePlayersTests {
    @Test func buildsVlcForRtspAndTheLivestreamPipelineForProtect() {
        let players = LivePlayers(dependencies: .isolated())
        let rtsp = players.make(for: .rtsp(url: "rtsp://127.0.0.1:18554/nursery"))
        let livestream = players.make(for: .livestream(cameraId: "cam-1", channel: 0))
        defer {
            rtsp.release()
            livestream.release()
        }
        #expect(rtsp is VlcVideoPlayerController)
        #expect(livestream is LivestreamVideoPlayerController)
    }

    @Test func aLivestreamWithNoConsoleSignedInIsAnError() async {
        let players = LivePlayers(dependencies: .isolated())
        let player = players.make(for: .livestream(cameraId: "cam-1", channel: 0))
        defer { player.release() }
        let log = PlayerEventLog(player)
        let window = PlayerWindow(player)
        defer { window.close() }

        player.play(.livestream(cameraId: "cam-1", channel: 0))

        #expect(await log.wait(for: .seconds(5)) { $0.events.contains(.error) })
    }
}

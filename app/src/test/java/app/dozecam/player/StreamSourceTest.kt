package app.dozecam.player

import app.dozecam.data.Camera
import app.dozecam.data.ProtectStream
import org.junit.Assert.assertEquals
import org.junit.Test

class StreamSourceTest {

    @Test
    fun `a Protect camera plays over the livestream`() {
        val camera = Camera(
            id = "protect-cam1-1",
            name = "Nursery",
            url = "rtsp://console:7447/alias",
            protect = ProtectStream(cameraId = "cam1", channel = 1),
        )

        assertEquals(StreamSource.Livestream("cam1", 1), StreamSource.of(camera))
    }

    @Test
    fun `a manually added camera stays on RTSP`() {
        val camera = Camera(id = "uuid", name = "Garage", url = "rtsp://10.0.0.9:554/stream")

        assertEquals(StreamSource.Rtsp("rtsp://10.0.0.9:554/stream"), StreamSource.of(camera))
    }

    @Test
    fun `a camera onboarded before the field existed recovers its identity from its id`() {
        // Every camera already installed deserializes with protect == null; an
        // upgrade has to fix those without making the user re-run onboarding.
        val camera = Camera(
            id = "protect-61b3f5c902f8e103e7000424-1",
            name = "Nursery",
            url = "rtsp://c:7447/a",
        )

        assertEquals(
            StreamSource.Livestream("61b3f5c902f8e103e7000424", 1),
            StreamSource.of(camera),
        )
    }

    @Test
    fun `a manual camera whose name resembles the protect id shape stays on RTSP`() {
        // UUIDs carry dashes, so they cannot collide with the onboarding shape.
        val camera = Camera(
            id = "3f2504e0-4f89-11d3-9a0c-0305e82c3301",
            name = "Garage",
            url = "rtsp://10.0.0.9:554/s",
        )

        assertEquals(StreamSource.Rtsp("rtsp://10.0.0.9:554/s"), StreamSource.of(camera))
    }

    @Test
    fun `a stored identity wins over the one encoded in the id`() {
        val camera = Camera(
            id = "protect-stale-9",
            name = "Nursery",
            url = "rtsp://c:7447/a",
            protect = ProtectStream(cameraId = "current", channel = 2),
        )

        assertEquals(StreamSource.Livestream("current", 2), StreamSource.of(camera))
    }

    @Test
    fun `a camera from another console falls back to its own RTSP url`() {
        // Onboarding a second console overwrites the single credentials slot
        // but leaves the first console's cameras in place. Negotiating their
        // ids against the new console cannot work; their RTSP URL still can.
        val camera = Camera(
            id = "protect-cam1-1",
            name = "Nursery",
            url = "rtsp://old-console:7447/alias",
            protect = ProtectStream("cam1", 1, consoleHost = "old-console"),
        )

        assertEquals(
            StreamSource.Rtsp("rtsp://old-console:7447/alias"),
            StreamSource.of(camera, consoleHost = "new-console"),
        )
    }

    @Test
    fun `a camera from the signed-in console uses the livestream`() {
        val camera = Camera(
            id = "protect-cam1-1",
            name = "Nursery",
            url = "rtsp://console:7447/alias",
            protect = ProtectStream("cam1", 1, consoleHost = "console"),
        )

        assertEquals(
            StreamSource.Livestream("cam1", 1),
            StreamSource.of(camera, consoleHost = "console"),
        )
    }

    @Test
    fun `a mismatch does not fall through to the id-derived identity`() {
        // The id encodes the same camera, so a fall-through would negotiate
        // exactly the wrong camera the ownership check just rejected.
        val camera = Camera(
            id = "protect-cam1-1",
            name = "Nursery",
            url = "rtsp://old:7447/alias",
            protect = ProtectStream("cam1", 1, consoleHost = "old"),
        )

        assertEquals(
            StreamSource.Rtsp("rtsp://old:7447/alias"),
            StreamSource.of(camera, consoleHost = "new"),
        )
    }

    @Test
    fun `a camera stored before ownership was recorded is still played`() {
        val camera = Camera(
            id = "protect-cam1-1",
            name = "Nursery",
            url = "rtsp://console:7447/alias",
            protect = ProtectStream("cam1", 1, consoleHost = null),
        )

        assertEquals(
            StreamSource.Livestream("cam1", 1),
            StreamSource.of(camera, consoleHost = "console"),
        )
    }
}

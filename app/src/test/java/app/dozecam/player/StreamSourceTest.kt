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
}

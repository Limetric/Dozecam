package app.dozecam.monitoring

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.dozecam.audio.SoundDetector
import app.dozecam.player.ConnectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MonitoringStatusTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun live(id: String, name: String = id, level: Float = 0f) = CameraMonitorState(
        cameraId = id,
        name = name,
        level = level,
        connection = ConnectionState.Live,
    )

    private fun of(states: List<CameraMonitorState>, enabledCount: Int = states.size) =
        MonitoringStatus.of(
            context,
            anyMonitors = true,
            states = states,
            enabledCount = enabledCount,
        )

    @Test
    fun `the listening line carries the loudest camera's level`() {
        val status = of(listOf(live("a", level = 0.1f), live("b", level = 0.4f)))

        assertEquals("Listening to 2 cameras", status.text)
        assertEquals(0.4f, status.level)
    }

    /**
     * The level is proof of health, so it must never ride a line that is not
     * the healthy one — "offline" wearing a live meter would be reassurance
     * in the one direction that matters.
     */
    @Test
    fun `no unhealthy line carries a level`() {
        val loud = live("a", level = 0.4f)

        listOf(
            of(listOf(loud, live("b").copy(connection = ConnectionState.Offline))),
            of(listOf(loud, live("b").copy(connection = ConnectionState.Reconnecting(1)))),
            of(listOf(loud, live("b").copy(connection = ConnectionState.Connecting))),
            of(
                listOf(
                    loud,
                    live("b", name = "Nursery").copy(phase = SoundDetector.Phase.TRIGGERED),
                ),
            ),
            of(emptyList()),
            MonitoringStatus.of(
                context,
                anyMonitors = false,
                states = emptyList(),
                enabledCount = 0,
            ),
        ).forEach { status -> assertNull(status.text, status.level) }
    }

    /**
     * A live camera that has not decoded a buffer yet has no level, and it
     * must neither crash the line nor drag the loudest reading down.
     */
    @Test
    fun `an unmeasured camera contributes no level`() {
        val mixed = of(listOf(live("a").copy(level = null), live("b", level = 0.3f)))
        assertEquals(0.3f, mixed.level)

        val unmeasured = of(listOf(live("a").copy(level = null)))
        assertEquals("Listening to 1 camera", unmeasured.text)
        assertNull(unmeasured.level)
    }

    @Test
    fun `a triggered camera outranks everything`() {
        val status = of(
            listOf(
                live("a").copy(connection = ConnectionState.Offline),
                live("b", name = "Nursery").copy(phase = SoundDetector.Phase.TRIGGERED),
            ),
        )

        assertEquals("Sound detected — Nursery", status.text)
    }

    /** An enabled-but-unmonitorable camera must not be silently claimed as covered. */
    @Test
    fun `partial coverage says so and still proves the rest is live`() {
        val status = of(listOf(live("a", level = 0.2f)), enabledCount = 2)

        assertEquals("Listening to 1 camera · 1 not monitorable", status.text)
        assertEquals(0.2f, status.level)
    }

    @Test
    fun `a room coming out of the speaker is disclosed in front of everything else`() {
        val status = MonitoringStatus.of(
            context,
            anyMonitors = true,
            states = listOf(live("a", "Nursery", level = 0.2f), live("b", "Hall")),
            enabledCount = 2,
            aloudCameraIds = setOf("a"),
        )

        // A phone quietly broadcasting a bedroom is exactly what a persistent
        // notification exists to admit to.
        assertEquals("Nursery aloud · Listening to 2 cameras", status.text)
        assertEquals(0.2f, status.level)
    }

    @Test
    fun `several rooms coming out of the speaker are counted rather than listed`() {
        val status = MonitoringStatus.of(
            context,
            anyMonitors = true,
            states = listOf(live("a", "Nursery"), live("b", "Hall"), live("c", "Play room")),
            enabledCount = 3,
            aloudCameraIds = setOf("a", "b", "c"),
        )

        // One line, and a list of bedrooms cut off mid-word would say less
        // than a number.
        assertEquals("3 rooms aloud · Listening to 3 cameras", status.text)
    }

    @Test
    fun `the disclosure does not push aside what is wrong`() {
        val status = MonitoringStatus.of(
            context,
            anyMonitors = true,
            states = listOf(
                live("a", "Nursery"),
                CameraMonitorState("b", "Hall", connection = ConnectionState.Offline),
            ),
            enabledCount = 2,
            aloudCameraIds = setOf("a"),
        )

        // Both facts are true at once, and an offline camera does not stop
        // being the more urgent half of the line.
        assertEquals("Nursery aloud · Offline — waiting for network", status.text)
    }

    @Test
    fun `nothing is claimed for a camera that is not actually being played`() {
        val status = MonitoringStatus.of(
            context,
            anyMonitors = true,
            states = listOf(live("a", "Nursery", level = 0.2f)),
            enabledCount = 1,
            // The switch may still be on — the speaker was lost to a call, or
            // the camera is one the monitor has stopped listening to.
            aloudCameraIds = setOf("gone"),
        )

        assertEquals("Listening to 1 camera", status.text)
    }

    @Test
    fun `with no monitors there is nothing to overstate`() {
        val status = MonitoringStatus.of(
            context,
            anyMonitors = false,
            states = emptyList(),
            enabledCount = 0,
        )

        assertEquals("No camera is switched on", status.text)
    }
}

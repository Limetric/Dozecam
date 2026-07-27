package app.dozecam.monitoring

import app.dozecam.data.Camera
import app.dozecam.data.ProtectStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MonitorPlanTest {

    private fun camera(id: String, url: String = "rtsp://cam:7447/$id", name: String = id) =
        Camera(id = id, name = name, url = url)

    private fun running(vararg cameras: Camera) = cameras.associateBy { it.id }

    @Test
    fun `starts every camera when nothing is running yet`() {
        val plan = MonitorPlan.of(running(), listOf(camera("a"), camera("b")))

        assertEquals(listOf("a", "b"), plan.start.map { it.id })
        assertTrue(plan.stop.isEmpty())
    }

    @Test
    fun `stops a camera that was switched off`() {
        val plan = MonitorPlan.of(running(camera("a"), camera("b")), listOf(camera("a")))

        assertEquals(setOf("b"), plan.stop)
        assertTrue(plan.start.isEmpty())
    }

    @Test
    fun `leaves an unchanged camera alone`() {
        val plan = MonitorPlan.of(running(camera("a")), listOf(camera("a")))

        assertTrue(plan.isEmpty)
    }

    @Test
    fun `a rename does not disturb the running monitor`() {
        val plan = MonitorPlan.of(
            running(camera("a", name = "Nursery")),
            listOf(camera("a", name = "Baby room")),
        )

        // Restarting here would re-arm a detector that may be mid-refractory.
        assertTrue(plan.isEmpty)
    }

    @Test
    fun `a url change under the same id restarts just that camera`() {
        val plan = MonitorPlan.of(
            running(camera("a"), camera("b")),
            listOf(camera("a", url = "rtsp://cam:7447/a-new"), camera("b")),
        )

        assertEquals(setOf("a"), plan.stop)
        assertEquals(listOf("a"), plan.start.map { it.id })
        assertEquals("rtsp://cam:7447/a-new", plan.start.single().url)
    }

    @Test
    fun `everything switched off stops everything and starts nothing`() {
        val plan = MonitorPlan.of(running(camera("a"), camera("b")), emptyList())

        assertEquals(setOf("a", "b"), plan.stop)
        assertTrue(plan.start.isEmpty())
    }

    @Test
    fun `a protect camera is matched by id and url like any other`() {
        val protect = camera("a").copy(protect = ProtectStream("cam-a", 1, "console"))
        val plan = MonitorPlan.of(running(protect), listOf(protect))

        assertTrue(plan.isEmpty)
    }
}

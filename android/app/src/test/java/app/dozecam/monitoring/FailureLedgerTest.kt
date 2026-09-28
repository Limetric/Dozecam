package app.dozecam.monitoring

import app.dozecam.player.ConnectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two rules that keep the failure alarm from crying wolf: nothing counts
 * until it has lasted the grace period, and what does count is announced
 * exactly once.
 */
class FailureLedgerTest {

    private var nowMs = 100_000L
    private var wallMs = 1_700_000_000_000L
    private val ledger = FailureLedger(monotonicClock = { nowMs }, wallClock = { wallMs })

    private val grace = 60_000L

    private fun advance(ms: Long) {
        nowMs += ms
        wallMs += ms
    }

    private fun camera(id: String, connection: ConnectionState, name: String = id) =
        CameraMonitorState(cameraId = id, name = name, level = 0f, connection = connection)

    private fun health(
        vararg cameras: CameraMonitorState,
        online: Boolean = true,
        battery: BatteryStatus? = BatteryStatus(percent = 80, plugged = true),
        notifications: Boolean = true,
        screenWake: Boolean = true,
    ) = MonitoringHealth(
        cameras = cameras.toList(),
        networkOnline = online,
        battery = battery,
        notificationsAllowed = notifications,
        screenWakeAllowed = screenWake,
    )

    private fun evaluate(health: MonitoringHealth) = ledger.evaluate(health, grace)

    @Test
    fun `a healthy monitor has nothing to say`() {
        val update = evaluate(health(camera("a", ConnectionState.Live)))

        assertTrue(update.active.isEmpty())
        assertTrue(update.announce.isEmpty())
        assertTrue(update.recovered.isEmpty())
    }

    @Test
    fun `a camera crossing the grace period is announced exactly once`() {
        evaluate(health(camera("a", ConnectionState.Reconnecting(1), name = "Nursery")))
        val since = wallMs

        advance(grace - 1)
        assertTrue(evaluate(health(camera("a", ConnectionState.Reconnecting(3)))).active.isEmpty())

        advance(1)
        val crossed = evaluate(health(camera("a", ConnectionState.Reconnecting(4), name = "Nursery")))
        assertEquals(1, crossed.announce.size)
        assertEquals(
            MonitoringFailure(FailureReason.CameraUnreachable("a", "Nursery", networkDown = false), since),
            crossed.announce.single(),
        )
        assertEquals(crossed.announce, crossed.active)

        // Still failing an hour later: still active, never announced again.
        repeat(3) {
            advance(20 * 60_000L)
            val later = evaluate(health(camera("a", ConnectionState.Offline, name = "Nursery")))
            assertEquals(1, later.active.size)
            assertTrue(later.announce.isEmpty())
        }
    }

    @Test
    fun `a flap inside the grace period fires nothing and leaves no trace`() {
        evaluate(health(camera("a", ConnectionState.Reconnecting(1))))
        advance(grace / 2)
        evaluate(health(camera("a", ConnectionState.Reconnecting(2))))

        advance(1_000)
        val back = evaluate(health(camera("a", ConnectionState.Live)))

        assertTrue(back.active.isEmpty())
        assertTrue(back.announce.isEmpty())
        assertTrue(back.recovered.isEmpty())

        // And the next drop starts its own clock rather than inheriting the
        // last one's: a second flap is still a flap.
        advance(1_000)
        evaluate(health(camera("a", ConnectionState.Reconnecting(1))))
        advance(grace - 1)
        assertTrue(evaluate(health(camera("a", ConnectionState.Reconnecting(2)))).announce.isEmpty())
    }

    @Test
    fun `recovery clears the failure and leaves a note`() {
        evaluate(health(camera("a", ConnectionState.Offline, name = "Nursery")))
        val since = wallMs
        advance(grace)
        evaluate(health(camera("a", ConnectionState.Offline, name = "Nursery")))

        advance(5 * 60_000L)
        val back = evaluate(health(camera("a", ConnectionState.Live, name = "Nursery")))

        assertTrue(back.active.isEmpty())
        assertEquals(
            RecoveredFailure(
                FailureReason.CameraUnreachable("a", "Nursery", networkDown = false),
                sinceMs = since,
                clearedAtMs = wallMs,
            ),
            back.recovered.single(),
        )

        // A drop after recovery is a new failure, and is announced afresh.
        advance(1_000)
        evaluate(health(camera("a", ConnectionState.Offline, name = "Nursery")))
        advance(grace)
        assertEquals(1, evaluate(health(camera("a", ConnectionState.Offline, name = "Nursery"))).announce.size)
    }

    /**
     * Every camera goes with the network. One alarm, naming them all, rather
     * than one per room — and the reason is the network, not the cameras.
     */
    @Test
    fun `cameras lost together are announced together with the network as the reason`() {
        val down = health(
            camera("a", ConnectionState.Offline, name = "Nursery"),
            camera("b", ConnectionState.Offline, name = "Hall"),
            online = false,
        )
        evaluate(down)
        advance(grace)

        val crossed = evaluate(down)

        assertEquals(2, crossed.announce.size)
        assertTrue(crossed.announce.all { (it.reason as FailureReason.CameraUnreachable).networkDown })
    }

    @Test
    fun `the failure's start does not move as the reason is refreshed`() {
        evaluate(health(camera("a", ConnectionState.Reconnecting(1), name = "Nursery")))
        val since = wallMs
        advance(grace)

        // Renamed and now offline: the same failure, under its current name.
        val update = evaluate(health(camera("a", ConnectionState.Offline, name = "Baby's room")))

        val failure = update.announce.single()
        assertEquals(since, failure.sinceMs)
        assertEquals("Baby's room", (failure.reason as FailureReason.CameraUnreachable).name)
    }

    @Test
    fun `a low battery on no charger is a failure with hysteresis`() {
        val plugged = health(battery = BatteryStatus(percent = 24, plugged = true))
        assertTrue(evaluate(plugged).active.isEmpty())

        evaluate(health(battery = BatteryStatus(percent = 25, plugged = false)))
        advance(grace)
        val low = evaluate(health(battery = BatteryStatus(percent = 25, plugged = false)))
        assertEquals(FailureReason.LowBattery(25), low.announce.single().reason)

        // Hovering just over the line does not clear it.
        val hovering = evaluate(health(battery = BatteryStatus(percent = 27, plugged = false)))
        assertEquals(FailureReason.LowBattery(27), hovering.active.single().reason)
        assertTrue(hovering.recovered.isEmpty())

        // A charger does.
        val charging = evaluate(health(battery = BatteryStatus(percent = 27, plugged = true)))
        assertTrue(charging.active.isEmpty())
        assertEquals(1, charging.recovered.size)
    }

    @Test
    fun `unplugging while armed is reported once, on the transition`() {
        assertFalse(evaluate(health(battery = BatteryStatus(80, plugged = true))).unplugged)

        assertTrue(evaluate(health(battery = BatteryStatus(80, plugged = false))).unplugged)
        assertFalse(evaluate(health(battery = BatteryStatus(79, plugged = false))).unplugged)

        // Starting unplugged is not being unplugged.
        val fresh = FailureLedger({ nowMs }, { wallMs })
        assertFalse(fresh.evaluate(health(battery = BatteryStatus(80, plugged = false)), grace).unplugged)
    }

    @Test
    fun `withdrawn grants are failures after the same grace`() {
        evaluate(health(notifications = false, screenWake = false))
        assertTrue(evaluate(health(notifications = false, screenWake = false)).active.isEmpty())

        advance(grace)
        val update = evaluate(health(notifications = false, screenWake = false))

        assertEquals(
            listOf(FailureReason.NotificationsBlocked, FailureReason.ScreenWakeBlocked),
            update.announce.map { it.reason },
        )
    }

    @Test
    fun `an unknown battery is not a failure`() {
        assertNull(evaluate(health(battery = null)).active.firstOrNull())
    }
}

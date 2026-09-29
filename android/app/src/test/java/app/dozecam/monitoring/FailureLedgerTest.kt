package app.dozecam.monitoring

import app.dozecam.player.ConnectionState
import app.dozecam.testing.Fixtures
import kotlinx.serialization.Serializable
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The two rules that keep the failure alarm from crying wolf: nothing counts
 * until it has lasted the grace period, and what does count is announced
 * exactly once.
 *
 * The timelines live in `shared/fixtures/failure-ledger/timelines.json`; this
 * class supplies the clocks and turns each step into a [MonitoringHealth].
 */
class FailureLedgerTest {

    @Serializable
    private data class Camera(
        val id: String,
        val name: String,
        val connection: String,
        val reconnectAttempt: Int? = null,
    )

    @Serializable
    private data class Battery(val percent: Int, val plugged: Boolean)

    @Serializable
    private data class Health(
        val cameras: List<Camera>,
        val networkOnline: Boolean,
        val battery: Battery?,
        val notificationsAllowed: Boolean,
        val screenWakeAllowed: Boolean,
    )

    /** A failure as the fixture spells it; times are relative to the case's start. */
    @Serializable
    private data class Failure(
        val reason: String,
        val cameraId: String? = null,
        val name: String? = null,
        val networkDown: Boolean? = null,
        val percent: Int? = null,
        val sinceMs: Long,
        val clearedAtMs: Long? = null,
    )

    @Serializable
    private data class Expect(
        val active: List<Failure>? = null,
        val announce: List<Failure>? = null,
        val recovered: List<Failure>? = null,
        val unplugged: Boolean? = null,
    )

    @Serializable
    private data class Step(val atMs: Long, val health: Health, val expect: Expect? = null)

    @Serializable
    private data class Case(val name: String, val steps: List<Step>)

    @Serializable
    private data class Fixture(val graceMs: Long, val cases: List<Case>)

    private val fixture = Fixtures.decode<Fixture>("failure-ledger/timelines.json")

    // Neither clock starts at zero, so a ledger that confused the two, or
    // measured from zero, would show.
    private val monotonicStartMs = 100_000L
    private val wallStartMs = 1_700_000_000_000L

    private fun connection(camera: Camera): ConnectionState = when (camera.connection) {
        "connecting" -> ConnectionState.Connecting
        "live" -> ConnectionState.Live
        "reconnecting" -> ConnectionState.Reconnecting(
            checkNotNull(camera.reconnectAttempt) { "reconnecting needs reconnectAttempt" },
        )
        "offline" -> ConnectionState.Offline
        else -> error("unknown connection \"${camera.connection}\"")
    }

    private fun Health.toMonitoringHealth() = MonitoringHealth(
        cameras = cameras.map {
            CameraMonitorState(cameraId = it.id, name = it.name, level = 0f, connection = connection(it))
        },
        networkOnline = networkOnline,
        battery = battery?.let { BatteryStatus(percent = it.percent, plugged = it.plugged) },
        notificationsAllowed = notificationsAllowed,
        screenWakeAllowed = screenWakeAllowed,
    )

    private fun describe(reason: FailureReason, sinceMs: Long, clearedAtMs: Long? = null): Failure {
        val since = sinceMs - wallStartMs
        val cleared = clearedAtMs?.minus(wallStartMs)
        return when (reason) {
            is FailureReason.CameraUnreachable -> Failure(
                "cameraUnreachable", reason.cameraId, reason.name, reason.networkDown,
                sinceMs = since, clearedAtMs = cleared,
            )
            is FailureReason.LowBattery ->
                Failure("lowBattery", percent = reason.percent, sinceMs = since, clearedAtMs = cleared)
            FailureReason.NotificationsBlocked ->
                Failure("notificationsBlocked", sinceMs = since, clearedAtMs = cleared)
            FailureReason.ScreenWakeBlocked -> Failure("screenWakeBlocked", sinceMs = since, clearedAtMs = cleared)
        }
    }

    private fun play(name: String) {
        val case = fixture.cases.singleOrNull { it.name == name } ?: error("no fixture case \"$name\"")
        var atMs = 0L
        val ledger = FailureLedger(
            monotonicClock = { monotonicStartMs + atMs },
            wallClock = { wallStartMs + atMs },
        )
        case.steps.forEachIndexed { index, step ->
            check(step.atMs >= atMs) { "${case.name}: step $index goes back in time" }
            atMs = step.atMs
            val update = ledger.evaluate(step.health.toMonitoringHealth(), fixture.graceMs)
            val expect = step.expect ?: return@forEachIndexed
            val at = "${case.name}: step $index at ${step.atMs}ms"
            val active = update.active.map { describe(it.reason, it.sinceMs) }
            val announce = update.announce.map { describe(it.reason, it.sinceMs) }
            val recovered = update.recovered.map { describe(it.reason, it.sinceMs, it.clearedAtMs) }
            expect.active?.let { assertEquals("$at active", it, active) }
            expect.announce?.let { assertEquals("$at announce", it, announce) }
            expect.recovered?.let { assertEquals("$at recovered", it, recovered) }
            expect.unplugged?.let { assertEquals("$at unplugged", it, update.unplugged) }
        }
    }

    @Test
    fun `a healthy monitor has nothing to say`() = play("a healthy monitor has nothing to say")

    @Test
    fun `a camera crossing the grace period is announced exactly once`() =
        play("a camera crossing the grace period is announced exactly once")

    // The second drop starts its own clock rather than inheriting the last
    // one's: a second flap is still a flap.
    @Test
    fun `a flap inside the grace period fires nothing and leaves no trace`() =
        play("a flap inside the grace period fires nothing and leaves no trace")

    // A drop after recovery is a new failure, and is announced afresh.
    @Test
    fun `recovery clears the failure and leaves a note`() = play("recovery clears the failure and leaves a note")

    /**
     * Every camera goes with the network. One alarm, naming them all, rather
     * than one per room — and the reason is the network, not the cameras.
     */
    @Test
    fun `cameras lost together are announced together with the network as the reason`() =
        play("cameras lost together are announced together with the network as the reason")

    // Renamed and now offline: the same failure, under its current name.
    @Test
    fun `the failure's start does not move as the reason is refreshed`() =
        play("the failure's start does not move as the reason is refreshed")

    // Hovering just over the line does not clear it; a charger does.
    @Test
    fun `a low battery on no charger is a failure with hysteresis`() =
        play("a low battery on no charger is a failure with hysteresis")

    // Starting unplugged is not being unplugged: a fresh ledger's first reading.
    @Test
    fun `unplugging while armed is reported once, on the transition`() {
        play("unplugging while armed is reported once, on the transition")
        play("starting unplugged is not being unplugged")
    }

    @Test
    fun `withdrawn grants are failures after the same grace`() =
        play("withdrawn grants are failures after the same grace")

    @Test
    fun `an unknown battery is not a failure`() = play("an unknown battery is not a failure")
}

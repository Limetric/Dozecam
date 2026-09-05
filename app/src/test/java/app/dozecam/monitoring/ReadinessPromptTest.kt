package app.dozecam.monitoring

import org.junit.Assert.assertEquals
import org.junit.Test

class ReadinessPromptTest {

    private val alertsOff = ReadinessFacts(alertsEnabled = false)
    private val alertsOffAndOptimised =
        ReadinessFacts(alertsEnabled = false, batteryOptimised = true)

    @Test
    fun `a fresh failure is worth interrupting for`() {
        val findings = Readiness.of(alertsOff)

        assertEquals(
            listOf(ReadinessCheck.ALERTS_ON),
            ReadinessPrompt.unannounced(findings, emptySet()).map { it.check },
        )
    }

    @Test
    fun `a failure already shown is not shown again`() {
        val findings = Readiness.of(alertsOff)
        val acknowledged = ReadinessPrompt.acknowledging(findings, emptySet())

        assertEquals(emptyList<ReadinessFinding>(), ReadinessPrompt.unannounced(findings, acknowledged))
    }

    @Test
    fun `a healthy phone interrupts nobody`() {
        assertEquals(
            emptyList<ReadinessFinding>(),
            ReadinessPrompt.unannounced(Readiness.of(ReadinessFacts()), emptySet()),
        )
    }

    @Test
    fun `a second failure alongside an acknowledged one still speaks up`() {
        val acknowledged = ReadinessPrompt.acknowledging(Readiness.of(alertsOff), emptySet())

        // Battery optimisation has just started failing; the alerts switch is
        // old news. Only the new thing is worth the interruption.
        assertEquals(
            listOf(ReadinessCheck.BATTERY_OPTIMISATION),
            ReadinessPrompt
                .unannounced(Readiness.of(alertsOffAndOptimised), acknowledged)
                .map { it.check },
        )
    }

    @Test
    fun `warnings count as worth saying once, like failures`() {
        assertEquals(
            listOf(ReadinessCheck.BATTERY_OPTIMISATION),
            ReadinessPrompt
                .unannounced(Readiness.of(ReadinessFacts(batteryOptimised = true)), emptySet())
                .map { it.check },
        )
    }

    @Test
    fun `a check that starts passing again is forgotten`() {
        val acknowledged = ReadinessPrompt.acknowledging(Readiness.of(alertsOff), emptySet())

        val remembered = ReadinessPrompt.remembered(Readiness.of(ReadinessFacts()), acknowledged)

        assertEquals(emptySet<String>(), remembered)
    }

    @Test
    fun `a failure that clears and comes back is worth saying again`() {
        // This is the whole mechanism: acknowledging is "I have seen that this
        // is broken now", not "never tell me about this check".
        var acknowledged = ReadinessPrompt.acknowledging(Readiness.of(alertsOff), emptySet())
        acknowledged = ReadinessPrompt.remembered(Readiness.of(ReadinessFacts()), acknowledged)

        assertEquals(
            listOf(ReadinessCheck.ALERTS_ON),
            ReadinessPrompt.unannounced(Readiness.of(alertsOff), acknowledged).map { it.check },
        )
    }

    @Test
    fun `remembering keeps only the failures that still stand`() {
        val acknowledged =
            ReadinessPrompt.acknowledging(Readiness.of(alertsOffAndOptimised), emptySet())

        // The alerts switch is back on; battery optimisation is not.
        assertEquals(
            setOf(ReadinessCheck.BATTERY_OPTIMISATION.name),
            ReadinessPrompt.remembered(
                Readiness.of(ReadinessFacts(batteryOptimised = true)),
                acknowledged,
            ),
        )
    }

    @Test
    fun `an unchanged set is returned unchanged, so nothing is written for nothing`() {
        val findings = Readiness.of(alertsOff)
        val acknowledged = ReadinessPrompt.acknowledging(findings, emptySet())

        assertEquals(acknowledged, ReadinessPrompt.remembered(findings, acknowledged))
    }

    /**
     * The prompt only ever carries the failures just shown, not the whole
     * checklist — so acknowledging it must add and never prune. Pruning against
     * that partial list would drop the acknowledgement of everything else still
     * failing, and a warning the user dismissed last week would pop straight
     * back up behind the one they just answered.
     */
    @Test
    fun `answering one prompt does not un-acknowledge the others`() {
        // Battery optimisation was dismissed some nights ago.
        val old = ReadinessPrompt.acknowledging(
            Readiness.of(ReadinessFacts(batteryOptimised = true)),
            emptySet(),
        )
        val now = Readiness.of(ReadinessFacts(batteryOptimised = true, alertsEnabled = false))
        // Tonight only the alerts switch is new, so only it is shown.
        val shown = ReadinessPrompt.unannounced(now, old)
        assertEquals(listOf(ReadinessCheck.ALERTS_ON), shown.map { it.check })

        val after = ReadinessPrompt.acknowledging(shown, old)

        assertEquals(
            setOf(ReadinessCheck.BATTERY_OPTIMISATION.name, ReadinessCheck.ALERTS_ON.name),
            after,
        )
        assertEquals(emptyList<ReadinessFinding>(), ReadinessPrompt.unannounced(now, after))
    }
    /**
     * The camera row is the one check that stands for a set of rooms rather
     * than a single fact, and it can go from naming one room to naming another
     * without ever passing in between. Acknowledged by the check alone, the
     * second room would be filed as old news and never interrupt anyone.
     */
    @Test
    fun `a second room going unheard is its own interruption`() {
        fun unheard(vararg names: String) = Readiness.of(
            ReadinessFacts(
                cameras = names.map { CameraAudibility(it, it, live = true, lastAudioAtMs = null) },
            ),
        )

        val nurseryOnly = unheard("Nursery")
        val acknowledged = ReadinessPrompt.acknowledging(
            ReadinessPrompt.unannounced(nurseryOnly, emptySet()),
            emptySet(),
        )
        assertEquals(emptyList<ReadinessFinding>(), ReadinessPrompt.unannounced(nurseryOnly, acknowledged))

        // The play room goes silent too, without the nursery ever recovering.
        val both = unheard("Nursery", "Play room")

        assertEquals(
            listOf(ReadinessCheck.CAMERAS_HEARD),
            ReadinessPrompt.unannounced(both, acknowledged).map { it.check },
        )
    }

    /**
     * Rooms are acknowledged one at a time, so one recovering cannot take
     * another's acknowledgement with it. Keyed by the whole unheard set, the
     * play room coming back would leave "Nursery" looking like a failure nobody
     * had ever been told about.
     */
    @Test
    fun `one room recovering does not un-acknowledge another`() {
        fun unheard(vararg names: String) = Readiness.of(
            ReadinessFacts(
                cameras = names.map { CameraAudibility(it, it, live = true, lastAudioAtMs = null) },
            ),
        )

        val both = unheard("Nursery", "Play room")
        val acknowledged = ReadinessPrompt.acknowledging(both.problems(), emptySet())

        // The play room is heard again; the nursery never was.
        val nurseryOnly = unheard("Nursery")
        val remembered = ReadinessPrompt.remembered(nurseryOnly, acknowledged)

        assertEquals(
            emptyList<ReadinessFinding>(),
            ReadinessPrompt.unannounced(nurseryOnly, remembered),
        )
    }

    @Test
    fun `the same rooms in another order are the same failure`() {
        fun unheard(vararg names: String) = Readiness.of(
            ReadinessFacts(
                cameras = names.map { CameraAudibility(it, it, live = true, lastAudioAtMs = null) },
            ),
        )

        val acknowledged = ReadinessPrompt.acknowledging(
            unheard("Nursery", "Play room").problems(),
            emptySet(),
        )

        assertEquals(
            emptyList<ReadinessFinding>(),
            ReadinessPrompt.unannounced(unheard("Play room", "Nursery"), acknowledged),
        )
    }
    /**
     * Identities key off the camera id, not the name on the row. A rename is
     * not a room recovering, and a room that never came back must not be
     * announced again for having been given a different label.
     */
    @Test
    fun `renaming an unheard room is not a new failure`() {
        fun unheard(id: String, name: String) = Readiness.of(
            ReadinessFacts(
                cameras = listOf(
                    CameraAudibility(id, name, live = true, lastAudioAtMs = null),
                ),
            ),
        )

        val before = unheard("cam-1", "Nursery")
        val acknowledged = ReadinessPrompt.acknowledging(before.problems(), emptySet())

        val renamed = unheard("cam-1", "Baby's room")

        assertEquals(
            emptyList<ReadinessFinding>(),
            ReadinessPrompt.unannounced(renamed, ReadinessPrompt.remembered(renamed, acknowledged)),
        )
    }

    /** And two rooms that happen to share a name are still two rooms. */
    @Test
    fun `rooms with the same name are acknowledged separately`() {
        fun unheard(vararg ids: String) = Readiness.of(
            ReadinessFacts(
                cameras = ids.map { CameraAudibility(it, "Nursery", live = true, lastAudioAtMs = null) },
            ),
        )

        val acknowledged = ReadinessPrompt.acknowledging(unheard("cam-1").problems(), emptySet())

        assertEquals(
            listOf(ReadinessCheck.CAMERAS_HEARD),
            ReadinessPrompt.unannounced(unheard("cam-1", "cam-2"), acknowledged).map { it.check },
        )
    }
    /**
     * The full-screen-access explanation is a whole dialog about the very thing
     * this check reports. Answering it has to count, or the prompt would raise
     * a second modal saying the same thing in fewer words moments later.
     */
    @Test
    fun `an explanation shown elsewhere counts as having been said`() {
        val findings = Readiness.of(ReadinessFacts(fullScreenIntentAllowed = false))

        val acknowledged =
            ReadinessPrompt.acknowledging(ReadinessCheck.WAKE_SCREEN, emptySet())

        assertEquals(
            emptyList<ReadinessFinding>(),
            ReadinessPrompt.unannounced(findings, acknowledged),
        )
    }

    /** And only that one: the rest of the night is still unheard-of. */
    @Test
    fun `acknowledging one check leaves the others to speak`() {
        val findings = Readiness.of(
            ReadinessFacts(fullScreenIntentAllowed = false, alertsEnabled = false),
        )

        val acknowledged =
            ReadinessPrompt.acknowledging(ReadinessCheck.WAKE_SCREEN, emptySet())

        assertEquals(
            listOf(ReadinessCheck.ALERTS_ON),
            ReadinessPrompt.unannounced(findings, acknowledged).map { it.check },
        )
    }
    /**
     * The camera row names nothing whenever the monitor is starting up,
     * reconnecting, or waiting on a permission — which is routine. Treating
     * that as "the rooms recovered" would announce a room that has been unheard
     * for a week as new every time the monitor blinked.
     */
    @Test
    fun `a check that cannot be decided keeps what was already said about it`() {
        val unheard = Readiness.of(
            ReadinessFacts(
                cameras = listOf(
                    CameraAudibility("cam-1", "Nursery", live = true, lastAudioAtMs = null),
                ),
            ),
        )
        val acknowledged = ReadinessPrompt.acknowledging(unheard.problems(), emptySet())

        // Monitoring stops: nothing is listening, so nothing can be said about
        // any room.
        val stopped = Readiness.of(
            ReadinessFacts(
                monitoringRunning = false,
                cameras = listOf(
                    CameraAudibility("cam-1", "Nursery", live = false, lastAudioAtMs = null),
                ),
            ),
        )
        val throughTheGap = ReadinessPrompt.remembered(stopped, acknowledged)

        // And comes back to the same room, still unheard, still not news.
        assertEquals(
            emptyList<ReadinessFinding>(),
            ReadinessPrompt.unannounced(
                unheard,
                ReadinessPrompt.remembered(unheard, throughTheGap),
            ),
        )
    }

    /** A room that really was heard again is still forgotten. */
    @Test
    fun `a room that recovers is forgotten even so`() {
        val unheard = Readiness.of(
            ReadinessFacts(
                cameras = listOf(
                    CameraAudibility("cam-1", "Nursery", live = true, lastAudioAtMs = null),
                ),
            ),
        )
        val acknowledged = ReadinessPrompt.acknowledging(unheard.problems(), emptySet())

        val heard = Readiness.of(
            ReadinessFacts(
                cameras = listOf(
                    CameraAudibility("cam-1", "Nursery", live = true, lastAudioAtMs = 0L),
                ),
                nowMs = 0L,
            ),
        )

        assertEquals(emptySet<String>(), ReadinessPrompt.remembered(heard, acknowledged))
    }
    /**
     * A channel row says nothing at all while notifications are denied — a pass
     * by courtesy, so the card does not carry two red rows for one cause.
     * Treated as recovery, restoring the permission would announce a channel
     * failure the user was told about days ago all over again.
     */
    @Test
    fun `a check standing aside keeps what was already said about it`() {
        val channelOff = Readiness.of(ReadinessFacts(alertChannelEnabled = false))
        val acknowledged = ReadinessPrompt.acknowledging(channelOff.problems(), emptySet())
        assertEquals(
            listOf(ReadinessCheck.ALERT_CHANNEL),
            ReadinessPrompt.unannounced(channelOff, emptySet()).map { it.check },
        )

        // Notifications are denied outright: the channel row stands aside.
        val denied = Readiness.of(
            ReadinessFacts(notificationsAllowed = false, alertChannelEnabled = false),
        )
        val throughTheGap = ReadinessPrompt.remembered(denied, acknowledged)

        // The permission comes back; the channel was never fixed, and is not news.
        assertEquals(
            emptyList<ReadinessFinding>(),
            ReadinessPrompt.unannounced(
                channelOff,
                ReadinessPrompt.remembered(channelOff, throughTheGap),
            ),
        )
    }

    @Test
    fun `a channel that really was switched back on is forgotten`() {
        val channelOff = Readiness.of(ReadinessFacts(alertChannelEnabled = false))
        val acknowledged = ReadinessPrompt.acknowledging(channelOff.problems(), emptySet())

        assertEquals(
            emptySet<String>(),
            ReadinessPrompt.remembered(Readiness.of(ReadinessFacts()), acknowledged),
        )
    }
}

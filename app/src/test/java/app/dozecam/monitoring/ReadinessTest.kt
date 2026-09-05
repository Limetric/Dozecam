package app.dozecam.monitoring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadinessTest {

    private fun state(check: ReadinessCheck, facts: ReadinessFacts): ReadinessState =
        Readiness.of(facts).single { it.check == check }.state

    private fun finding(check: ReadinessCheck, facts: ReadinessFacts): ReadinessFinding =
        Readiness.of(facts).single { it.check == check }

    @Test
    fun `a phone with nothing wrong passes every check`() {
        val findings = Readiness.of(ReadinessFacts())

        assertTrue(findings.all { it.state == ReadinessState.PASS })
        assertEquals(ReadinessState.PASS, findings.worstState())
        assertEquals(emptyList<ReadinessFinding>(), findings.problems())
    }

    @Test
    fun `every check is reported exactly once, in card order`() {
        // The card renders what it is handed; a check silently missing from the
        // list would be a failure mode nobody is ever told about.
        assertEquals(ReadinessCheck.entries, Readiness.of(ReadinessFacts()).map { it.check })
    }

    // ---- Alerts can reach you ----

    @Test
    fun `denied notifications fail`() {
        assertEquals(
            ReadinessState.FAIL,
            state(ReadinessCheck.NOTIFICATIONS, ReadinessFacts(notificationsAllowed = false)),
        )
    }

    @Test
    fun `a disabled alert channel is not blamed on a user who cannot see it`() {
        // Notifications denied outright means the channel is moot: two red rows
        // for one cause is one more than anyone needs at bedtime.
        val facts = ReadinessFacts(notificationsAllowed = false, alertChannelEnabled = false)

        assertEquals(ReadinessState.FAIL, state(ReadinessCheck.NOTIFICATIONS, facts))
        assertEquals(ReadinessState.PASS, state(ReadinessCheck.ALERT_CHANNEL, facts))
        // A pass by courtesy, and it says so — standing aside is not the same
        // as being fixed, and the record of what the user has been told relies
        // on the difference.
        assertTrue(finding(ReadinessCheck.ALERT_CHANNEL, facts).masked)
    }

    @Test
    fun `a check that really passes is not marked as standing aside`() {
        assertTrue(Readiness.of(ReadinessFacts()).none { it.masked })
    }

    @Test
    fun `a channel switched off in Android fails on its own`() {
        assertEquals(
            ReadinessState.FAIL,
            state(ReadinessCheck.ALERT_CHANNEL, ReadinessFacts(alertChannelEnabled = false)),
        )
    }

    @Test
    fun `no full-screen-intent access fails`() {
        // The failure that looks most like success: the alert really is posted,
        // it simply never wakes anyone.
        assertEquals(
            ReadinessState.FAIL,
            state(ReadinessCheck.WAKE_SCREEN, ReadinessFacts(fullScreenIntentAllowed = false)),
        )
    }

    @Test
    fun `alerts switched off in the app fail`() {
        assertEquals(
            ReadinessState.FAIL,
            state(ReadinessCheck.ALERTS_ON, ReadinessFacts(alertsEnabled = false)),
        )
    }

    // ---- Alerts can be heard ----

    @Test
    fun `an alarm stream at zero fails`() {
        assertEquals(
            ReadinessState.FAIL,
            state(ReadinessCheck.ALARM_VOLUME, ReadinessFacts(alarmVolume = 0)),
        )
    }

    @Test
    fun `a muted alarm stream fails even at volume`() {
        assertEquals(
            ReadinessState.FAIL,
            state(ReadinessCheck.ALARM_VOLUME, ReadinessFacts(alarmVolume = 7, alarmsMuted = true)),
        )
    }

    @Test
    fun `total silence fails`() {
        assertEquals(
            ReadinessState.FAIL,
            state(ReadinessCheck.DO_NOT_DISTURB, ReadinessFacts(alarmsSuppressed = true)),
        )
    }

    @Test
    fun `chime and vibration both off fails`() {
        assertEquals(
            ReadinessState.FAIL,
            state(
                ReadinessCheck.ALERT_SIGNAL,
                ReadinessFacts(alertChime = false, alertVibrate = false),
            ),
        )
    }

    @Test
    fun `either a chime or a vibration is enough to be noticed`() {
        assertEquals(
            ReadinessState.PASS,
            state(
                ReadinessCheck.ALERT_SIGNAL,
                ReadinessFacts(alertChime = false, alertVibrate = true),
            ),
        )
        assertEquals(
            ReadinessState.PASS,
            state(
                ReadinessCheck.ALERT_SIGNAL,
                ReadinessFacts(alertChime = true, alertVibrate = false),
            ),
        )
    }

    // ---- Monitoring will keep running ----

    @Test
    fun `a monitor that is not running fails`() {
        assertEquals(
            ReadinessState.FAIL,
            state(ReadinessCheck.MONITORING, ReadinessFacts(monitoringRunning = false)),
        )
    }

    @Test
    fun `battery optimisation is a warning, not a failure`() {
        // Doze is allowed to stop a foreground service, not bound to. Reporting
        // a maybe as a certainty spends attention that the red rows need.
        assertEquals(
            ReadinessState.WARN,
            state(ReadinessCheck.BATTERY_OPTIMISATION, ReadinessFacts(batteryOptimised = true)),
        )
    }

    @Test
    fun `a low battery off the charger warns`() {
        assertEquals(
            ReadinessState.WARN,
            state(
                ReadinessCheck.POWER,
                ReadinessFacts(charging = false, batteryPercent = 10),
            ),
        )
    }

    @Test
    fun `a charging phone never warns about power, however low`() {
        assertEquals(
            ReadinessState.PASS,
            state(ReadinessCheck.POWER, ReadinessFacts(charging = true, batteryPercent = 1)),
        )
    }

    @Test
    fun `a full battery off the charger is fine`() {
        assertEquals(
            ReadinessState.PASS,
            state(ReadinessCheck.POWER, ReadinessFacts(charging = false, batteryPercent = 90)),
        )
    }

    @Test
    fun `the low-battery line is where it says it is`() {
        assertEquals(
            ReadinessState.PASS,
            state(
                ReadinessCheck.POWER,
                ReadinessFacts(charging = false, batteryPercent = Readiness.LOW_BATTERY_PERCENT),
            ),
        )
        assertEquals(
            ReadinessState.WARN,
            state(
                ReadinessCheck.POWER,
                ReadinessFacts(charging = false, batteryPercent = Readiness.LOW_BATTERY_PERCENT - 1),
            ),
        )
    }

    // ---- Every camera is being heard ----

    @Test
    fun `a room streaming video but never audio is not being heard`() {
        // The whole point of the check: perfectly connected, and monitored in
        // name only.
        val finding = finding(
            ReadinessCheck.CAMERAS_HEARD,
            ReadinessFacts(
                cameras = listOf(CameraAudibility("nursery", "Nursery", live = true, lastAudioAtMs = null)),
                nowMs = 60_000L,
            ),
        )

        assertEquals(ReadinessState.FAIL, finding.state)
        assertEquals(listOf("Nursery"), finding.cameras.map { it.name })
    }

    @Test
    fun `a room whose audio has gone stale is not being heard`() {
        val nowMs = 100_000L
        val stale = nowMs - Readiness.AUDIO_STALE_MS - 1

        val finding = finding(
            ReadinessCheck.CAMERAS_HEARD,
            ReadinessFacts(
                cameras = listOf(CameraAudibility("nursery", "Nursery", live = true, lastAudioAtMs = stale)),
                nowMs = nowMs,
            ),
        )

        assertEquals(ReadinessState.FAIL, finding.state)
    }

    @Test
    fun `a quiet room still decoding buffers is being heard`() {
        // Silence is not the absence of audio: a quiet nursery goes on decoding
        // buffers of near-zero PCM, and a check that cried wolf over that would
        // be the check nobody trusts.
        val nowMs = 100_000L

        assertEquals(
            ReadinessState.PASS,
            state(
                ReadinessCheck.CAMERAS_HEARD,
                ReadinessFacts(
                    cameras = listOf(
                        CameraAudibility(
                            "nursery",
                            "Nursery",
                            live = true,
                            lastAudioAtMs = nowMs - Readiness.AUDIO_STALE_MS,
                        ),
                    ),
                    nowMs = nowMs,
                ),
            ),
        )
    }

    @Test
    fun `an offline room is not being heard whatever it last decoded`() {
        assertEquals(
            ReadinessState.FAIL,
            state(
                ReadinessCheck.CAMERAS_HEARD,
                ReadinessFacts(
                    cameras = listOf(
                        CameraAudibility("nursery", "Nursery", live = false, lastAudioAtMs = 100_000L),
                    ),
                    nowMs = 100_000L,
                ),
            ),
        )
    }

    @Test
    fun `only the unheard rooms are named`() {
        val nowMs = 100_000L
        val finding = finding(
            ReadinessCheck.CAMERAS_HEARD,
            ReadinessFacts(
                cameras = listOf(
                    CameraAudibility("nursery", "Nursery", live = true, lastAudioAtMs = nowMs),
                    CameraAudibility("play-room", "Play room", live = true, lastAudioAtMs = null),
                    CameraAudibility("landing", "Landing", live = false, lastAudioAtMs = null),
                ),
                nowMs = nowMs,
            ),
        )

        assertEquals(listOf("Play room", "Landing"), finding.cameras.map { it.name })
    }

    @Test
    fun `no camera switched on is a failure of its own`() {
        val finding = finding(
            ReadinessCheck.CAMERAS_HEARD,
            ReadinessFacts(cameras = emptyList()),
        )

        assertEquals(ReadinessState.FAIL, finding.state)
        assertEquals(emptyList<CameraAudibility>(), finding.cameras)
    }

    @Test
    fun `the cameras are not blamed for a monitor that is not running`() {
        // Nothing is listening, so nothing can be said about whether a room can
        // be heard — and the monitoring row is already saying the real thing.
        val facts = ReadinessFacts(
            monitoringRunning = false,
            cameras = listOf(CameraAudibility("nursery", "Nursery", live = false, lastAudioAtMs = null)),
        )

        assertEquals(ReadinessState.WARN, state(ReadinessCheck.CAMERAS_HEARD, facts))
        assertEquals(ReadinessState.FAIL, state(ReadinessCheck.MONITORING, facts))
    }

    // ---- Aggregates ----

    @Test
    fun `one failure outranks any number of warnings`() {
        val findings = Readiness.of(
            ReadinessFacts(batteryOptimised = true, alertsEnabled = false),
        )

        assertEquals(ReadinessState.FAIL, findings.worstState())
    }

    @Test
    fun `warnings alone are still worth surfacing`() {
        val findings = Readiness.of(ReadinessFacts(batteryOptimised = true))

        assertEquals(ReadinessState.WARN, findings.worstState())
        assertEquals(listOf(ReadinessCheck.BATTERY_OPTIMISATION), findings.problems().map { it.check })
    }

    @Test
    fun `a passing check offers no remedy`() {
        Readiness.of(ReadinessFacts()).forEach {
            assertEquals(ReadinessRemedy.NONE, it.remedy)
        }
    }

    @Test
    fun `a failing check offers its own remedy`() {
        val findings = Readiness.of(ReadinessFacts(alertsEnabled = false, alarmVolume = 0))

        assertEquals(
            ReadinessRemedy.TURN_ALERTS_ON,
            findings.single { it.check == ReadinessCheck.ALERTS_ON }.remedy,
        )
        assertEquals(
            ReadinessRemedy.SOUND_SETTINGS,
            findings.single { it.check == ReadinessCheck.ALARM_VOLUME }.remedy,
        )
    }

    @Test
    fun `power has no button, because the fix is a cable`() {
        val findings = Readiness.of(ReadinessFacts(charging = false, batteryPercent = 5))

        assertEquals(
            ReadinessRemedy.NONE,
            findings.single { it.check == ReadinessCheck.POWER }.remedy,
        )
    }

    /**
     * The other way a full-screen intent stops working, and the one nothing
     * else on the phone would show: Android only launches one for a channel
     * still at high importance, so a channel turned *down* posts the alert
     * perfectly and never lights the screen.
     */
    @Test
    fun `a channel turned down fails its own check, not the one about the grant`() {
        val facts = ReadinessFacts(alertChannelWakesScreen = false)

        assertEquals(ReadinessState.FAIL, state(ReadinessCheck.ALERT_CHANNEL_PRIORITY, facts))
        // The alert still arrives; that is the point of the two rows.
        assertEquals(ReadinessState.PASS, state(ReadinessCheck.ALERT_CHANNEL, facts))
        assertEquals(ReadinessState.PASS, state(ReadinessCheck.WAKE_SCREEN, facts))
    }

    @Test
    fun `a channel that is off is not also reported as too quiet`() {
        val facts = ReadinessFacts(alertChannelEnabled = false, alertChannelWakesScreen = false)

        assertEquals(ReadinessState.FAIL, state(ReadinessCheck.ALERT_CHANNEL, facts))
        assertEquals(ReadinessState.PASS, state(ReadinessCheck.ALERT_CHANNEL_PRIORITY, facts))
    }

    @Test
    fun `notifications denied silences both channel rows`() {
        val facts = ReadinessFacts(
            notificationsAllowed = false,
            alertChannelEnabled = false,
            alertChannelWakesScreen = false,
        )

        assertEquals(ReadinessState.PASS, state(ReadinessCheck.ALERT_CHANNEL, facts))
        assertEquals(ReadinessState.PASS, state(ReadinessCheck.ALERT_CHANNEL_PRIORITY, facts))
    }

    @Test
    fun `a turned-down channel is fixed where its importance lives`() {
        assertEquals(
            ReadinessRemedy.NOTIFICATION_SETTINGS,
            finding(
                ReadinessCheck.ALERT_CHANNEL_PRIORITY,
                ReadinessFacts(alertChannelWakesScreen = false),
            ).remedy,
        )
    }
    /**
     * The alarm stream only matters to a phone that is going to play something.
     * [AlertSignaler] never starts its player with the chime off, so a red row
     * here would send a vibration-only user to a slider that changes nothing —
     * the false alarm that teaches people to stop reading this card.
     */
    @Test
    fun `a vibration-only alert is not failed for a silent alarm stream`() {
        assertEquals(
            ReadinessState.PASS,
            state(
                ReadinessCheck.ALARM_VOLUME,
                ReadinessFacts(alertChime = false, alertVibrate = true, alarmVolume = 0),
            ),
        )
    }

    @Test
    fun `Do Not Disturb still reaches a vibration-only alert`() {
        // Total silence suppresses vibration by usage as well as sound.
        assertEquals(
            ReadinessState.FAIL,
            state(
                ReadinessCheck.DO_NOT_DISTURB,
                ReadinessFacts(alertChime = false, alertVibrate = true, alarmsSuppressed = true),
            ),
        )
    }

    /** A tablet with the chime off can signal nothing at all, and must say so. */
    @Test
    fun `vibration on a device that cannot vibrate is not a signal`() {
        assertEquals(
            ReadinessState.FAIL,
            state(
                ReadinessCheck.ALERT_SIGNAL,
                ReadinessFacts(alertChime = false, alertVibrate = true, hasVibrator = false),
            ),
        )
    }

    @Test
    fun `a chime needs no vibrator behind it`() {
        assertEquals(
            ReadinessState.PASS,
            state(
                ReadinessCheck.ALERT_SIGNAL,
                ReadinessFacts(alertChime = true, alertVibrate = true, hasVibrator = false),
            ),
        )
    }
    /**
     * With no camera there is nothing to monitor, and starting the service
     * would only have settings stop it again the moment it saw so — a button
     * that could never clear its own row. The camera row carries the one that
     * leads somewhere.
     */
    @Test
    fun `with no camera the monitoring row keeps its sentence and drops its button`() {
        val facts = ReadinessFacts(monitoringRunning = false, cameras = emptyList())

        val monitoring = finding(ReadinessCheck.MONITORING, facts)
        assertEquals(ReadinessState.FAIL, monitoring.state)
        assertEquals(ReadinessRemedy.NONE, monitoring.remedy)

        val cameras = finding(ReadinessCheck.CAMERAS_HEARD, facts)
        assertEquals(ReadinessState.FAIL, cameras.state)
        assertEquals(ReadinessRemedy.CAMERA_SETTINGS, cameras.remedy)
    }

    @Test
    fun `with a camera to listen to the monitoring row offers to start`() {
        assertEquals(
            ReadinessRemedy.START_MONITORING,
            finding(
                ReadinessCheck.MONITORING,
                ReadinessFacts(
                    monitoringRunning = false,
                    cameras = listOf(CameraAudibility("nursery", "Nursery", live = false, lastAudioAtMs = null)),
                ),
            ).remedy,
        )
    }
    /**
     * Enabled cameras with no way in at all — an `rtsps://` URL with no Protect
     * livestream behind it, since Media3 has no RTSP TLS. Arming is refused by
     * the same gate the viewer uses, so an offer to start would be a button
     * that could never clear its own row.
     */
    @Test
    fun `cameras nothing can listen to are a camera problem, not a start button`() {
        val facts = ReadinessFacts(
            monitoringRunning = false,
            anyMonitorable = false,
            cameras = listOf(
                CameraAudibility("nursery", "Nursery", live = false, lastAudioAtMs = null),
                CameraAudibility("landing", "Landing", live = false, lastAudioAtMs = null),
            ),
        )

        val monitoring = finding(ReadinessCheck.MONITORING, facts)
        assertEquals(ReadinessState.FAIL, monitoring.state)
        assertEquals(ReadinessRemedy.NONE, monitoring.remedy)

        // Named and actionable, rather than the "cannot check yet" warning that
        // offers nothing to press.
        val cameras = finding(ReadinessCheck.CAMERAS_HEARD, facts)
        assertEquals(ReadinessState.FAIL, cameras.state)
        assertEquals(listOf("Nursery", "Landing"), cameras.cameras.map { it.name })
        assertEquals(ReadinessRemedy.CAMERA_SETTINGS, cameras.remedy)
    }

    @Test
    fun `a monitorable camera with the monitor off still offers to start`() {
        val facts = ReadinessFacts(
            monitoringRunning = false,
            anyMonitorable = true,
            cameras = listOf(CameraAudibility("nursery", "Nursery", live = false, lastAudioAtMs = null)),
        )

        assertEquals(
            ReadinessRemedy.START_MONITORING,
            finding(ReadinessCheck.MONITORING, facts).remedy,
        )
        // And the cameras stand aside, because nothing is listening yet.
        assertEquals(ReadinessState.WARN, state(ReadinessCheck.CAMERAS_HEARD, facts))
    }
    /**
     * Nothing on this phone can reach a camera at all. Named as its own row
     * because it is separately losable, and because reported through the camera
     * row it would send the user to a camera list that cannot fix it.
     */
    @Test
    fun `no local network access is its own failure, and the cameras stand aside`() {
        val facts = ReadinessFacts(
            localNetworkGranted = false,
            cameras = listOf(CameraAudibility("nursery", "Nursery", live = false, lastAudioAtMs = null)),
        )

        val network = finding(ReadinessCheck.LOCAL_NETWORK, facts)
        assertEquals(ReadinessState.FAIL, network.state)
        assertEquals(ReadinessRemedy.GRANT_LOCAL_NETWORK, network.remedy)

        val cameras = finding(ReadinessCheck.CAMERAS_HEARD, facts)
        assertEquals(ReadinessState.WARN, cameras.state)
        assertEquals(ReadinessRemedy.NONE, cameras.remedy)
    }

    /** A revoked grant is invisible from a service that is still running. */
    @Test
    fun `a running monitor does not excuse a missing grant`() {
        val facts = ReadinessFacts(monitoringRunning = true, localNetworkGranted = false)

        assertEquals(ReadinessState.PASS, state(ReadinessCheck.MONITORING, facts))
        assertEquals(ReadinessState.FAIL, state(ReadinessCheck.LOCAL_NETWORK, facts))
    }
    /**
     * Not every fuel gauge reports a capacity, and the property answers
     * `Integer.MIN_VALUE` when it cannot. Read at face value that is a flat
     * battery on every such phone, every night.
     */
    @Test
    fun `an unknown battery level is not a low one`() {
        assertEquals(
            ReadinessState.PASS,
            state(
                ReadinessCheck.POWER,
                ReadinessFacts(charging = false, batteryPercent = null),
            ),
        )
    }
}

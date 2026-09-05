package app.dozecam.monitoring

/**
 * The bedtime check: *will this actually wake me?*
 *
 * A baby monitor is trusted before it is tested. Someone sets Dozecam up in the
 * afternoon, sees a live picture, and goes to bed believing it will wake them —
 * and a working video feed says nothing at all about whether an alert can be
 * posted, whether it may light the screen, whether the phone will make a sound,
 * whether the service will still be alive at 3am, or whether the audio that
 * feeds the detector has ever once arrived.
 *
 * Every one of those can be checked, and each fails independently, so each gets
 * its own line and its own way out rather than being folded into a single
 * verdict a parent cannot act on. The decision is made here, from plain facts,
 * so the whole checklist is testable without a device; gathering those facts is
 * [ReadinessProbe]'s job and carrying out a remedy is the caller's.
 */
enum class ReadinessState {
    /** Nothing to do. */
    PASS,

    /**
     * Might still wake you, and might not. Reserved for the things Android
     * gives no promise about either way — a service Doze is free to stop, a
     * phone that may or may not last the night — because reporting a maybe as
     * a failure would spend the user's attention on something they may have
     * already handled.
     */
    WARN,

    /** Will not wake you, as things stand. */
    FAIL,
}

/** The four questions the checks answer between them, in the order they matter. */
enum class ReadinessGroup { REACH, AUDIBLE, KEEP_RUNNING, CAMERAS }

/**
 * What a remedy has to do. Named rather than carried as an [android.content.Intent]
 * so the model stays free of Android, and because two of them are not intents at
 * all: one is a setting of ours, one starts the service.
 */
enum class ReadinessRemedy {
    /** Nothing anyone can press; the row is advice. */
    NONE,
    REQUEST_NOTIFICATIONS,
    NOTIFICATION_SETTINGS,
    FULL_SCREEN_INTENT_SETTINGS,
    TURN_ALERTS_ON,
    SOUND_SETTINGS,
    DO_NOT_DISTURB_SETTINGS,
    TURN_CHIME_ON,
    START_MONITORING,
    GRANT_LOCAL_NETWORK,
    BATTERY_SETTINGS,
    CAMERA_SETTINGS,
}

/**
 * One thing that has to be true tonight. The order of the entries is the order
 * the card renders them in, and the id is stable — it is remembered, in
 * [app.dozecam.data.AppSettings.acknowledgedReadinessChecks], to keep a prompt
 * about a failure from becoming a nightly one.
 */
enum class ReadinessCheck(val group: ReadinessGroup, val remedy: ReadinessRemedy) {
    /** Notifications denied: [MonitoringNotifications.postAlert] cannot post at all. */
    NOTIFICATIONS(ReadinessGroup.REACH, ReadinessRemedy.REQUEST_NOTIFICATIONS),

    /**
     * The alert channel switched off in Android's own settings. Separate from
     * the permission because it is separately losable — and because a channel
     * cannot be re-enabled from inside the app, only opened to.
     */
    ALERT_CHANNEL(ReadinessGroup.REACH, ReadinessRemedy.NOTIFICATION_SETTINGS),

    /**
     * The alert channel turned *down* rather than off. Android only launches a
     * full-screen intent for a notification whose channel is still at high
     * importance; demoted to "Default" the alert is posted perfectly, appears
     * in the shade, and never lights the screen — the same silent failure as a
     * missing full-screen-intent grant, arrived at by a different route, and
     * invisible from everything else about the app working.
     *
     * Its own row rather than folded into [ALERT_CHANNEL] because the two are
     * different sentences: one is "Android will not show this", the other is
     * "Android will show this quietly".
     */
    ALERT_CHANNEL_PRIORITY(ReadinessGroup.REACH, ReadinessRemedy.NOTIFICATION_SETTINGS),

    /**
     * Android 14+ special app access. Without it the alert degrades to a
     * heads-up notification nobody sees, which is the failure that looks most
     * like success: the alert really is posted, it simply never wakes anyone.
     */
    WAKE_SCREEN(ReadinessGroup.REACH, ReadinessRemedy.FULL_SCREEN_INTENT_SETTINGS),

    /** Our own switch, off: the detector still runs but nothing reaches anyone. */
    ALERTS_ON(ReadinessGroup.REACH, ReadinessRemedy.TURN_ALERTS_ON),

    /** The alarm stream at zero, or muted. Dozecam plays on it and never overrides it. */
    ALARM_VOLUME(ReadinessGroup.AUDIBLE, ReadinessRemedy.SOUND_SETTINGS),

    /**
     * Do Not Disturb filtering alarms out. Alarm usage gets through DND's
     * *default* rules — see [AlarmAudio] — but a profile that silences alarms
     * too is allowed, and silences this.
     */
    DO_NOT_DISTURB(ReadinessGroup.AUDIBLE, ReadinessRemedy.DO_NOT_DISTURB_SETTINGS),

    /** Chime and vibration both off: the alert would arrive without a sound or a buzz. */
    ALERT_SIGNAL(ReadinessGroup.AUDIBLE, ReadinessRemedy.TURN_CHIME_ON),

    /** The service is not running, so nothing is listening for a sound to alert about. */
    MONITORING(ReadinessGroup.KEEP_RUNNING, ReadinessRemedy.START_MONITORING),

    /** Battery optimisation left free to stop the service partway through the night. */
    BATTERY_OPTIMISATION(ReadinessGroup.KEEP_RUNNING, ReadinessRemedy.BATTERY_SETTINGS),

    /** Not charging, with a night of decoding ahead. Advice: no button can fix a cable. */
    POWER(ReadinessGroup.KEEP_RUNNING, ReadinessRemedy.NONE),

    /**
     * Local-network access, without which nothing on this phone can reach a
     * camera at all: every RTSP connection is dropped as a connect timeout.
     *
     * Its own row because it is separately losable — Android 16+ can have it
     * revoked from system settings while the service goes on running — and
     * because nothing else would name it. Reported here it would otherwise
     * surface as every room being unheard, pointing the user at a camera list
     * that cannot fix it.
     */
    LOCAL_NETWORK(ReadinessGroup.CAMERAS, ReadinessRemedy.GRANT_LOCAL_NETWORK),

    /**
     * A camera that is switched on but is not actually being heard. Streaming
     * video perfectly is not the same as producing an audio buffer, and a room
     * that has never produced one is monitored in name only.
     */
    CAMERAS_HEARD(ReadinessGroup.CAMERAS, ReadinessRemedy.CAMERA_SETTINGS),
}

/**
 * Whether one enabled camera is actually being heard right now.
 *
 * [lastAudioAtMs] is the monotonic moment its last audio buffer was decoded, on
 * the connection it is on now, or null if none ever has been — the one signal
 * that separates a quiet nursery from a stream that plays without ever yielding
 * a sample. See [CameraMonitorState.lastAudioAtMs].
 */
data class CameraAudibility(
    val cameraId: String,
    val name: String,
    val live: Boolean,
    val lastAudioAtMs: Long?,
)

/**
 * Everything the checks are decided from, as plain values. Defaults are the
 * healthy answer so a test states only the fact it is about.
 *
 * [nowMs] and [CameraAudibility.lastAudioAtMs] share one monotonic clock
 * ([android.os.SystemClock.elapsedRealtime]); a wall clock would make a camera
 * look freshly heard, or long gone, the moment the phone corrected its time.
 */
data class ReadinessFacts(
    val notificationsAllowed: Boolean = true,
    val alertChannelEnabled: Boolean = true,
    /** The alert channel is still at the high importance a full-screen intent needs. */
    val alertChannelWakesScreen: Boolean = true,
    /** True below Android 14, where there is no gate to be refused by. */
    val fullScreenIntentAllowed: Boolean = true,
    val alertsEnabled: Boolean = true,
    val alertChime: Boolean = true,
    val alertVibrate: Boolean = true,
    /** The device's alarm stream volume, and whether it is muted. */
    val alarmVolume: Int = 1,
    /** Whether this device has a vibrator at all. Tablets often do not. */
    val hasVibrator: Boolean = true,
    val alarmsMuted: Boolean = false,
    /** Do Not Disturb is silencing everything, alarms included. */
    val alarmsSuppressed: Boolean = false,
    /**
     * Do Not Disturb is on and filtering by priority — a state whose effect on
     * an alarm is not knowable from here. Whether alarms are among the allowed
     * categories lives in the notification policy, which only an app holding
     * notification-policy access may read, and that is a great deal of access
     * to hold for one row of a checklist.
     */
    val dndFiltering: Boolean = false,
    val monitoringRunning: Boolean = true,
    /** True when Dozecam is *subject to* battery optimisation, i.e. not exempt. */
    val batteryOptimised: Boolean = false,
    val charging: Boolean = true,
    /**
     * How much charge is left, or null where the device will not say. Not every
     * fuel gauge reports a capacity, and an unknown one must not be read as an
     * empty one.
     */
    val batteryPercent: Int? = 100,
    /** Every switched-on camera, whether or not the monitor found a way to hear it. */
    val cameras: List<CameraAudibility> =
        listOf(CameraAudibility(cameraId = "", name = "", live = true, lastAudioAtMs = 0L)),
    /**
     * Whether any switched-on camera has a transport the monitor could listen
     * over at all — see [monitorable]. A camera can be enabled and still have
     * no way in: an `rtsps://` URL with no Protect livestream behind it is the
     * usual one, since Media3 has no RTSP TLS.
     */
    val anyMonitorable: Boolean = true,
    /** Without it every camera connection is dropped as a timeout. */
    val localNetworkGranted: Boolean = true,
    val nowMs: Long = 0L,
)

/**
 * One check, decided. [cameras] is the rooms that are not being heard, for the
 * one row that has something to name; every other check leaves it empty. Whole
 * cameras rather than their names, because two things want different halves of
 * them: the row shows the name, and the record of what has already been said
 * about a room has to key off the id, which a rename cannot move.
 */
data class ReadinessFinding(
    val check: ReadinessCheck,
    val state: ReadinessState,
    val cameras: List<CameraAudibility> = emptyList(),
    /**
     * Its check's own remedy, unless the check passes — nothing to offer then —
     * or the caller knows better. The one place that does is a camera row with
     * no monitor running behind it: the button would send someone to the camera
     * list over a problem that is not in it.
     */
    val remedy: ReadinessRemedy =
        if (state == ReadinessState.PASS) ReadinessRemedy.NONE else check.remedy,
    /**
     * Whether this check was not really evaluated at all, because something it
     * depends on had already failed.
     *
     * Several checks stand aside rather than pile a second red row onto one
     * cause: the channel rows say nothing while notifications are denied
     * outright, and the camera row says nothing while nothing is listening.
     * They report as passing so the card stays legible — but a pass by
     * courtesy is not evidence that anything was fixed, and anything keeping a
     * record of what the user has already been told must not throw that record
     * away on one. See [ReadinessPrompt].
     */
    val masked: Boolean = false,
    /**
     * Whether this is a warning about not being able to check, rather than
     * about something found to be wrong.
     *
     * The card says it either way — "will this wake me?" is not answered by
     * silence — but nothing unverifiable is allowed to interrupt anyone. A
     * bedtime Do Not Disturb schedule is on every night by design, and a
     * warning that appeared over the cameras every night would be the one
     * people stop reading by the night it matters.
     */
    val unverified: Boolean = false,
)

object Readiness {

    /**
     * How long a camera may go without a decoded buffer before it stops
     * counting as heard. Generous on purpose: silence is not the absence of
     * audio — a quiet room still decodes buffers of near-zero PCM — so this
     * only has to outlast a hiccup in delivery, and a monitor that cried wolf
     * over a two-second stall would be the check nobody trusts.
     */
    const val AUDIO_STALE_MS = 15_000L

    /**
     * Below this, and off the charger, a night of decoding audio is a real
     * question. Not a failure — plenty of phones would manage it — which is
     * exactly why it is a [ReadinessState.WARN] with no button: the fix is a
     * cable, and the app is not the one that can go and get it.
     */
    const val LOW_BATTERY_PERCENT = 30

    /** Every check, in the order the card shows them. */
    fun of(facts: ReadinessFacts): List<ReadinessFinding> = listOf(
        finding(ReadinessCheck.NOTIFICATIONS, facts.notificationsAllowed),
        // Only asked once the permission is in hand: a channel cannot be
        // reported as disabled by a user who was never able to see it, and two
        // red rows for one cause is one more than anybody needs at bedtime.
        finding(
            ReadinessCheck.ALERT_CHANNEL,
            facts.alertChannelEnabled,
            masked = !facts.notificationsAllowed,
        ),
        // Both of the checks above have to be answered before this one means
        // anything: a channel nobody can see cannot be reported as too quiet.
        finding(
            ReadinessCheck.ALERT_CHANNEL_PRIORITY,
            facts.alertChannelWakesScreen,
            masked = !facts.notificationsAllowed || !facts.alertChannelEnabled,
        ),
        finding(ReadinessCheck.WAKE_SCREEN, facts.fullScreenIntentAllowed),
        finding(ReadinessCheck.ALERTS_ON, facts.alertsEnabled),
        // Only asked of a phone that is going to play something. With the
        // chime off, [AlertSignaler] never starts its player at all, so the
        // alarm stream's volume has no bearing on the alert — and a red row
        // sending a vibration-only user to a slider that changes nothing is
        // exactly the false alarm that teaches people to stop reading this.
        finding(
            ReadinessCheck.ALARM_VOLUME,
            !facts.alertChime || (facts.alarmVolume > 0 && !facts.alarmsMuted),
        ),
        // Asked unconditionally, unlike the volume above: Do Not Disturb
        // filters by usage, so it reaches a vibration-only alert exactly as it
        // reaches a chime.
        //
        // Three outcomes rather than two, because there are three states and
        // only two of them are knowable. Total silence stops an alarm outright.
        // Priority mode might: alarms are among its allowed categories by
        // default and can be taken out of them, and which it is cannot be read
        // without notification-policy access. Reporting that as fine is how the
        // card comes to say "ready for tonight" over a phone that will deliver
        // nothing — so it says what is true instead, that it cannot tell, and
        // offers the screen where the answer is.
        dndFinding(facts),
        // Vibration only counts where there is something to vibrate. A tablet
        // propped up as a monitor with the chime switched off would otherwise
        // pass this row while being incapable of signalling anything at all.
        finding(
            ReadinessCheck.ALERT_SIGNAL,
            facts.alertChime || (facts.alertVibrate && facts.hasVibrator),
        ),
        ReadinessFinding(
            check = ReadinessCheck.MONITORING,
            state = if (facts.monitoringRunning) ReadinessState.PASS else ReadinessState.FAIL,
            // The row still says the true thing — nothing is listening — but
            // an offer to start is only honest where starting would achieve
            // something. With no camera switched on, or none the monitor has
            // any way to hear, arming is refused by the same gate the viewer
            // uses and the row could never be cleared by pressing it. The
            // camera row below carries the button that leads somewhere.
            // The empty list is spelled out alongside the flag rather than left
            // to the caller to keep consistent: no camera cannot mean anything
            // but nothing monitorable, and a facts object that said otherwise
            // would put back the button this exists to remove.
            remedy = if (
                facts.monitoringRunning || !facts.anyMonitorable || facts.cameras.isEmpty()
            ) {
                ReadinessRemedy.NONE
            } else {
                ReadinessCheck.MONITORING.remedy
            },
        ),
        finding(
            ReadinessCheck.BATTERY_OPTIMISATION,
            !facts.batteryOptimised,
            // Doze is *allowed* to stop a foreground service, not bound to;
            // plenty of phones never do. Stated as the risk it is.
            failedState = ReadinessState.WARN,
        ),
        // An unknown charge is not a low one. A device that will not report a
        // capacity would otherwise be told every night that it may not last
        // until morning, which is a warning that is wrong every time it appears.
        finding(
            ReadinessCheck.POWER,
            facts.charging || (facts.batteryPercent ?: 100) >= LOW_BATTERY_PERCENT,
            failedState = ReadinessState.WARN,
        ),
        finding(ReadinessCheck.LOCAL_NETWORK, facts.localNetworkGranted),
        cameras(facts),
    )

    private fun dndFinding(facts: ReadinessFacts): ReadinessFinding = when {
        facts.alarmsSuppressed -> ReadinessFinding(
            check = ReadinessCheck.DO_NOT_DISTURB,
            state = ReadinessState.FAIL,
        )
        facts.dndFiltering -> ReadinessFinding(
            check = ReadinessCheck.DO_NOT_DISTURB,
            state = ReadinessState.WARN,
            unverified = true,
        )
        else -> ReadinessFinding(ReadinessCheck.DO_NOT_DISTURB, ReadinessState.PASS)
    }

    /**
     * Which rooms the monitor is not actually hearing.
     *
     * With no camera switched on there is nothing to monitor, and that is the
     * whole answer. With monitoring not yet running there is nothing to report
     * either — every camera would read as unheard for the plain reason that
     * nobody is listening — so this stands aside and lets
     * [ReadinessCheck.MONITORING] be the one row that says so.
     */
    private fun cameras(facts: ReadinessFacts): ReadinessFinding {
        if (facts.cameras.isEmpty()) {
            return ReadinessFinding(ReadinessCheck.CAMERAS_HEARD, ReadinessState.FAIL)
        }
        // Nothing can reach a camera at all, so nothing can be said about any
        // one of them. The row above names the real cause and carries the only
        // button that helps; this one would send the user to a camera list that
        // is not what is broken.
        if (!facts.localNetworkGranted) {
            return ReadinessFinding(
                check = ReadinessCheck.CAMERAS_HEARD,
                state = ReadinessState.WARN,
                remedy = ReadinessRemedy.NONE,
                masked = true,
            )
        }
        // Switched on, and with no way in at all. Stated as a failure of the
        // cameras rather than left to the monitoring row, because it is the
        // camera list that can be fixed — and without this it would be reported
        // as the "cannot check yet" warning below, which is the one shape of
        // this row that offers nothing to press.
        if (!facts.anyMonitorable) {
            return ReadinessFinding(
                check = ReadinessCheck.CAMERAS_HEARD,
                state = ReadinessState.FAIL,
                cameras = facts.cameras,
            )
        }
        if (!facts.monitoringRunning) {
            return ReadinessFinding(
                check = ReadinessCheck.CAMERAS_HEARD,
                state = ReadinessState.WARN,
                remedy = ReadinessRemedy.NONE,
                masked = true,
            )
        }
        val unheard = facts.cameras.filterNot { heard(it, facts.nowMs) }
        return ReadinessFinding(
            check = ReadinessCheck.CAMERAS_HEARD,
            state = if (unheard.isEmpty()) ReadinessState.PASS else ReadinessState.FAIL,
            cameras = unheard,
        )
    }

    /**
     * Live *and* recently decoded. Both, because they fail separately and for
     * different reasons: a camera can be offline, and a camera can be perfectly
     * connected while its audio has never once arrived.
     */
    private fun heard(camera: CameraAudibility, nowMs: Long): Boolean {
        val lastAudioAtMs = camera.lastAudioAtMs ?: return false
        return camera.live && nowMs - lastAudioAtMs <= AUDIO_STALE_MS
    }

    private fun finding(
        check: ReadinessCheck,
        passed: Boolean,
        failedState: ReadinessState = ReadinessState.FAIL,
        /** Something this check depends on failed first; it stands aside. */
        masked: Boolean = false,
    ): ReadinessFinding = ReadinessFinding(
        check = check,
        state = if (passed || masked) ReadinessState.PASS else failedState,
        masked = masked,
    )
}

/** The worst state in the list, which is the state of the night as a whole. */
fun List<ReadinessFinding>.worstState(): ReadinessState = when {
    any { it.state == ReadinessState.FAIL } -> ReadinessState.FAIL
    any { it.state == ReadinessState.WARN } -> ReadinessState.WARN
    else -> ReadinessState.PASS
}

/** Everything that is not simply fine, in check order. */
fun List<ReadinessFinding>.problems(): List<ReadinessFinding> =
    filter { it.state != ReadinessState.PASS }

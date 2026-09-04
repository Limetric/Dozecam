package app.dozecam.monitoring

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import app.dozecam.DozecamApp
import app.dozecam.audio.SoundDetector
import app.dozecam.data.Camera
import app.dozecam.data.SoundMode
import kotlinx.coroutines.flow.first
import app.dozecam.player.ConnectionState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.shadows.ShadowPowerManager

/**
 * Covers the paths that do not build a decoder. Starting an actual
 * [CameraAudioMonitor] pulls in ExoPlayer and a live RTSP socket, so what a
 * running monitor does is covered by [MonitorPlanTest] and the player tests
 * instead.
 */
@RunWith(RobolectricTestRunner::class)
class MonitoringServiceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val container get() = (context.applicationContext as DozecamApp).container

    /**
     * The shadow keeps the newest wake lock in process-wide state, so "the
     * latest one" is only this service's if nothing else in the fork made one
     * first. Without this the assertions below read whichever test happened to
     * run before them, which makes them pass or fail on test ordering rather
     * than on anything the service did.
     */
    @Before
    fun forgetOtherTestsWakeLocks() {
        ShadowPowerManager.clearWakeLocks()
    }

    /**
     * The shadow's media stream starts at volume zero, which the service reads
     * as "nobody can hear the mix" — true to the rule, and the opposite of
     * what most of these tests are about. A phone with its volume up is the
     * baseline; the one test about volume turns it down itself.
     */
    private val services = mutableListOf<ServiceController<MonitoringService>>()

    private fun createService(): ServiceController<MonitoringService> =
        Robolectric.buildService(MonitoringService::class.java).create().also { services += it }

    private fun destroy(controller: ServiceController<MonitoringService>) {
        services -= controller
        controller.destroy()
    }

    /**
     * The preferences store is one per process, not per test, and a service
     * left running keeps collecting it: the next test's settings write would
     * reach a service whose Application — and audio focus receiver — is
     * already gone. So no service outlives its test.
     */
    @After
    fun destroyServices() {
        services.toList().forEach(::destroy)
    }

    /**
     * Every test starts from a phone that is not playing anything aloud and
     * wakes on sound, whatever the last one left in the shared store.
     */
    @Before
    fun resetSettings() = runTest {
        container.appSettings.update {
            it.copy(soundMode = SoundMode.OFF, alertsEnabled = true)
        }
        container.monitoringState.listeningCameraIds.value = emptySet()
    }

    /** Listen mode's switch: the stored sound mode, which the service watches. */
    private suspend fun listenAloud() {
        container.appSettings.update { it.copy(soundMode = SoundMode.ALL_ALOUD) }
    }

    private suspend fun soundMode(): SoundMode = container.appSettings.settings.first().soundMode

    /**
     * A write the service makes lands on the store from its main-thread scope
     * a hop or two after the event that caused it, and the paused looper only
     * moves when told to. Idled and read until it shows, or until it clearly
     * never will.
     */
    private suspend fun awaitSoundMode(expected: SoundMode): SoundMode {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            val mode = soundMode()
            if (mode == expected) return mode
            Thread.sleep(10)
        }
        return soundMode()
    }

    @Before
    fun turnTheVolumeUp() {
        context.getSystemService(AudioManager::class.java)
            .setStreamVolume(AudioManager.STREAM_MUSIC, 5, 0)
    }

    @Test
    fun `with nothing to listen to the service idles without a wake lock`() = runTest {
        val service = createService().get()

        // Nothing to decode, so nothing to keep the CPU awake for.
        assertFalse(ShadowPowerManager.getLatestWakeLock().isHeld)
        // But still alive: stopping here would race a camera switched straight
        // back on, and monitoring would silently stay off.
        assertFalse(shadowOf(service).isStoppedBySelf)
    }

    @Test
    fun `an unmonitorable camera is not something to stay awake for`() = runTest {
        // rtsps cannot be monitored: Media3's RTSP stack has no TLS. Such a
        // camera is watchable, so it stays enabled — but there is no audio to
        // decode for it.
        container.cameras.upsert(
            Camera("a", "Nursery", "rtsps://cam:7441/a", enabled = true),
        )

        createService().get()

        assertFalse(ShadowPowerManager.getLatestWakeLock().isHeld)
    }

    @Test
    fun `stopping clears the state the viewer reads`() = runTest {
        val controller = createService()
        container.monitoringState.put(CameraMonitorState("a", "Nursery", level = 0.4f))

        destroy(controller)

        assertFalse(container.monitoringState.serviceRunning.value)
        assertTrue(container.monitoringState.cameras.value.isEmpty())
    }

    /**
     * Stopping monitoring from the notification's own action leaves the shade
     * showing whatever the last alert posted; a card still offering a live view
     * of a camera nobody is listening to any more is a lie by the time it is
     * tapped.
     */
    @Test
    fun `stopping takes any alert notification with it`() = runTest {
        val controller = createService()
        MonitoringNotifications.postAlert(context, "a", "Nursery")

        destroy(controller)

        assertNull(
            shadowOf(context.getSystemService(NotificationManager::class.java))
                .getNotification(MonitoringNotifications.ALERT_NOTIFICATION_ID),
        )
    }

    private fun live(id: String, name: String) =
        CameraMonitorState(id, name, level = 0f, connection = ConnectionState.Live)

    /**
     * A room's cry began while it was aloud, so its alarm was withheld: someone
     * awake was hearing it. Losing the speaker mid-cry must not leave that room
     * silent — the detector will not fire again until the crying pauses, so the
     * withheld alarm has to be raised by the loss itself.
     */
    @Test
    fun `losing the speaker mid-cry raises the alarm that was withheld`() = runTest {
        // The alarm's own noise is beside the point; only whether it is raised.
        container.appSettings.update { it.copy(alertChime = false, alertVibrate = false) }
        createService().get()
        val state = container.monitoringState
        state.put(live("a", "Nursery"))
        listenAloud()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(setOf("a"), state.listeningCameraIds.value)
        state.update("a") { it.copy(phase = SoundDetector.Phase.TRIGGERED) }
        assertNull(container.alertSignaler.alarmingCameraId.value)

        state.viewerAudible.value = true
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(emptySet<String>(), state.listeningCameraIds.value)
        assertEquals("a", container.alertSignaler.alarmingCameraId.value)
        val alert = shadowOf(context.getSystemService(NotificationManager::class.java))
            .getNotification(MonitoringNotifications.ALERT_NOTIFICATION_ID)
        assertNotNull(alert.fullScreenIntent)
        container.alertSignaler.stop()
    }

    /**
     * The speaker can also go by way of the notification's action, which
     * empties the shared record before the service hears about the setting.
     * The withheld alarm must still be raised.
     */
    @Test
    fun `listen mode switched off mid-cry raises the alarm that was withheld`() = runTest {
        container.appSettings.update { it.copy(alertChime = false, alertVibrate = false) }
        createService().get()
        val state = container.monitoringState
        state.put(live("a", "Nursery"))
        listenAloud()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(setOf("a"), state.listeningCameraIds.value)
        state.update("a") { it.copy(phase = SoundDetector.Phase.TRIGGERED) }

        state.listeningCameraIds.value = emptySet()
        container.appSettings.update { it.copy(soundMode = SoundMode.OFF) }
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals("a", container.alertSignaler.alarmingCameraId.value)
        container.alertSignaler.stop()
    }

    /**
     * Decoding is not hearing. With the media volume turned to zero the mix
     * plays into nothing, and the aloud set does not move — so the withheld
     * alarm has to be raised by the volume change itself.
     */
    @Test
    fun `media volume turned to zero mid-cry raises the alarm that was withheld`() = runTest {
        container.appSettings.update { it.copy(alertChime = false, alertVibrate = false) }
        val audio = context.getSystemService(AudioManager::class.java)
        createService().get()
        val state = container.monitoringState
        state.put(live("a", "Nursery"))
        listenAloud()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(setOf("a"), state.listeningCameraIds.value)
        state.update("a") { it.copy(phase = SoundDetector.Phase.TRIGGERED) }
        assertNull(container.alertSignaler.alarmingCameraId.value)

        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
        context.sendBroadcast(Intent("android.media.VOLUME_CHANGED_ACTION"))
        shadowOf(Looper.getMainLooper()).idle()

        // Still in the aloud set — it is the volume that changed, not the mix.
        assertEquals(setOf("a"), state.listeningCameraIds.value)
        assertEquals("a", container.alertSignaler.alarmingCameraId.value)
        container.alertSignaler.stop()
    }

    @Test
    fun `a room that has settled is not alarmed for when the speaker goes`() = runTest {
        container.appSettings.update { it.copy(alertChime = false, alertVibrate = false) }
        createService().get()
        val state = container.monitoringState
        state.put(live("a", "Nursery"))
        listenAloud()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(setOf("a"), state.listeningCameraIds.value)

        state.viewerAudible.value = true
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(emptySet<String>(), state.listeningCameraIds.value)
        assertNull(container.alertSignaler.alarmingCameraId.value)
    }

    @Test
    fun `every room the monitor can hear plays aloud together`() = runTest {
        createService().get()
        val state = container.monitoringState
        state.put(live("a", "Nursery"))
        state.put(live("b", "Play room"))
        shadowOf(Looper.getMainLooper()).idle()

        listenAloud()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(setOf("a", "b"), state.listeningCameraIds.value)
    }

    @Test
    fun `a room the monitor starts hearing later joins the mix`() = runTest {
        createService().get()
        val state = container.monitoringState
        state.put(live("a", "Nursery"))
        listenAloud()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(setOf("a"), state.listeningCameraIds.value)

        state.put(live("b", "Play room"))
        shadowOf(Looper.getMainLooper()).idle()

        // The switch means "the house", not the rooms that happened to exist
        // when it was flipped.
        assertEquals(setOf("a", "b"), state.listeningCameraIds.value)
    }

    /**
     * A room whose stream is down has no audio to turn up. Claiming it would
     * make the notification overstate what is playing — and an alert from the
     * one room that *is* playing would light the screen to name it, as though
     * there were another it could be mistaken for.
     */
    @Test
    fun `a room whose stream is down is not claimed aloud`() = runTest {
        createService().get()
        val state = container.monitoringState
        state.put(live("a", "Nursery"))
        state.put(live("b", "Play room"))
        listenAloud()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(setOf("a", "b"), state.listeningCameraIds.value)

        state.update("b") { it.withConnection(ConnectionState.Reconnecting(1)) }
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(setOf("a"), state.listeningCameraIds.value)
        // The switch stays on: the outage is the network's, not the user's.
        assertEquals(SoundMode.ALL_ALOUD, soundMode())

        state.update("b") { it.withConnection(ConnectionState.Live) }
        shadowOf(Looper.getMainLooper()).idle()

        // Live off the player's clock alone, nothing decoded yet on the new
        // connection: still not something the speaker can be claimed to play.
        assertEquals(setOf("a"), state.listeningCameraIds.value)

        state.update("b") { it.copy(level = 0.1f) }
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(setOf("a", "b"), state.listeningCameraIds.value)
    }

    /**
     * Losing one room does not stop the others, and does not touch the
     * switch: there is still something behind it.
     */
    @Test
    fun `switching off one room leaves the rest playing`() = runTest {
        container.cameras.upsert(Camera("a", "Nursery", "rtsp://127.0.0.1:1/a"))
        container.cameras.upsert(Camera("b", "Hall", "rtsp://127.0.0.1:1/b"))
        createService().get()
        shadowOf(Looper.getMainLooper()).idle()
        val state = container.monitoringState
        listenAloud()
        shadowOf(Looper.getMainLooper()).idle()

        container.cameras.setEnabled("a", false)
        shadowOf(Looper.getMainLooper()).idle()

        // Which rooms play follows the monitors on its own (see the tests
        // above); what matters here is that the switch is left alone.
        assertEquals(SoundMode.ALL_ALOUD, soundMode())
        assertFalse("a" in state.listeningCameraIds.value)
    }

    /**
     * The sound mode is the viewer's setting as much as this service's, and
     * the viewer can play rooms the service cannot listen to. So nothing left
     * to hear silences the speaker without touching the setting; the
     * notification, which reads the record rather than the ask, already admits
     * to nothing being aloud.
     */
    @Test
    fun `switching off the last room silences the speaker but keeps the mode`() = runTest {
        container.cameras.upsert(Camera("a", "Nursery", "rtsp://127.0.0.1:1/a"))
        createService().get()
        shadowOf(Looper.getMainLooper()).idle()
        val state = container.monitoringState
        listenAloud()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(SoundMode.ALL_ALOUD, soundMode())

        container.cameras.setEnabled("a", false)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(SoundMode.ALL_ALOUD, soundMode())
        assertEquals(emptySet<String>(), state.listeningCameraIds.value)
    }

    @Test
    fun `a speaker asked for before the monitors exist is not snatched away`() = runTest {
        container.cameras.upsert(Camera("a", "Nursery", "rtsp://127.0.0.1:1/a"))

        // The viewer offers the switch as soon as the service is running, which
        // is before the first reconcile has resolved anything. "Nothing
        // monitored" means "not yet" here, and must not be read as "gone".
        createService().get()
        listenAloud()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(SoundMode.ALL_ALOUD, soundMode())
    }

    /**
     * Stopping is an exit, and the sound mode is meant to be found again the
     * way it was left. What must not survive is the record: a target left
     * standing would be picked straight back up by the next service, before it
     * had won the speaker or decoded anything.
     */
    @Test
    fun `stopping takes the speaker with it and leaves the mode alone`() = runTest {
        val controller = createService()
        listenAloud()
        container.monitoringState.listeningCameraIds.value = setOf("a")

        destroy(controller)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(SoundMode.ALL_ALOUD, soundMode())
        assertEquals(emptySet<String>(), container.monitoringState.listeningCameraIds.value)
    }

    /**
     * The whole alert: no card, no screen, no alarm. The detector still runs,
     * so the meters and the status line say the room is loud, but the user
     * asked not to be told and nothing tells them.
     */
    @Test
    fun `with alerts off a loud room reaches nobody`() = runTest {
        container.appSettings.update { it.copy(alertsEnabled = false) }
        createService().get()
        val state = container.monitoringState
        state.put(live("a", "Nursery"))
        // The one path into raiseAlert that needs no decoder: a room that was
        // being heard aloud stops being heard while its detector is triggered.
        listenAloud()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(setOf("a"), state.listeningCameraIds.value)
        state.update("a") { it.copy(phase = SoundDetector.Phase.TRIGGERED) }

        state.viewerAudible.value = true
        shadowOf(Looper.getMainLooper()).idle()

        assertNull(container.alertSignaler.alarmingCameraId.value)
        assertNull(state.lastAlertCameraId.value)
        assertNull(
            shadowOf(context.getSystemService(NotificationManager::class.java))
                .getNotification(MonitoringNotifications.ALERT_NOTIFICATION_ID),
        )
    }

    /** An alarm already sounding has nothing left to mean once alerts are off. */
    @Test
    fun `switching alerts off silences an alarm already sounding`() = runTest {
        container.appSettings.update { it.copy(alertChime = false, alertVibrate = false) }
        createService().get()
        val state = container.monitoringState
        state.put(live("a", "Nursery"))
        listenAloud()
        shadowOf(Looper.getMainLooper()).idle()
        state.update("a") { it.copy(phase = SoundDetector.Phase.TRIGGERED) }
        state.viewerAudible.value = true
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("a", container.alertSignaler.alarmingCameraId.value)

        container.appSettings.update { it.copy(alertsEnabled = false) }
        shadowOf(Looper.getMainLooper()).idle()

        assertNull(container.alertSignaler.alarmingCameraId.value)
        assertNull(
            shadowOf(context.getSystemService(NotificationManager::class.java))
                .getNotification(MonitoringNotifications.ALERT_NOTIFICATION_ID),
        )
    }

    @Test
    fun `a speaker nothing will grant switches itself back off`() = runTest {
        // Something else owns the speaker for the whole of this test.
        val audioManager = context.getSystemService(AudioManager::class.java)
        shadowOf(audioManager).setNextFocusRequestResponse(
            AudioManager.AUDIOFOCUS_REQUEST_FAILED,
        )
        createService().get()

        listenAloud()
        shadowOf(Looper.getMainLooper()).idle()

        // A control that says "on" next to a phone that is silent is worse
        // than one that visibly did not take.
        assertEquals(SoundMode.OFF, awaitSoundMode(SoundMode.OFF))
        assertEquals(emptySet<String>(), container.monitoringState.listeningCameraIds.value)
    }

    @Test
    fun `stopping is left to whoever emptied the camera set`() = runTest {
        container.cameras.upsert(Camera("a", "Nursery", "rtsps://cam:7441/a"))
        container.monitoringState.serviceRunning.value = true

        // The mirror of arming: settings switching off the last listenable
        // camera is what ends monitoring, not the service deciding for itself.
        assertTrue(container.shouldStopMonitoring())
    }

    @Test
    fun `a listenable camera is not a reason to stop`() = runTest {
        container.cameras.upsert(Camera("a", "Nursery", "rtsp://cam:7447/a"))
        container.monitoringState.serviceRunning.value = true

        assertFalse(container.shouldStopMonitoring())
    }

    @Test
    fun `an already-stopped monitor is not stopped again`() = runTest {
        container.monitoringState.serviceRunning.value = false

        assertFalse(container.shouldStopMonitoring())
    }
}

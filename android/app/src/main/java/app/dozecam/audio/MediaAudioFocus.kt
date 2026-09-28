package app.dozecam.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The app's single owner of media audio focus.
 *
 * One owner rather than one per player, and one for the whole app rather than
 * one per screen. The grid hands sound from camera to camera every few seconds,
 * and a focus request per tile would ask the system for the same focus over and
 * over — enough to make every other app on the phone duck and recover on a
 * loop. Worse, a second request from this same process arrives at the first as
 * a *loss*: two owners would mean switching listen mode on silenced the viewer,
 * and the viewer coming back silenced the nursery. What the rest of the system
 * cares about is whether Dozecam is making noise at all, which is exactly what
 * this tracks.
 *
 * App-scoped rather than activity-scoped because listen mode has to outlive the
 * viewer: the speaker is the whole point of it, and the screen being off is
 * when it matters.
 *
 * Main-thread object: the system delivers focus changes there, and both callers
 * act on them there — Compose state in the viewer, and the monitoring service's
 * main-dispatcher scope.
 */
class MediaAudioFocus(context: Context) {

    /**
     * Who wants the speaker. Named rather than counted because the two outlive
     * each other in both directions: the viewer comes and goes while listen
     * mode plays all night, and listen mode is switched on from a viewer that
     * is then closed. A count could not tell a second request from the same
     * client apart from a genuinely new one.
     */
    enum class Client { VIEWER, LISTEN }

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)

    /**
     * Whether Dozecam may make a sound this instant: focus held, and nothing
     * else currently borrowing the speaker.
     *
     * The players are gated on this rather than on the user's switches, and it
     * moves synchronously with the system's callback. Routing it through a
     * stored setting instead would leave a window — however short — where the
     * cameras play on after focus is gone, which is exactly the window
     * headphones being pulled out cannot have.
     */
    private val _granted = MutableStateFlow(false)
    val granted: StateFlow<Boolean> = _granted.asStateFlow()

    /**
     * What each holder wants to be told when sound has ended for good. They
     * switch their own sound off; nothing here decides that.
     */
    private val holders = mutableMapOf<Client, () -> Unit>()

    private var held = false
    private var noisyReceiverRegistered = false

    private val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                .build(),
        )
        // Without this the framework ducks us itself and never tells the
        // listener, so the duck branch below would be dead code and a room
        // would go faintly quiet under someone else's navigation prompt —
        // which reads as a settled room. Asking to be told instead means we
        // can go properly silent and say why.
        .setWillPauseWhenDucked(true)
        .setOnAudioFocusChangeListener(::onFocusChange)
        .build()

    /** Headphones out. Room audio must not jump to the loudspeaker on its own. */
    private val becomingNoisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != AudioManager.ACTION_AUDIO_BECOMING_NOISY) return
            loseForGood()
        }
    }

    /**
     * Asks for focus on [client]'s behalf; false means something else owns it
     * and that client stays silent. A client that already holds, or that joins
     * one already holding, is granted without asking the system again.
     */
    fun request(client: Client, onLost: () -> Unit): Boolean {
        if (held) {
            holders[client] = onLost
            return true
        }
        val granted = audioManager.requestAudioFocus(request) ==
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (!granted) return false
        holders[client] = onLost
        held = true
        _granted.value = true
        ContextCompat.registerReceiver(
            appContext,
            becomingNoisy,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        noisyReceiverRegistered = true
        return true
    }

    /**
     * Idempotent, and only the last holder out abandons the focus: the viewer
     * releasing as it goes to the background must not silence a nursery that is
     * still meant to be playing aloud.
     */
    fun release(client: Client) {
        holders.remove(client)
        if (holders.isEmpty()) abandon()
    }

    private fun abandon() {
        if (noisyReceiverRegistered) {
            appContext.unregisterReceiver(becomingNoisy)
            noisyReceiverRegistered = false
        }
        if (!held) return
        held = false
        // Set before abandoning, so nothing can observe "focus gone, still
        // allowed to play" even for an instant.
        _granted.value = false
        audioManager.abandonAudioFocusRequest(request)
    }

    /**
     * Visible for testing: the system's callback delegates straight here, so
     * the decisions can be exercised without a real [AudioManager].
     */
    internal fun onFocusChange(change: Int) {
        when (change) {
            // Only if the focus is still ours: a gain arriving after we let go
            // must not quietly hand the speaker back.
            AudioManager.AUDIOFOCUS_GAIN -> if (held) _granted.value = true

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            // Ducked rather than silenced is worse than useless here: a room
            // that sounds quiet under someone else's audio is indistinguishable
            // from a quiet room, and this is the one app whose whole job is
            // telling those apart. Go silent and say so instead.
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
            -> _granted.value = false

            else -> loseForGood()
        }
    }

    /**
     * Everyone loses together: the speaker is one device, and a holder left
     * believing it still had it would show a switch that is on next to a phone
     * that is silent. Cleared before the callbacks run, because each of them
     * turns a switch off and comes straight back through [release].
     */
    private fun loseForGood() {
        val lost = holders.values.toList()
        holders.clear()
        abandon()
        lost.forEach { it() }
    }
}

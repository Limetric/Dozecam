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
 * The viewer's single owner of media audio focus.
 *
 * One owner rather than one per player. The grid hands sound from camera to
 * camera every few seconds, and a focus request per tile would ask the system
 * for the same focus over and over — enough to make every other app on the
 * phone duck and recover on a loop. What the rest of the system cares about is
 * whether Dozecam is making noise at all, which is exactly what this tracks.
 *
 * Main-thread object: the system delivers focus changes there, and the callers
 * that act on them are Compose state.
 */
class ViewerAudioFocus(
    context: Context,
    /**
     * Sound has ended for good — something else took the speaker, or the
     * headphones came out. The caller switches its own sound off; nothing here
     * decides that.
     */
    private val onLost: () -> Unit,
) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)

    /**
     * Whether the viewer may make a sound this instant: focus held, and
     * nothing else currently borrowing the speaker.
     *
     * The players are gated on this rather than on the user's switch, and it
     * moves synchronously with the system's callback. Routing it through the
     * stored setting instead would leave a window — however short — where the
     * cameras play on after focus is gone, which is exactly the window
     * headphones being pulled out cannot have.
     */
    private val _granted = MutableStateFlow(false)
    val granted: StateFlow<Boolean> = _granted.asStateFlow()

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
            release()
            onLost()
        }
    }

    /** Asks for focus; false means something else owns it and we stay silent. */
    fun request(): Boolean {
        if (held) return true
        val granted = audioManager.requestAudioFocus(request) ==
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (granted) {
            held = true
            _granted.value = true
            ContextCompat.registerReceiver(
                appContext,
                becomingNoisy,
                IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            noisyReceiverRegistered = true
        }
        return granted
    }

    /** Idempotent: the viewer releases on its own and again when focus is lost. */
    fun release() {
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

            else -> {
                release()
                onLost()
            }
        }
    }
}

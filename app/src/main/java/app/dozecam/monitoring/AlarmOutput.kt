package app.dozecam.monitoring

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibratorManager

/**
 * Alarm usage, in one place, because it is the single fact the whole feature
 * rests on: it is what keeps an alert audible on a phone set to silent, gets it
 * through Do Not Disturb's default rules, and puts it on alarm volume rather
 * than the media stream the viewer is using. The tone this replaced was played
 * with *notification* usage, which is why it made no sound at night at all.
 */
object AlarmAudio {
    val ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()
}

/**
 * The speaker, behind an interface. Everything about *when* to make noise is
 * decided by [AlarmSchedule] and [AlertSignaler]; this only obeys, which is what
 * lets the schedule be tested without a device.
 */
interface AlarmPlayer {
    /** Starts one burst at [volume] (0..1 of the alarm stream), replacing any burst still playing. */
    fun start(uri: Uri, volume: Float)

    /** Adjusts the burst in flight; ignored if nothing is playing. */
    fun setVolume(volume: Float)

    fun stop()
}

interface AlarmVibrator {
    fun pulse()
    fun cancel()
}

/**
 * Alarm-usage playback: a silent ringer and Do Not Disturb's default priority
 * rules do not apply to it, and it rides the alarm stream rather than media, so
 * whatever the viewer is playing has no bearing on how loud the alert is.
 */
class MediaPlayerAlarmPlayer(private val context: Context) : AlarmPlayer {

    private var player: MediaPlayer? = null

    override fun start(uri: Uri, volume: Float) {
        stop()
        val next = MediaPlayer()
        next.setAudioAttributes(AlarmAudio.ATTRIBUTES)
        next.setVolume(volume, volume)
        next.setOnErrorListener { _, _, _ ->
            // A sound that has gone missing (an SD card ringtone, a revoked
            // grant) must not take the alarm down with it: the vibration and the
            // next burst carry on.
            release(next)
            true
        }
        next.setOnPreparedListener { prepared ->
            // An acknowledgement can land between asking for this burst and it
            // becoming ready. Starting the player we have already let go of
            // would sound an alert the user has just silenced.
            if (player === prepared) prepared.start()
        }
        player = next
        try {
            next.setDataSource(context, uri)
            next.prepareAsync()
        } catch (_: Exception) {
            // setDataSource throws for an unreadable or malformed URI, and
            // prepareAsync for a player the failure left in a bad state.
            release(next)
        }
    }

    override fun setVolume(volume: Float) {
        try {
            player?.setVolume(volume, volume)
        } catch (_: IllegalStateException) {
            // Released underneath us by an error; the next burst rebuilds it.
        }
    }

    override fun stop() {
        player?.let(::release)
    }

    private fun release(target: MediaPlayer) {
        if (player === target) player = null
        try {
            target.reset()
        } catch (_: IllegalStateException) {
            // Already released.
        }
        target.release()
    }
}

/**
 * Longer and harder than the in-hand waveform this replaced: the phone this has
 * to reach is face-down on a nightstand, or under a pillow.
 */
class SystemAlarmVibrator(context: Context) : AlarmVibrator {

    private val vibrator =
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager)
            .defaultVibrator

    override fun pulse() {
        val effect = VibrationEffect.createWaveform(PATTERN, AMPLITUDES, -1)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            vibrator.vibrate(
                effect,
                VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM),
            )
        } else {
            // Alarm usage on the pre-33 overload, which is what keeps the
            // vibration alive on a phone set to silent.
            @Suppress("DEPRECATION")
            vibrator.vibrate(effect, AlarmAudio.ATTRIBUTES)
        }
    }

    override fun cancel() {
        vibrator.cancel()
    }

    companion object {
        val PATTERN = longArrayOf(0, 700, 300, 700, 300, 700)
        val AMPLITUDES = intArrayOf(0, 255, 0, 255, 0, 255)
    }
}

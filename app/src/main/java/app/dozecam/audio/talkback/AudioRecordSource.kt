package app.dozecam.audio.talkback

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.util.Log

/**
 * The phone's microphone, a frame at a time.
 *
 * Captured on [MediaRecorder.AudioSource.VOICE_COMMUNICATION] for the echo
 * cancellation, noise suppression and gain control a device brings with it —
 * this is a phone speaking into a room that is being listened to through the
 * same phone, which is the loop that source exists for. It is not the primary
 * defence: holding a button to talk and ducking the room while it is held
 * settles the feedback without asking physics for a favour. This just makes the
 * result pleasanter.
 *
 * That source does not offer every rate on every device, and 24 kHz is the one
 * Protect asks for most. Where it is unavailable, capture happens at a whole
 * multiple and is averaged down.
 */
class AudioRecordSource private constructor(
    private val record: AudioRecord,
    private val captureSamples: Int,
    private val downsampleFactor: Int,
) : PcmSource {

    private val captureBuffer = ShortArray(captureSamples)
    private val echoCanceler: AcousticEchoCanceler? =
        runCatching {
            AcousticEchoCanceler.create(record.audioSessionId)?.apply { enabled = true }
        }.getOrNull()

    init {
        record.startRecording()
    }

    override fun read(into: ShortArray): Boolean {
        var filled = 0
        // AudioRecord returns what it has rather than what was asked for, so a
        // frame is assembled rather than assumed.
        while (filled < captureSamples) {
            val read = record.read(captureBuffer, filled, captureSamples - filled)
            if (read <= 0) return false
            filled += read
        }
        val frame = PcmDownsampler.byFactor(captureBuffer, downsampleFactor)
        frame.copyInto(into, 0, 0, minOf(frame.size, into.size))
        return true
    }

    override fun close() {
        echoCanceler?.runCatching { release() }
        runCatching { record.stop() }
        record.release()
    }

    companion object {
        private const val TAG = "AudioRecordSource"

        /**
         * Devices that will not capture at the camera's rate are asked for this
         * instead; it divides every rate the platform Opus encoder accepts, and
         * is the rate the voice-communication source is likeliest to offer.
         */
        private const val FALLBACK_CAPTURE_RATE = 48_000

        /**
         * Opens the microphone for [format], or returns null if this device
         * cannot be made to produce that rate at all.
         *
         * Requires `RECORD_AUDIO`; callers check before reaching here, which is
         * what the availability states are for.
         */
        @SuppressLint("MissingPermission")
        fun open(format: TalkbackFormat.Speakable): AudioRecordSource? {
            val candidates = listOf(format.sampleRate, FALLBACK_CAPTURE_RATE).distinct()
            for (captureRate in candidates) {
                val factor = PcmDownsampler.factorBetween(captureRate, format.sampleRate)
                if (factor == null) continue
                val captureSamples = format.frameSamples * factor
                val record = runCatching { recordAt(captureRate, captureSamples) }.getOrNull()
                if (record != null) {
                    if (captureRate != format.sampleRate) {
                        Log.i(TAG, "capturing at $captureRate, averaging down by $factor")
                    }
                    return AudioRecordSource(record, captureSamples, factor)
                }
            }
            Log.w(TAG, "no usable capture rate for ${format.sampleRate}Hz")
            return null
        }

        @SuppressLint("MissingPermission")
        private fun recordAt(captureRate: Int, captureSamples: Int): AudioRecord? {
            val minimum = AudioRecord.getMinBufferSize(
                captureRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minimum <= 0) return null

            // Several frames of headroom: enough that a scheduling hiccup does
            // not lose audio, small enough that what comes back is still recent.
            val bufferBytes = maxOf(minimum, captureSamples * Short.SIZE_BYTES * 4)
            val record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                captureRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferBytes,
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                return null
            }
            return record
        }
    }
}

package app.dozecam.audio.talkback

import android.os.Bundle
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.locks.LockSupport
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The one thing no amount of unit testing can settle: whether a camera accepts
 * the packets *we* build.
 *
 * ffmpeg's were proven by hand against a real camera before any of this was
 * written, but ffmpeg is not what ships. This encodes with the platform Opus
 * encoder, wraps frames with [RtpPacketiser], paces them with [TalkbackPacer]
 * and sends them from the phone — the exact path a held button will take.
 *
 * It needs a camera, so it is skipped unless one is named:
 *
 *   ./gradlew :app:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.talkbackHost=192.168.1.12
 *
 * The address is not a secret and no console credential is involved: resolve it
 * first with tools/talkback-spike/stage0.sh --probe-only.
 */
@RunWith(AndroidJUnit4::class)
class TalkbackWireTest {

    private val args: Bundle get() = InstrumentationRegistry.getArguments()

    private fun arg(name: String): String? = args.getString(name)

    /**
     * Runs everywhere, camera or not: if the platform encoder is missing or
     * refuses the format, everything downstream is moot.
     */
    @Test
    fun the_platform_encoder_produces_opus_frames_from_pcm() {
        val rate = 24_000
        val frameSamples = rate * TalkbackFormat.FRAME_MILLIS / 1000
        val frames = mutableListOf<ByteArray>()

        OpusEncoder(rate).use { encoder ->
            repeat(50) { index ->
                val pcm = tone(frameSamples, rate, frequency = 1_000.0, fromSample = index * frameSamples)
                frames += encoder.encode(pcm, presentationTimeUs = index * 20_000L)
            }
            frames += encoder.finish()
        }

        // Roughly one packet per input frame once primed, and none of them the
        // OpusHead/OpusTags the encoder opens with.
        assertTrue("expected encoded frames, got ${frames.size}", frames.size > 40)
        assertTrue("frames should be non-empty", frames.all { it.isNotEmpty() })
        assertTrue(
            "OpusHead must never reach the wire",
            frames.none { it.size >= 8 && String(it, 0, 8) == "OpusHead" },
        )
    }

    /**
     * Two sirens, so the tail can be judged against its absence in one trip to
     * the room. The first stops dead, the way releasing a button naively would;
     * the second closes on silence and flushes what the encoder still holds.
     *
     * A siren rather than a steady tone, and deliberately unlike anything the
     * ffmpeg spike played, so what comes out of the speaker cannot be mistaken
     * for an echo of an earlier test.
     */
    @Test
    fun a_camera_accepts_the_packets_this_app_builds() {
        val host = arg("talkbackHost")
        assumeTrue("no talkbackHost given; skipping the camera leg", host != null)

        val port = arg("talkbackPort")?.toIntOrNull() ?: 7004
        val rate = arg("talkbackRate")?.toIntOrNull() ?: 24_000

        val abrupt = sendSiren(host!!, port, rate, ssrc = SSRC, tailFrames = 0, flush = false)
        LockSupport.parkNanos(GAP_MILLIS * 1_000_000L)
        val tailed = sendSiren(
            host, port, rate,
            // A fresh stream gets a fresh SSRC: the sequence restarts at zero,
            // and a receiver told this is the same source would read that as
            // two thousand packets of reordering.
            ssrc = SSRC + 1,
            tailFrames = TalkbackPacer.LEAD_OUT_FRAMES,
            flush = true,
        )

        // Nothing here proves arrival -- UDP cannot. It proves the phone built
        // and released two paced streams without throwing, which is the half a
        // machine can check. The other half is somebody in the room.
        assertTrue("expected packets in the first siren, got $abrupt", abrupt > 100)
        assertTrue("the tailed siren should be longer, got $tailed vs $abrupt", tailed > abrupt)
    }

    private fun sendSiren(
        host: String,
        port: Int,
        rate: Int,
        ssrc: Int,
        tailFrames: Int,
        flush: Boolean,
    ): Int {
        val frameSamples = rate * TalkbackFormat.FRAME_MILLIS / 1000
        val toneFrames = TONE_MILLIS / TalkbackFormat.FRAME_MILLIS
        var bytes = 0
        val packetiser = RtpPacketiser(
            ssrc = ssrc,
            timestampIncrement = 48_000 * TalkbackFormat.FRAME_MILLIS / 1000,
        )
        val pacer = TalkbackPacer(System.nanoTime())
        var sent = 0

        OpusEncoder(rate).use { encoder ->
            TalkbackSender(host, port, packetiser).use { sender ->
                var index = 0L
                repeat(TalkbackPacer.LEAD_IN_FRAMES + toneFrames + tailFrames) { step ->
                    LockSupport.parkNanos(pacer.waitNanos(index, System.nanoTime()))

                    // Silence either side of the notes: before, so the camera's
                    // opening click lands on nothing; after, so its buffer
                    // drains on something real.
                    val note = step - TalkbackPacer.LEAD_IN_FRAMES
                    val pcm = if (note < 0 || note >= toneFrames) {
                        ShortArray(frameSamples)
                    } else {
                        // Alternate every 300 ms: fifteen frames a note.
                        val frequency = if ((note / 15) % 2 == 0) 800.0 else 1_200.0
                        tone(frameSamples, rate, frequency, fromSample = note * frameSamples)
                    }

                    encoder.encode(pcm, presentationTimeUs = index * 20_000L).forEach { frame ->
                        sender.send(frame)
                        sent++
                    }
                    index++
                }
                if (flush) {
                    encoder.finish().forEach { frame ->
                        sender.send(frame)
                        sent++
                    }
                }
                bytes = sender.bytesSent
            }
        }

        Log.i(TAG, "siren tail=$tailFrames flush=$flush -> $sent packets, $bytes bytes")
        return sent
    }

    private fun tone(
        samples: Int,
        sampleRate: Int,
        frequency: Double,
        fromSample: Int,
    ): ShortArray = ShortArray(samples) { i ->
        val t = (fromSample + i).toDouble() / sampleRate
        (sin(2 * PI * frequency * t) * AMPLITUDE).roundToInt().toShort()
    }

    private companion object {
        const val TAG = "TalkbackWireTest"
        const val SSRC = 0x0D02ECA1
        const val AMPLITUDE = 12_000
        const val TONE_MILLIS = 3_000
        const val GAP_MILLIS = 1_500L
    }
}

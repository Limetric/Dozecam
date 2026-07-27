package app.dozecam.player

import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.mp4.FragmentedMp4Extractor
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import app.dozecam.protect.ProtectLivestreamProvider
import app.dozecam.protect.ProtectLivestreamSocket

/**
 * One negotiated livestream, handed over as something Media3 can play.
 *
 * Single-use: the console mints a fresh authorization token per negotiation,
 * so recovering from a drop means opening another connection rather than
 * reopening this one.
 *
 * Shared by the viewer and the monitor, which want different things from the
 * same bytes — pictures on screen, or loudness with the display off — and must
 * not drift apart in how they get them.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class LivestreamConnection private constructor(
    private val socket: ProtectLivestreamSocket,
    val mediaSource: MediaSource,
) {
    fun close() = socket.close()

    companion object {

        /** The pipe is the real source; ExoPlayer only needs a stable identity. */
        private const val LIVESTREAM_URI = "dozecam://livestream"
        private const val TAG = "Dozecam"

        /**
         * Negotiates and opens a connection for [livestream]. Throws whatever
         * the negotiation threw — no console, no session, no route — which the
         * caller reports as a stream failure and retries on its own schedule.
         *
         * [onFailure] arrives on the WebSocket's thread once the stream is
         * running and then dies.
         */
        suspend fun open(
            provider: ProtectLivestreamProvider,
            livestream: StreamSource.Livestream,
            onFailure: (Throwable) -> Unit,
        ): LivestreamConnection {
            val pipe = LivestreamPipe()
            val negotiated = provider.connect(livestream.cameraId, livestream.channel)
            val socket = ProtectLivestreamSocket(
                httpClient = negotiated.client,
                onBytes = pipe::offer,
                // The console names the codecs it is about to send, and until
                // recently that was read and thrown away. Logged, it is one
                // half of the audio picture: what was offered, against what the
                // extractor and decoder made of it.
                onCodec = { Log.i(TAG, "console codec string: $it") },
                onFailure = { cause ->
                    pipe.fail(cause)
                    onFailure(cause)
                },
            )
            socket.open(negotiated.url)
            return LivestreamConnection(
                socket = socket,
                mediaSource = ProgressiveMediaSource.Factory(
                    LivestreamDataSource.Factory(pipe),
                    ExtractorsFactory {
                        arrayOf(FragmentedMp4Extractor(DefaultSubtitleParserFactory()))
                    },
                ).createMediaSource(MediaItem.fromUri(LIVESTREAM_URI)),
            )
        }
    }
}
